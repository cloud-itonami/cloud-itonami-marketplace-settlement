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

  Six HARD checks, ALL permanent, un-overridable by any human approval:

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

  Two ESCALATE (SOFT) gates:
    - LLM confidence below the floor.
    - `:bind-payout-destination`, `:propose-release` and
      `:flag-settlement-concern` ALWAYS escalate. Binding where a
      seller's money goes is the money-equivalent of issuing an
      identity, and authorising a release is the moment money actually
      leaves. Neither may EVER become auto-commit-eligible;
      `settleops.phase` keeps all three out of every phase's `:auto`
      set independently -- two layers, not one."
  (:require [clojure.string :as str]
            [marketplace.settlement :as settle]
            [settleops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist. CRITICAL: no op that directly
  transfers funds is EVER a member -- such an op would be a permanent
  scope violation, not merely un-implemented. `:propose-release`
  AUTHORISES a transfer for a rail to perform; it does not perform one."
  #{:plan-settlement :bind-payout-destination :open-escrow
    :propose-release :flag-settlement-concern})

(def always-escalate-ops
  #{:bind-payout-destination :propose-release :flag-settlement-concern})

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
