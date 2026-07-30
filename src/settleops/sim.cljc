(ns settleops.sim
  "Offline demo: compute a multi-seller settlement, watch an unverified
  payout destination block a basket, watch a release refused because
  nobody recorded that the buyer paid, record a PSP-attested capture and
  watch the release wait for a human, then watch a refund refused because
  the money already went to the sellers. `clojure -M:dev:run`."
  (:require [langgraph.graph :as g]
            [marketplace.acceptance :as accept]
            [marketplace.settlement :as settle]
            [settleops.operation :as operation]
            [settleops.store :as store]))

(def ^:private now "2026-06-15T00:00:00Z")
(def ^:private ctx {:actor-id "settle-demo" :phase 3 :now now})

(defn- run-req! [actor tid request]
  (g/run* actor {:request request :context ctx} {:thread-id tid}))

(defn- capture-patch
  "A コード決済 capture for `basket`, as the host would hand it over after
  a PSP webhook. `expected` is what the plan says the buyer owes."
  [basket expected]
  {:request (accept/payment-request
             {:order basket :rail :code-payment :mode :mpm-dynamic
              :psp "psp.demo" :expected-minor expected :currency "JPY"
              :expires-at "2026-06-02T00:10:00Z" :reference "psp-ref-demo"})
   :attestation (accept/psp-attestation
                 {:psp "psp.demo" :transaction-id (str "psp-tx-" basket)
                  :amount-minor expected :currency "JPY"
                  :attested-at "2026-06-02T00:05:00Z" :source :webhook})})

(defn -main [& _]
  (let [s (store/seed-db)
        actor (operation/build s)]

    (println "\n=== 1. 複数出品者バスケットの精算計算（自動コミット）===")
    (let [r (run-req! actor "sim-1" {:op :plan-settlement :basket-id "basket-1"})
          p (store/plan s "basket-1")]
      (println "  status     :" (:status r))
      (println "  買い手請求  :" (:plan/buyer-charge-minor p) (:plan/currency p))
      (println "  商品総額    :" (:plan/gross-minor p))
      (doseq [a (:plan/allocations p)]
        (println "    -" (:alloc/seller a)
                 "小計" (:alloc/subtotal-minor a)
                 "→ 受取" (:alloc/seller-payout-minor a)
                 "手数料" (:alloc/commission-minor a)))
      (println "  運営合計    :" (:plan/operator-total-minor p))
      (println "  保存則      :" (:plan/conserved? p) " 預託しない:" (not (:plan/custodial? p))))

    (println "\n=== 2. 未検証の支払先を含むバスケット（HARD hold）===")
    (let [r (run-req! actor "sim-2" {:op :plan-settlement :basket-id "basket-2"})]
      (println "  status     :" (:status r))
      (println "  disposition:" (:disposition (:state r)))
      (println "  violations :" (mapv :rule (:violations (last (store/ledger s))))))

    (println "\n=== 3. 入金の記録が無い解放は人間にすら聞かずに拒否 ===")
    (store/commit-record!
     s {:op :open-escrow
        :value {:escrow (settle/escrow {:id "esc-1" :plan (store/plan-for s "basket-1")
                                        :basket "basket-1"
                                        :opened-at "2026-06-01T00:00:00Z"
                                        :release-after "2026-06-08T00:00:00Z"})}})
    (let [r (run-req! actor "sim-3a" {:op :propose-release :basket-id "basket-1"
                                      :patch {:escrow-id "esc-1"}})]
      (println "  status     :" (:status r))
      (println "  disposition:" (:disposition (:state r)))
      (println "  violations :" (mapv :rule (:violations (last (store/ledger s)))))
      (println "  入金記録    :" (pr-str (store/acceptance s "basket-1"))))

    (println "\n=== 3b. PSP が attest した入金を記録（これも人間の承認が必要）===")
    (let [expected (:plan/buyer-charge-minor (store/plan-for s "basket-1"))
          held (run-req! actor "sim-3b" {:op :record-payment-capture
                                         :basket-id "basket-1"
                                         :patch (capture-patch "basket-1" expected)})]
      (println "  status     :" (:status held) "（自動コミットされない）")
      (let [ok (g/run* actor {:approval {:status :approved :by "treasury-01"}}
                       {:thread-id "sim-3b" :resume? true})
            a (store/acceptance s "basket-1")]
        (println "  status     :" (:status ok))
        (println "  着金        :" (:accept/captured-minor a) "/ 請求" expected
                 "→" (:status (accept/settlement-status a)))
        (println "  出典        :" (:accept/attested-by a) "（買い手提示は拒否される）")))

    (println "\n=== 3c. 入金が記録されたので解放が人間の承認を通る ===")
    (let [held (run-req! actor "sim-3" {:op :propose-release :basket-id "basket-1"
                                        :patch {:escrow-id "esc-1"}})]
      (println "  status     :" (:status held))
      (println "  エスクロー  :" (:escrow/state (store/escrow s "esc-1")) "（承認前）")
      (let [ok (g/run* actor {:approval {:status :approved :by "treasury-01"}}
                       {:thread-id "sim-3" :resume? true})]
        (println "  --- 人間 treasury-01 が承認 ---")
        (println "  status     :" (:status ok))
        (println "  エスクロー  :" (:escrow/state (store/escrow s "esc-1"))
                 "by" (:escrow/released-by (store/escrow s "esc-1")))))

    (println "\n=== 4. 未配達の注文は人間にすら聞かずに拒否 ===")
    (store/commit-record!
     s {:op :open-escrow
        :value {:escrow (settle/escrow {:id "esc-3" :plan (store/plan-for s "basket-3")
                                        :basket "basket-3"
                                        :opened-at "2026-06-01T00:00:00Z"
                                        :release-after "2026-06-08T00:00:00Z"})}})
    (let [r (run-req! actor "sim-4" {:op :propose-release :basket-id "basket-3"
                                     :patch {:escrow-id "esc-3"}})]
      (println "  status     :" (:status r))
      (println "  violations :" (mapv :rule (:violations (last (store/ledger s))))))

    (println "\n=== 5. 解放済みの注文への返金は拒否（二重払いになる）===")
    (let [r (run-req! actor "sim-5" {:op :propose-refund :basket-id "basket-1"
                                     :patch {:amount-minor 1000 :reason "返品"}})]
      (println "  status     :" (:status r))
      (println "  violations :" (mapv :rule (:violations (last (store/ledger s)))))
      (println "  返金記録    :" (store/refunds s "basket-1")))

    (println "\n=== 監査台帳 ===")
    (doseq [f (store/ledger s)]
      (println " " (:t f) (:op f) (or (:basis f) "")))))
