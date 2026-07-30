(ns settleops.governor
  "SettlementGovernor -- the independent compliance layer standing
  between a computed settlement and anyone's money.

  The advisor has no notion of whether the arithmetic it produced
  actually conserves the buyer's payment, whether the sellers it wants
  to pay have verified destinations, whether an escrow it wants to
  release covers a delivered order, or whether its own `:effect`
  secretly claims to have already moved funds. So this MUST be a
  separate system able to *reject* a proposal and fall back to HOLD.

  ## Nothing here moves money

  A settlement plan is a COMPUTATION -- an auditable statement of who
  should receive what. `marketplace.settlement` stamps
  `:plan/custodial? false` on every plan to say so, and this governor
  treats any claim to have *already* transferred, paid out or settled
  funds as a HARD, permanent scope exclusion. A release, once a human
  approves it, records that authorisation; a rail adapter (nexus-x402
  for on-chain USDC, Stripe for cards) performs the actual transfer
  outside this actor.

  Nine HARD checks, ALL permanent, un-overridable by any human approval:

    1. Plan not conserved       -- seller payouts + commission must equal
                                   gross exactly. Money that does not add
                                   up is never a confidence question, and
                                   it is checked here as well as inside
                                   `marketplace.settlement` -- two layers,
                                   because a rounding leak is silent.
    2. Payout destination       -- every seller in the plan must have a
                                   destination that the STORE records as
                                   `:payout/verified?`. Re-derived from
                                   the store, never from the proposal's
                                   claim. Caught before approval rather
                                   than at execution, the same failure
                                   mode ISIC 4791 guards with its
                                   `:payment-processor-linked?` gate.
    3. Escrow not releasable    -- a release requires BOTH the hold
                                   window to have elapsed AND delivery to
                                   be confirmed. Not either. An elapsed
                                   window on an undelivered order must
                                   never auto-release.
    4. Disputed escrow          -- releasing a `:disputed` escrow is a
                                   HARD block. The ONLY way out of
                                   `:disputed` is a named human's
                                   decision through
                                   `marketplace.settlement/resolve-dispute`;
                                   no actor resolves a payment dispute
                                   (ADR-2607264000 D5, ISIC 4791's
                                   original scope exclusion).
    5. Effect not :propose      -- any other value is a claim to directly
                                   actuate outside governance.
    6. Scope exclusion          -- any claim to have moved funds, plus any
                                   op outside the closed allowlist.
    7. Funds not arrived        -- on a CUSTODIAL flow, an escrow may not
                                   open and a release may not be authorised
                                   unless the store holds a PSP-attested
                                   capture that settles the plan exactly.
                                   See `payment-violations`; this is the
                                   check whose absence meant an unpaid
                                   order could be released in full.
    8. Capture not derivable    -- a `:record-payment-capture` proposal
                                   must survive `marketplace.acceptance`'s
                                   own `capture-errors`, so a buyer's
                                   screenshot, an expired code or a
                                   bound-amount mismatch is refused before
                                   a human is asked to approve it.
    9. Refund has nothing to    -- a refund needs money STILL HELD. Refused
       come from                   when there is no capture, when the escrow
                                   already released (that money went to
                                   sellers; paying the buyer too is paying
                                   twice), when the escrow is disputed
                                   (refunding IS deciding the dispute), or
                                   when the amount exceeds what remains
                                   after earlier refunds.

  Two ESCALATE (SOFT) gates:
    - LLM confidence below the floor.
    - `:bind-payout-destination`, `:propose-release`, `:propose-refund`,
      `:flag-settlement-concern` and `:record-payment-capture` ALWAYS
      escalate. Binding where a seller's money goes is the money-equivalent
      of issuing an identity; authorising a release is the moment money
      leaves toward sellers; a refund is the moment it leaves toward the
      buyer. `:record-payment-capture` is here for a different reason worth
      stating plainly: it writes THE EVIDENCE THE FUNDS GATE STANDS ON. An
      actor that could auto-commit its own payment evidence would be an
      actor that can unlock check 7 by itself, which is not a gate. None of
      the five may EVER become auto-commit-eligible; `settleops.phase`
      keeps all five out of every phase's `:auto` set independently --
      two layers, not one."
  (:require [clojure.string :as str]
            [marketplace.acceptance :as accept]
            [marketplace.settlement :as settle]
            [settleops.rail :as rail]
            [settleops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist. CRITICAL: no op that directly
  transfers funds is EVER a member -- such an op would be a permanent
  scope violation, not merely un-implemented. `:propose-release`
  AUTHORISES a transfer for a rail to perform; it does not perform one."
  #{:plan-settlement :bind-payout-destination :open-escrow
    :propose-release :flag-settlement-concern :record-payment-capture
    :propose-refund})

(def always-escalate-ops
  #{:bind-payout-destination :propose-release :flag-settlement-concern
    :record-payment-capture :propose-refund})

(def scope-excluded-terms
  "Case-insensitive substrings marking a proposal as claiming to have
  moved money.

  CRITICAL: every term is phrased as the COMPLETED transfer ('transferred
  the funds'), never a bare noun like 'payout' or 'settlement' -- a bare
  noun would match inside this actor's own legitimate proposals (whose
  whole job is to talk about payouts and settlements) and self-block the
  happy path. See
  `settleops.governor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`."
  ["transferred the funds" "transferred the money" "have transferred"
   "paid out the seller" "paid the seller" "have paid out"
   "executed the payout" "executed the transfer" "completed the payout"
   "completed the transfer" "settled the funds" "have settled the funds"
   "released the funds to" "moved the funds" "disbursed the funds"
   "resolved the payment dispute" "closed the dispute in"
   "送金した" "送金を実行した" "支払いを実行した" "払い出した"
   "資金を移動した" "決済を完了した" "支払い紛争を解決した"])

;; ----------------------------- checks -----------------------------

(defn- plan-of
  "The plan a proposal is about: the one it carries, or the stored plan
  for the basket it names."
  [proposal st]
  (or (get-in proposal [:value :plan])
      (some->> (get-in proposal [:value :basket-id]) (store/plan st))))

(defn- plan-violations
  "Conservation + payout destinations, delegated to
  `marketplace.settlement/plan-errors` with destinations read FROM THE
  STORE.

  Applies to any op that carries or names a plan -- planning it, and
  opening an escrow over it. A plan that does not add up must not become
  an escrow."
  [proposal st]
  (when (contains? #{:plan-settlement :open-escrow} (:op proposal))
    (if-let [p (plan-of proposal st)]
      (when-let [errs (seq (settle/plan-errors p (store/destinations-for st p)))]
        (mapv (fn [e]
                {:rule (:settlement.error/code e)
                 :detail (or (:settlement.error/detail e)
                             (name (:settlement.error/code e)))})
              errs))
      [{:rule :plan-missing :detail "対象の精算計画が特定できない"}])))

(defn- release-violations
  "A release requires a real escrow, a non-disputed state, an elapsed
  hold window AND confirmed delivery.

  `marketplace.settlement/releasable?` owns the window+delivery rule;
  this function adds the store lookups and the dispute block. The
  dispute block is stated separately from `releasable?` so the ledger
  records WHY -- 'this escrow is disputed' and 'this order is not
  delivered yet' are different facts for a human reading the log."
  [proposal st now]
  (when (= :propose-release (:op proposal))
    (let [eid (get-in proposal [:value :escrow-id])
          e (and eid (store/escrow st eid))]
      (cond
        (nil? e)
        [{:rule :escrow-unknown
          :detail (str (or eid "(escrow-id missing)") " というエスクローは存在しない")}]

        (= :disputed (:escrow/state e))
        [{:rule :escrow-disputed
          :detail "係争中のエスクローの解放は永久に禁止 -- 人間の裁定のみが :disputed を抜けられる"}]

        (not= :held (:escrow/state e))
        [{:rule :escrow-not-held
          :detail (str "状態 " (pr-str (:escrow/state e)) " のエスクローは解放できない")}]

        (not (settle/releasable? e now (store/delivered? st (:escrow/basket e))))
        [{:rule :escrow-not-releasable
          :detail (str "保留期間の経過と配達確認の両方が必要 -- delivered? "
                       (store/delivered? st (:escrow/basket e))
                       " / release-after " (:escrow/release-after e))}]))))

;; ----------------------------- the funds gate -----------------------------

(defn custodial-plan?
  "Does the buyer's money for this plan pass through the OPERATOR?

  Read from the store's payout destinations, per seller, via
  `settleops.rail/instruction-kind`:

    :direct-split (x402)  the buyer paid each seller's own treasury
                          directly at pay time. There is no operator-side
                          receipt to require, and demanding one would
                          block the rail this actor already runs in
                          production. Evidence there is `rail/reconcile`.
    :transfer     (stripe / bank-transfer / a コード決済 PSP settling to
                          the merchant bank account) funds passed through
                          the platform, so the buyer's payment IS a fact
                          this actor can and must check.

  Mixed baskets count as custodial: if even one seller is paid out of
  money the operator received, that money had to arrive."
  [st plan]
  (boolean
   (some (fn [alloc]
           (let [d (store/payout-destination st (:alloc/seller alloc))]
             (= :transfer (rail/instruction-kind (:payout/rail d)))))
         (:plan/allocations plan))))

(defn- payment-violations
  "HARD check 7. On a custodial flow, refuse to open an escrow or
  authorise a release until the store holds a PSP-attested capture that
  settles this plan exactly.

  Read from the STORE, never from the proposal -- a proposal asserting
  `{:paid? true}` is worth exactly as much as one asserting
  `:payout/verified?`, which is to say nothing.

  A MISSING acceptance is refused, not treated as 'probably fine'. That
  asymmetry is the whole point: the failure this closes is an unpaid order
  being released in full, and the only reason it was possible is that
  nobody asked."
  [proposal st]
  (when (contains? #{:open-escrow :propose-release} (:op proposal))
    (let [[order plan]
          (if (= :open-escrow (:op proposal))
            [(get-in proposal [:value :basket-id]) (plan-of proposal st)]
            (let [e (some->> (get-in proposal [:value :escrow-id]) (store/escrow st))]
              [(:escrow/basket e) (:escrow/plan e)]))]
      ;; A missing plan/escrow is already reported by checks 1 and 3; do not
      ;; pile a second diagnosis onto the same cause.
      (when (and order plan (custodial-plan? st plan))
        (let [a (store/acceptance st order)
              {:keys [status expected captured]} (when a (accept/settlement-status a))]
          (cond
            (nil? a)
            [{:rule :payment-not-recorded
              :detail (str order " について PSP が attest した入金の記録がない"
                           " -- 未記録は「未入金」として扱う（不明を良しとしない）")}]

            (not= :captured (:accept/state a))
            [{:rule :payment-not-captured
              :detail (str "入金の状態は " (pr-str (:accept/state a))
                           " であって :captured ではない")}]

            (not= :settled status)
            [{:rule (case status :short :payment-short :over :payment-over :payment-not-settled)
              :detail (str "請求 " expected " に対して着金 " (pr-str captured)
                           " -- 不足なら運営の自腹、超過なら買い手への返金義務が先")}]

            (not (accept/covers-plan? a plan))
            [{:rule :payment-does-not-cover-plan
              :detail (str "着金額が精算計画の買い手請求額 "
                           (:plan/buyer-charge-minor plan) " "
                           (:plan/currency plan) " と一致しない")}]))))))

(defn- escrows-for-order
  "Every escrow this store holds over `order`. Escrows are keyed by escrow
  id, so a refund -- which knows only the order -- has to look across them."
  [st order]
  (filter #(= (str order) (str (:escrow/basket %))) (store/all-escrows st)))

(defn- refund-violations
  "HARD check 9. A refund gives the BUYER money back, so the two things
  that must not be true are: there is nothing to give back, and it has
  already gone somewhere else.

  The amount is checked against the STORED capture through
  `acceptance/refund-instruction`, whose ceiling is what is still held --
  so a second full refund is refused rather than paying the same money back
  twice. Attribution is NOT checked here: the approver's name is stamped by
  `:request-approval` and does not exist yet at governance time. The store
  refuses to book an unattributed refund instead."
  [proposal st]
  (when (= :propose-refund (:op proposal))
    (let [{:keys [order amount-minor]} (:value proposal)
          a (store/acceptance st order)
          released (filter #(= :released (:escrow/state %)) (escrows-for-order st order))
          disputed (filter #(= :disputed (:escrow/state %)) (escrows-for-order st order))]
      (cond
        (str/blank? (str order))
        [{:rule :refund-order-missing :detail "返金対象の注文が特定できない"}]

        (nil? a)
        [{:rule :refund-without-capture
          :detail (str order " について入金の記録がない -- 返す対象が存在しない")}]

        ;; Money that already left to sellers cannot also go back to the
        ;; buyer: that is paying twice. Once a release is authorised the
        ;; correction path is a dispute or a chargeback, not this op.
        (seq released)
        [{:rule :refund-after-release
          :detail (str "escrow " (pr-str (mapv :escrow/id released))
                       " は既に解放済み -- 出品者に渡った金を買い手にも返すことはできない")}]

        ;; Refunding a disputed escrow IS deciding the dispute, and no actor
        ;; in this fleet adjudicates (ADR-2607264000 D5).
        (seq disputed)
        [{:rule :refund-resolves-dispute
          :detail (str "escrow " (pr-str (mapv :escrow/id disputed))
                       " は係争中 -- 返金による解決は resolve-dispute（人間の裁定）のみ")}]

        (not (and (integer? amount-minor) (pos? amount-minor)))
        [{:rule :refund-amount-invalid
          :detail (str "返金額は正の整数（最小単位）でなければならない: "
                       (pr-str amount-minor))}]

        ;; A probe with a placeholder approver: if the library will not build
        ;; the instruction, the reason is the amount or the state, and both
        ;; are decidable now rather than after a human approves.
        (nil? (accept/refund-instruction a {:amount-minor amount-minor
                                            :requested-by "governor-probe"}))
        (if (= :captured (:accept/state a))
          [{:rule :refund-exceeds-held
            :detail (str "返金額 " amount-minor " が保持額 "
                         (accept/net-captured-minor a) " を超える"
                         "（既に返金済み " (accept/refunded-minor a) "）")}]
          [{:rule :refund-without-capture
            :detail (str "入金の状態は " (pr-str (:accept/state a))
                         " -- 返す対象が残っていない")}])))))

(defn- capture-violations
  "HARD check 8. A `:record-payment-capture` proposal must be one
  `marketplace.acceptance` would actually produce.

  The library owns what counts as evidence -- only the PSP speaking
  (`attestation-sources`), never a buyer-presented completion screen, and
  never a capture after the code expired. Re-deriving it here means the
  refusal happens BEFORE a human is asked to approve, rather than the
  store silently writing nothing and everyone assuming it worked."
  [proposal st]
  (when (= :record-payment-capture (:op proposal))
    (let [{:keys [request attestation]} (:value proposal)
          order (:accept/order request)]
      (cond
        (not (and (map? request) (map? attestation)))
        [{:rule :capture-payload-missing
          :detail "入金記録には payment request と PSP attestation の両方が必要"}]

        (seq (accept/payment-request-errors request))
        (mapv (fn [e] {:rule (:accept.error/code e)
                       :detail (or (:accept.error/detail e)
                                   (name (:accept.error/code e)))})
              (accept/payment-request-errors request))

        (seq (accept/capture-errors request attestation))
        (mapv (fn [e] {:rule (:accept.error/code e)
                       :detail (or (:accept.error/detail e)
                                   (name (:accept.error/code e)))})
              (accept/capture-errors request attestation))

        ;; The order must be the one being claimed. Recording merchant A's
        ;; capture against merchant B's order would satisfy every other
        ;; check and unlock the wrong release.
        (not= (str order) (str (get-in proposal [:value :order])))
        [{:rule :capture-order-mismatch
          :detail (str "request の order " (pr-str order) " と提案の order "
                       (pr-str (get-in proposal [:value :order])) " が一致しない")}]

        ;; If a plan is already committed for this order, the amount being
        ;; captured must be the amount that plan says the buyer owes.
        :else
        (when-let [p (store/plan st order)]
          (when-not (accept/covers-plan? request p)
            [{:rule :payment-does-not-cover-plan
              :detail (str "請求額 " (:accept/expected-minor request)
                           " が計画の買い手請求額 " (:plan/buyer-charge-minor p)
                           " と一致しない")}]))))))

(defn- destination-violations
  "For `:bind-payout-destination`: the drafted destination must itself be
  structurally sound. Note this check does NOT accept the proposal's own
  `:payout/verified?` as proof of anything -- verification is an
  out-of-band act, and the human approving this binding is the one
  asserting it happened."
  [proposal]
  (when (= :bind-payout-destination (:op proposal))
    (let [d (get-in proposal [:value :destination])]
      (if-not (map? d)
        [{:rule :destination-missing :detail "支払先の草案がない"}]
        (when-let [errs (seq (remove #(= :payout-destination-unverified
                                         (:settlement.error/code %))
                                     (settle/payout-destination-errors d)))]
          (mapv (fn [e] {:rule (:settlement.error/code e)
                         :detail (or (:settlement.error/detail e)
                                     (name (:settlement.error/code e)))})
                errs))))))

(defn- effect-not-propose-violations [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "資金移動の実行・支払い紛争の解決など確定行為に触れる提案は永久に禁止"}])))

(defn check
  "Censors a SettlementAdvisor proposal. `context` supplies `:now`
  (ISO-8601 UTC) -- this governor has no clock of its own.

  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
           :high-stakes? bool :hard? bool}."
  [_request context proposal store]
  (let [now (:now context)
        hard (into []
                   (concat (plan-violations proposal store)
                           (release-violations proposal store now)
                           (payment-violations proposal store)
                           (capture-violations proposal store)
                           (refund-violations proposal store)
                           (destination-violations proposal)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (always-escalate-ops (:op proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :basket-id  (:basket-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
