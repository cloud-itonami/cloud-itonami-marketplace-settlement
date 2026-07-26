(ns settleops.rail.client
  "The only I/O in this repo — and it cannot move money by itself.

  ## How this namespace is kept safe

  There is no ambient HTTP capability here. Every function that talks to
  a rail takes an `http` FUNCTION as an argument: `(fn [req] response)`.
  The namespace requires no HTTP client, opens no socket and reads no
  credential. A caller who does not supply `http` cannot make a request,
  and the test suite supplies a recording stub, so nothing in this
  repository has ever executed a real transfer.

  On top of that, the one write path (`execute-transfer!`) refuses
  unless the caller passes `:authorised-by` naming a human AND
  `:execute? true`. The default is a dry run that returns the request it
  WOULD have sent. Building the request and sending it are deliberately
  different acts.

  ## The two rails are not symmetric

  - **x402** (`gftdcojp/nexus-x402`): the facilitator holds no keys and
    the buyer pays each seller's treasury directly. There is no send
    endpoint. This client therefore only READS
    `GET /admin/settlements/<seller>` to reconcile what actually landed.
    `execute-transfer!` refuses an x402 instruction outright, because
    there is nothing to execute — the money either arrived or it did
    not.
  - **Stripe**: funds pass through the platform account, so a release
    becomes a real Connect transfer. Stripe takes
    `application/x-www-form-urlencoded`, matching the convention already
    used in `cloud-itonami.edge.billing`.

  Portable `.cljc`: request/response are plain maps, so the same code
  runs under a Cloudflare Worker `fetch`, an HTTP client on the JVM, or
  a stub in tests."
  (:require [clojure.string :as str]
            [settleops.rail :as rail]))

;; ───────────────────────────── x402 (read-only) ─────────────────────────────

(def default-x402-base "https://x402.nexus")

(defn x402-settlements-request
  "The read request for one seller's settled payments.

  `GET /admin/settlements/<seller>` is one of exactly two admin
  endpoints nexus-x402 exposes; the other registers pricing rules.
  Neither sends money."
  [{:keys [base seller since token] :or {base default-x402-base}}]
  {:method  :get
   :url     (str base "/admin/settlements/" seller
                 (when since (str "?since=" since)))
   :headers (cond-> {"accept" "application/json"}
              token (assoc "authorization" (str "Bearer " token)))})

(defn observed-from-x402
  "Reduce a settlements response body to the `{seller amount-minor}` map
  `settleops.rail/reconcile` expects.

  `amount-fn` extracts the minor-unit amount from one settlement entry;
  it is a parameter because the on-the-wire unit is the rail's business
  and this namespace will not guess at a decimal exponent — the same
  reason `marketplace.settlement/->pay-micros` makes the caller state
  `minor-per-unit`."
  [seller body amount-fn]
  (let [entries (or (:settlements body) (get body "settlements") [])]
    {seller (reduce + 0 (keep amount-fn entries))}))

(defn fetch-observed
  "Read settled amounts for every seller in a set of instructions.

  `http` is injected. Returns `{seller amount-minor}` for the sellers the
  rail answered for; a seller the rail has no record of is simply absent,
  which `reconcile` reports as `:missing` rather than as zero — those are
  different facts."
  [http instructions {:keys [base since token amount-fn]
                      :or {amount-fn :amount-minor}}]
  (reduce (fn [acc i]
            (let [seller (:instruction/seller i)
                  resp (http (x402-settlements-request
                              {:base base :seller seller :since since :token token}))]
              (if (= 200 (:status resp))
                (merge acc (observed-from-x402 seller (:body resp) amount-fn))
                acc)))
          {}
          (filter #(= :x402 (:instruction/rail %)) instructions)))

;; ───────────────────────────── stripe (write) ─────────────────────────────

(defn- form-encode
  "Stripe takes `application/x-www-form-urlencoded`, not JSON — the same
  convention `cloud-itonami.edge.billing` already follows."
  [m]
  (str/join "&" (for [[k v] (sort-by (comp str key) m)]
                  (str (name k) "=" v))))

(defn stripe-transfer-request
  "Build the Connect transfer request for ONE instruction. Builds only —
  see `execute-transfer!`.

  `:transfer_group` carries the escrow id so a transfer can always be
  traced back to the authorised release that justified it. An
  `Idempotency-Key` derived from the escrow and seller means a retry
  after a timeout cannot pay twice."
  [{:keys [base secret instruction] :or {base "https://api.stripe.com/v1"}}]
  (let [i instruction]
    {:method  :post
     :url     (str base "/transfers")
     :headers (cond-> {"content-type" "application/x-www-form-urlencoded"}
                secret (assoc "authorization" (str "Bearer " secret))
                true   (assoc "idempotency-key"
                              (str "mp-" (:instruction/escrow i) "-" (:instruction/seller i))))
     :body    (form-encode
               {:amount (:instruction/amount-minor i)
                :currency (str/lower-case (str (:instruction/currency i)))
                :destination (:instruction/to i)
                :transfer_group (:instruction/escrow i)})}))

(defn execute-transfer!
  "The ONE function in this repository that can move money, and it
  refuses by default.

  Requires ALL of:
    - `:execute? true`                 -- an explicit act, not a default
    - `:authorised-by \"<human>\"`     -- a named person, not a service
    - `http`                           -- injected; there is no ambient
                                          client to fall back on
    - a `:transfer`-kind instruction   -- an x402 instruction is refused,
                                          because that rail has no send
                                          endpoint and the buyer already
                                          paid the seller directly

  Without `:execute? true` it returns `{:dry-run? true :request ..}` —
  the exact request it would have sent — so an operator can inspect it,
  diff it, and only then decide.

  Returns `{:refused reason}` when any precondition fails. Refusing is
  the default path and is not an error condition."
  [http {:keys [instruction execute? authorised-by base secret]}]
  (let [errs (rail/instruction-errors instruction)]
    (cond
      (seq errs)
      {:refused :invalid-instruction :errors errs}

      (= :direct-split (:instruction/kind instruction))
      {:refused :rail-has-no-send-endpoint
       :detail "x402 は買い手が出品者の treasury へ直接支払う -- 送金する対象が無い。reconcile を使う"}

      (true? (:instruction/executed? instruction))
      {:refused :already-executed}

      (str/blank? (str authorised-by))
      {:refused :no-named-authoriser}

      :else
      (let [req (stripe-transfer-request {:base base :secret secret
                                          :instruction instruction})]
        (if-not (true? execute?)
          {:dry-run? true :request req
           :would-transfer (:instruction/amount-minor instruction)
           :to (:instruction/to instruction)}
          (if-not http
            {:refused :no-http-client}
            (let [resp (http req)]
              {:executed? (<= 200 (:status resp 500) 299)
               :authorised-by authorised-by
               :status (:status resp)
               :response (:body resp)
               :instruction (assoc instruction :instruction/executed? true)})))))))
