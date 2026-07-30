(ns settleops.advisor
  "SettlementAdvisor -- the *contained intelligence node* for the
  marketplace settlement actor.

  It drafts exactly six kinds of proposal from a closed allowlist:
  computing a settlement plan for a basket, binding a seller's payout
  destination, opening an escrow, authorising a release, recording a
  PSP-attested payment capture, and flagging a settlement concern.

  CRITICAL: it is a smart-but-untrusted advisor, and this is the actor
  where that matters most, because the subject is money. Every
  proposal's `:effect` is always `:propose`; nothing here moves funds.
  Every output is censored downstream by `settleops.governor`.

  Note what the advisor does NOT decide: the ARITHMETIC is not its
  opinion. `plan-settlement` calls `marketplace.settlement/settlement-plan`,
  a pure integer allocation over the operator's published fee schedule,
  and the governor independently re-runs `plan-errors` over the result.
  An advisor cannot pay a seller more (or less) by being persuasive --
  it can only propose a basket to run that arithmetic over.

  Like every sibling actor's advisor this is a deterministic mock so the
  actor graph runs offline. In production this calls a real LLM with the
  same proposal shape."
  (:require [marketplace.settlement :as settle]
            [settleops.store :as store]))

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn- propose-plan
  "Draft a settlement plan for a basket. The numbers come from
  `marketplace.settlement`, not from the model."
  [st {:keys [basket-id]}]
  (let [p (store/plan-for st basket-id)]
    {:op        :plan-settlement
     :basket-id basket-id
     :summary   (str basket-id " の精算計画を作成: 総額 " (:plan/gross-minor p)
                     " / 出品者取り分 " (:plan/seller-payout-total-minor p)
                     " / 手数料 " (:plan/operator-total-minor p))
     :rationale "公開済み手数料表に基づく配分計算の提示のみ。資金の移動は行わない。"
     :cites     [basket-id]
     :effect    :propose
     :value     {:basket-id basket-id :plan p}
     :confidence 0.94}))

(defn- propose-destination
  "Draft a payout destination binding. ALWAYS escalates -- deciding
  where a seller's money goes is the money-equivalent of issuing an
  identity."
  [_st {:keys [patch]}]
  (let [d (settle/payout-destination (select-keys patch [:seller :rail :address :verified?]))]
    {:op        :bind-payout-destination
     :basket-id nil
     :summary   (str (:payout/seller d) " の支払先の登録を提案 (" (name (or (:payout/rail d) :unknown)) ")")
     :rationale "支払先候補の提示のみ。実在性・所有者の確認は人間が行い、その確認をもって承認する。"
     :cites     [(:payout/seller d)]
     :effect    :propose
     :value     {:destination d}
     :confidence (or (:confidence patch) 0.8)}))

(defn- propose-escrow
  [st {:keys [basket-id patch]}]
  (let [p (or (store/plan st basket-id) (store/plan-for st basket-id))]
    {:op        :open-escrow
     :basket-id basket-id
     :summary   (str basket-id " のエスクロー開設を提案（保留期間 "
                     (:fee/payout-hold-days (store/fee-schedule st)) " 日）")
     :rationale "買い手の支払いを配達確認まで留め置く記録の作成のみ。資金の預託そのものは決済レールが行う。"
     :cites     [basket-id]
     :effect    :propose
     :value     {:basket-id basket-id
                 :plan p
                 :escrow (settle/escrow {:id (or (:escrow-id patch) (str "esc-" basket-id))
                                         :plan p
                                         :basket basket-id
                                         :opened-at (:opened-at patch)
                                         :release-after (:release-after patch)})}
     :confidence 0.9}))

(defn- propose-release
  "Authorise a release. ALWAYS escalates -- this is the moment money
  actually leaves. The rationale describes the AUTHORISATION, never a
  completed transfer, so it never trips `scope-excluded-terms`."
  [_st {:keys [basket-id patch]}]
  {:op        :propose-release
   :basket-id basket-id
   :summary   (str (:escrow-id patch) " の解放承認を提案")
   :rationale "配達確認と保留期間の経過を根拠とする解放の承認依頼のみ。実際の振替は承認後に決済レールが行う。"
   :cites     (vec (keep identity [basket-id (:escrow-id patch)]))
   :effect    :propose
   :value     {:escrow-id (:escrow-id patch) :basket-id basket-id}
   :confidence (or (:confidence patch) 0.87)})

(defn- propose-payment-capture
  "Relay a capture the HOST observed -- a PSP webhook or the answer to a
  PSP query -- as a proposal to record it.

  The advisor invents nothing here and cannot: the request and the
  attestation come in on the patch, `settleops.governor` re-derives them
  through `marketplace.acceptance/capture-errors`, and the store writes
  what the library derives rather than what this proposal claims. Always
  escalates, because this is the evidence the funds gate stands on."
  [_st {:keys [patch]}]
  (let [{:keys [request attestation]} patch
        order (:accept/order request)]
    {:op        :record-payment-capture
     :basket-id order
     :summary   (str order " の入金記録を提案: " (pr-str (:psp/amount-minor attestation))
                     " " (:psp/currency attestation)
                     " / 出典 " (pr-str (:psp/source attestation)))
     :rationale "PSP が attest した入金事実の記録のみ。資金の移動も返金の実行も行わない。買い手提示の画面は根拠にならない。"
     :cites     (vec (keep identity [order (:psp/transaction-id attestation)]))
     :effect    :propose
     :value     {:order order :request request :attestation attestation}
     :confidence (or (:confidence patch) 0.85)}))

(defn- propose-settlement-concern
  [_st {:keys [basket-id patch]}]
  {:op        :flag-settlement-concern
   :basket-id basket-id
   :summary   (str basket-id " の精算に関する懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale "観察された精算上の懸念事実の報告のみ。支払い紛争の解決や資金の移動は行わない。"
   :cites     [basket-id]
   :effect    :propose
   :value     (merge {:basket-id basket-id} patch)
   :confidence (or (:confidence patch) 0.8)})

(defn infer
  [st {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :plan-settlement         (propose-plan st request)
                   :bind-payout-destination (propose-destination st request)
                   :open-escrow             (propose-escrow st request)
                   :propose-release         (propose-release st request)
                   :record-payment-capture  (propose-payment-capture st request)
                   :flag-settlement-concern (propose-settlement-concern st request)
                   {})]
    ;; Test hook: inject scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Clear before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str
              " -- actually transferred the funds and paid out the seller")
      proposal)))

(defn trace [_request proposal]
  {:t          :advisor-proposal
   :op         (:op proposal)
   :basket-id  (:basket-id proposal)
   :summary    (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request]
      (infer store request))))
