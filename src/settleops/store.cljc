(ns settleops.store
  "SSoT for the marketplace settlement actor -- who is owed what, and
  where it may be sent.

  Directories, all keyed by STRING ids (never keywords):

    destinations  seller id -> payout destination. `:payout/verified?`
                  on these records is the ONLY thing the governor trusts;
                  a proposal claiming a destination is verified is
                  ignored. Binding a destination is the money-equivalent
                  of issuing an identity, so it is always human-gated.
    baskets       basket id -> multi-seller basket lines.
    plans         basket id -> the committed settlement plan.
    escrows       escrow id -> escrow record.
    deliveries    basket id -> bool, delivery confirmation from the
                  fulfilment side. Escrow release requires it.

  This store holds NO money and no keys. It records intentions and
  outcomes; moving funds is a rail's job (`nexus-x402` for the on-chain
  USDC rail, Stripe for cards) and is only ever performed after a human
  approves a release. `marketplace.settlement` computes the arithmetic
  and carries `:plan/custodial? false` to say so on the record itself.

  The ledger stays append-only."
  (:require [marketplace.settlement :as settle]))

(defprotocol Store
  (payout-destination [s seller-id] "Verified payout destination, or nil.")
  (all-payout-destinations [s])
  (basket [s basket-id] "Basket lines for an order, or nil.")
  (all-baskets [s])
  (plan [s basket-id] "The committed settlement plan, or nil.")
  (escrow [s escrow-id])
  (all-escrows [s])
  (delivered? [s basket-id] "Delivery confirmation from the fulfilment side.")
  (fee-schedule [s] "The operator's published commission schedule.")
  (operator [s] "The operator's own payout identity.")
  (ledger [s])
  (settlement-log [s])
  (commit-record! [s record])
  (append-ledger! [s fact])
  (with-delivery [s basket-id delivered?]))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "Self-contained fixtures covering the happy path and each hard check.

    merchant.alpha  payout destination VERIFIED
    merchant.beta   payout destination VERIFIED
    merchant.gamma  payout destination present but NOT verified -- a plan
                    containing them is refused before approval, not
                    discovered at execution time

    basket-1  alpha + beta, delivered
    basket-2  alpha + gamma, delivered (blocked by gamma's destination)
    basket-3  alpha only, NOT delivered (blocks escrow release)"
  []
  {:destinations
   {"merchant.alpha" (settle/payout-destination
                      {:seller "merchant.alpha" :rail :x402
                       :address "0xaaa0000000000000000000000000000000000001"
                       :verified? true})
    "merchant.beta"  (settle/payout-destination
                      {:seller "merchant.beta" :rail :stripe
                       :address "acct_beta" :verified? true})
    "merchant.gamma" (settle/payout-destination
                      {:seller "merchant.gamma" :rail :x402
                       :address "0xccc0000000000000000000000000000000000003"
                       :verified? false})}
   :baskets
   {"basket-1" [(settle/basket-line {:seller "merchant.alpha" :offer "offer.a"
                                     :amount-minor 1200 :qty 1})
                (settle/basket-line {:seller "merchant.beta" :offer "offer.b"
                                     :amount-minor 3300 :qty 3})]
    "basket-2" [(settle/basket-line {:seller "merchant.alpha" :offer "offer.a"
                                     :amount-minor 1200 :qty 1})
                (settle/basket-line {:seller "merchant.gamma" :offer "offer.c"
                                     :amount-minor 900 :qty 1})]
    "basket-3" [(settle/basket-line {:seller "merchant.alpha" :offer "offer.a"
                                     :amount-minor 2400 :qty 2})]}
   :deliveries {"basket-1" true "basket-2" true "basket-3" false}
   :plans {}
   :escrows {}
   :fee-schedule (settle/fee-schedule {:commission-bps 1000 :fixed-minor 50
                                       :payout-hold-days 7})
   :operator "merchant.marketplace-operator"})

;; ----------------------------- MemStore -----------------------------

(defrecord MemStore [a]
  Store
  (payout-destination [_ id] (get-in @a [:destinations id]))
  (all-payout-destinations [_] (sort-by :payout/seller (vals (:destinations @a))))
  (basket [_ id] (get-in @a [:baskets id]))
  (all-baskets [_] (:baskets @a))
  (plan [_ id] (get-in @a [:plans id]))
  (escrow [_ id] (get-in @a [:escrows id]))
  (all-escrows [_] (sort-by :escrow/id (vals (:escrows @a))))
  (delivered? [_ id] (boolean (get-in @a [:deliveries id])))
  (fee-schedule [_] (:fee-schedule @a))
  (operator [_] (:operator @a))
  (ledger [_] (:ledger @a))
  (settlement-log [_] (:settlement-log @a))
  (commit-record! [_ record]
    (swap! a update :settlement-log conj record)
    ;; `:payload` is where `settleops.operation`'s :request-approval node
    ;; stamps `:approved-by`; `:value` is the advisor's own proposal
    ;; payload and never carries it. Reading the approver from :value
    ;; would silently record every release as unattributed.
    (let [{:keys [op value payload]} record]
      (case op
        :bind-payout-destination
        (when-let [d (:destination value)]
          (swap! a assoc-in [:destinations (:payout/seller d)] d))

        :plan-settlement
        (when-let [p (:plan value)]
          (swap! a assoc-in [:plans (:basket-id value)] p))

        :open-escrow
        (when-let [e (:escrow value)]
          (swap! a assoc-in [:escrows (:escrow/id e)] e))

        ;; A release NEVER moves money here. It records that a human
        ;; authorised one; a rail adapter reads the released escrow and
        ;; performs the transfer outside this actor.
        :propose-release
        (swap! a update-in [:escrows (:escrow-id value)]
               (fn [e] (when e (assoc e :escrow/state :released
                                      :escrow/released-by (:approved-by payload)))))

        nil))
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-delivery [s id d?] (swap! a assoc-in [:deliveries id] (boolean d?)) s))

(defn seed-db []
  (->MemStore (atom (assoc (demo-data) :ledger [] :settlement-log []))))

(defn mem-store [m]
  (->MemStore (atom (merge {:destinations {} :baskets {} :plans {} :escrows {}
                            :deliveries {} :ledger [] :settlement-log []
                            :fee-schedule (settle/fee-schedule {:commission-bps 1000})
                            :operator "merchant.marketplace-operator"}
                           m))))

;; ----------------------------- derived views -----------------------------

(defn plan-for
  "Compute (not commit) the settlement plan for a basket, using THIS
  store's published fee schedule and operator identity."
  [s basket-id]
  (when-let [lines (basket s basket-id)]
    (settle/settlement-plan {:lines lines
                             :currency "JPY"
                             :fee-schedule (fee-schedule s)
                             :operator (operator s)})))

(defn destinations-for
  "seller-id -> payout destination, for every seller in a plan. Reads the
  STORE's records, which is what makes `:payout/verified?` ground truth
  rather than self-report."
  [s plan*]
  (into {} (keep (fn [seller]
                   (when-let [d (payout-destination s seller)]
                     [seller d]))
                 (map :alloc/seller (:plan/allocations plan*)))))
