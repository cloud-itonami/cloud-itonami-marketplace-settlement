(ns settleops.edge.worker
  "The settlement actor's Worker.

  Read the ops list and notice what is missing: nothing here moves
  money. `:propose-release` AUTHORISES a transfer for a rail to perform,
  and `settleops.rail` is where an authorised release meets an actual
  payment provider — refusing without a named human of its own. The
  governor's allowlist has no op that transfers funds at all, and that
  is a permanent scope exclusion rather than an unimplemented feature.

  Both money-shaped operations always escalate:

    :bind-payout-destination  deciding where a seller's money goes is
                              the money-equivalent of issuing an
                              identity.
    :propose-release          the moment funds actually leave.

  Baskets come from the order actor's own projection
  (`marketplace.order/->basket-lines`) written into the shared ref, so a
  change to the order shape cannot silently alter a payout. Delivery is
  re-checked here independently rather than trusted from the plan."
  (:require [marketplace.edge :as edge]
            [settleops.advisor :as advisor]
            [settleops.governor :as governor]
            [settleops.phase :as phase]
            [settleops.store :as store]))

(def ^:private ops
  {:advise      (fn [st req] (advisor/-advise (advisor/mock-advisor) st req))
   :check       governor/check
   :disposition phase/verdict->disposition
   :gate        phase/gate
   :commit!     (fn [st proposal req]
                  (store/commit-record! st {:op (:op proposal)
                                            :basket-id (:basket-id req)
                                            :value (:value proposal)
                                            ;; :payload is where an approver is
                                            ;; stamped; :value never carries one.
                                            :payload (:value proposal)}))
   :ledger!     store/append-ledger!
   :hold-fact   governor/hold-fact})

(defn- ctx [body]
  {:actor-id "settleops-edge"
   :phase (get body "phase" 3)
   :now (get body "now" "2026-06-01T00:00:00Z")})

(defn- run [client wants body op patch ref]
  (edge/with-store
    {:client client :wants wants :store-fn store/kotobase-store}
    (fn [st]
      (edge/outcome ref (edge/run ops st (ctx body)
                                  {:op op :basket-id (get body "basket-id")
                                   :ref ref :patch patch})))))

;; ───────────────────────── operations ─────────────────────────

(defn- mirror-basket
  "Mirror an order's basket lines so a plan can be computed against
  them. The lines are the ORDER's projection; settlement does not get to
  author the basket it is about to split."
  [client body]
  (let [bid (get body "basket-id")]
    (edge/with-store
      {:client client :wants {:basket [bid]} :store-fn store/kotobase-store}
      (fn [st]
        (store/put-basket! st bid
                           (mapv (fn [l] {:seller (get l "seller")
                                          :amount-minor (get l "amount-minor")
                                          :currency (get l "currency" "JPY")})
                                 (get body "lines" [])))
        {:ref bid :disposition "commit" :violations []
         :lines (count (get body "lines" []))}))))

(defn- set-config
  "The operator's fee schedule and payout identity. Operator input: a
  commission rate this actor chose for itself would be a conflict of
  interest written into code."
  [client body]
  (let [k (get body "key")]
    (edge/with-store
      {:client client :wants {} :store-fn store/kotobase-store}
      (fn [st]
        (store/put-config! st k (into {} (map (fn [[kk v]] [(keyword kk) v])
                                              (get body "value" {}))))
        {:ref k :disposition "commit" :violations []}))))

(defn- record-delivery
  "Mirror the fulfilment side's delivery fact. Settlement re-checks this
  before releasing anything, which is only a check if it is a separate
  document from the plan it gates."
  [client body]
  (let [bid (get body "basket-id")]
    (edge/with-store
      {:client client :wants {} :store-fn store/kotobase-store}
      (fn [st]
        (store/with-delivery st bid (get body "delivered" true))
        {:ref bid :disposition "commit" :violations []}))))

;; ───────────────────────── routes ─────────────────────────

(defn- gated [request env f]
  (if-not (edge/authorised? request env)
    (js/Promise.resolve (edge/json {:error "unauthorised"} 401))
    (-> (.json request) (.then #(f (js->clj %))) (.then #(edge/json % 200)))))

(defn- routes [client request env method path _url]
  (cond
    (and (= method "POST") (= path "/baskets")) (gated request env #(mirror-basket client %))
    (and (= method "POST") (= path "/config")) (gated request env #(set-config client %))
    (and (= method "POST") (= path "/deliveries")) (gated request env #(record-delivery client %))

    (and (= method "POST") (= path "/destinations"))
    (gated request env
           (fn [b] (run client {:payout :all} b :bind-payout-destination
                        {:seller (get b "seller")
                         :rail (keyword (get b "rail" "bank"))
                         :address (get b "address")
                         :verified? (boolean (get b "verified?"))}
                        (get b "seller"))))

    (and (= method "POST") (= path "/plans"))
    (gated request env
           (fn [b] (run client {:basket [(get b "basket-id")] :payout :all :config :all}
                        b :plan-settlement {} (get b "basket-id"))))

    (and (= method "POST") (= path "/escrows"))
    (gated request env
           (fn [b] (run client {:basket [(get b "basket-id")] :plan [(get b "basket-id")]
                                :payout :all :config :all :escrow :all}
                        b :open-escrow
                        {:escrow-id (get b "escrow-id")
                         :opened-at (get b "opened-at" (get b "now" "2026-06-01T00:00:00Z"))
                         :release-after (get b "release-after")}
                        (get b "basket-id"))))

    (and (= method "POST") (= path "/releases"))
    (gated request env
           (fn [b] (run client {:escrow :all :basket [(get b "basket-id")]
                                :plan [(get b "basket-id")] :delivery :all :config :all}
                        b :propose-release {:escrow-id (get b "escrow-id")}
                        (get b "escrow-id"))))

    (and (= method "GET") (= path "/escrows"))
    (if-not (edge/authorised? request env)
      (js/Promise.resolve (edge/json {:error "unauthorised"} 401))
      (-> (edge/read-all client :escrow)
          (.then (fn [es]
                   (edge/json {:escrows (mapv (fn [e] {:escrow-id (:escrow/id e)
                                                       :basket (:escrow/basket e)
                                                       :state (str (:escrow/state e))
                                                       :released-by (:escrow/released-by e)})
                                              es)}
                              200)))))

    ;; /escalations and /ledger, implemented once in marketplace.edge.
    ;; Every high-stakes move in this actor escalates rather than committing
    ;; on a machine's say-so; without a way to READ those, each of those gates
    ;; is a black hole.
    :else (edge/ledger-routes client request env method path :settleops)))

(def app
  (clj->js
   {:fetch (fn [request env _ctx]
             (edge/serve "cloud-itonami-marketplace-settlement" request env routes))}))
