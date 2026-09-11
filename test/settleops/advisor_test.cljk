(ns settleops.advisor-test
  "The advisor is smart-but-untrusted. These tests pin the two properties
  the governor's own tests assume: every proposal is `:propose`-only, and
  the ARITHMETIC is not the advisor's opinion."
  (:require [clojure.test :refer [deftest is testing]]
            [marketplace.acceptance :as accept]
            [settleops.advisor :as advisor]
            [settleops.governor :as governor]
            [settleops.store :as store]))

(def ^:private ops
  [{:op :plan-settlement         :basket-id "basket-1"}
   {:op :bind-payout-destination :patch {:seller "merchant.delta" :rail :stripe
                                         :address "acct_delta" :verified? true}}
   {:op :open-escrow             :basket-id "basket-1"
    :patch {:escrow-id "esc-9" :opened-at "2026-06-01T00:00:00Z"
            :release-after "2026-06-08T00:00:00Z"}}
   {:op :propose-release         :basket-id "basket-1" :patch {:escrow-id "esc-9"}}
   {:op :record-payment-capture  :basket-id "basket-1"
    :patch {:request (accept/payment-request
                      {:order "basket-1" :rail :code-payment :mode :mpm-dynamic
                       :psp "psp.test" :expected-minor 4550 :currency "JPY"
                       :expires-at "2026-06-02T00:10:00Z"})
            :attestation (accept/psp-attestation
                          {:psp "psp.test" :transaction-id "psp-tx-1"
                           :amount-minor 4550 :currency "JPY"
                           :attested-at "2026-06-02T00:05:00Z" :source :webhook})}}
   {:op :propose-refund          :basket-id "basket-1"
    :patch {:amount-minor 4550 :reason "未着" :requested-at "2026-08-01T00:00:00Z"}}
   {:op :flag-settlement-concern :basket-id "basket-1" :patch {:concern :short-payment}}])

(deftest every-proposal-is-propose-only
  (let [st (store/seed-db)]
    (doseq [{:keys [op] :as request} ops]
      (let [p (advisor/infer st request)]
        (is (= :propose (:effect p)) (str op " must never claim an effect"))
        (is (= op (:op p)))
        (is (seq (:summary p)) (str op " must summarise itself"))
        (is (seq (:rationale p)) (str op " must state a rationale"))))))

(deftest the-allowlist-is-closed
  (testing "an op the advisor does not know produces nothing, not a guess"
    (is (= {} (advisor/infer (store/seed-db) {:op :transfer-funds})))
    (is (= {} (advisor/infer (store/seed-db) {:op nil}))))
  (testing "and the seven it does know are exactly the governor's allowlist"
    (is (= governor/allowed-ops (set (map :op ops))))))

(deftest a-capture-proposal-relays-the-psp-and-invents-nothing
  (let [request (:request (:patch (nth ops 4)))
        attestation (:attestation (:patch (nth ops 4)))
        p (advisor/infer (store/seed-db) (nth ops 4))]
    (is (= :record-payment-capture (:op p)))
    (is (= request (get-in p [:value :request])) "relayed verbatim")
    (is (= attestation (get-in p [:value :attestation])))
    (is (= "basket-1" (get-in p [:value :order])))
    (testing "the PSP transaction is cited so the ledger can be reconciled"
      (is (= ["basket-1" "psp-tx-1"] (:cites p))))
    (testing "and the rationale does not claim anything was moved"
      (is (= :propose (:effect p)))
      (is (not (re-find #"送金|移動した|完了した" (:rationale p)))))))

(deftest the-arithmetic-is-not-the-advisors-opinion
  (let [st (store/seed-db)
        p (advisor/infer st {:op :plan-settlement :basket-id "basket-1"})]
    (testing "the plan in the proposal is the one marketplace.settlement computes"
      (is (= (store/plan-for st "basket-1") (get-in p [:value :plan]))))
    (testing "so a persuasive advisor cannot pay a seller more"
      (is (true? (get-in p [:value :plan :plan/conserved?])))
      (is (false? (get-in p [:value :plan :plan/custodial?]))))))

(deftest an-escrow-proposal-reuses-a-committed-plan-when-one-exists
  (let [st (store/seed-db)
        committed (assoc (store/plan-for st "basket-1") :plan/marker :committed)]
    (store/commit-record! st {:op :plan-settlement
                              :value {:basket-id "basket-1" :plan committed}})
    (let [p (advisor/infer st {:op :open-escrow :basket-id "basket-1" :patch {}})]
      (is (= :committed (get-in p [:value :plan :plan/marker]))
          "recomputing here could disagree with the plan a human approved")
      (is (= "esc-basket-1" (get-in p [:value :escrow :escrow/id]))
          "a default escrow id is derived from the basket, not invented per call"))))

(deftest a-drafted-destination-is-only-a-draft
  (let [p (advisor/infer (store/seed-db)
                         {:op :bind-payout-destination
                          :patch {:seller "merchant.delta" :rail :stripe
                                  :address "acct_delta" :verified? true}})]
    (is (= "merchant.delta" (get-in p [:value :destination :payout/seller])))
    (is (= ["merchant.delta"] (:cites p)))
    (testing "the advisor may echo :verified?, and the governor reads the STORE instead"
      (is (nil? (store/payout-destination (store/seed-db) "merchant.delta"))))))

(deftest a-refund-proposal-carries-the-ask-and-not-the-instruction
  (let [p (advisor/infer (store/seed-db) (nth ops 5))]
    (is (= :propose-refund (:op p)))
    (is (= {:order "basket-1" :amount-minor 4550 :reason "未着"
            :requested-at "2026-08-01T00:00:00Z"}
           (:value p)))
    (testing "the instruction is NOT built here -- the store derives it from
              the stored capture and the approver's name"
      (is (not (contains? (:value p) :refund/rail)))
      (is (nil? (get-in p [:value :requested-by]))))
    (testing "and the rationale describes the authorisation, not a done refund"
      (is (= :propose (:effect p)))
      (is (not (re-find #"送金|返金した|完了した" (:rationale p)))))))

(deftest confidence-comes-from-the-patch-when-given
  (is (= 0.42 (:confidence (advisor/infer (store/seed-db)
                                          {:op :propose-release :basket-id "basket-1"
                                           :patch {:escrow-id "e" :confidence 0.42}}))))
  (is (= 0.87 (:confidence (advisor/infer (store/seed-db)
                                          {:op :propose-release :basket-id "basket-1"
                                           :patch {:escrow-id "e"}})))))

(deftest the-out-of-scope-hook-is-what-the-governor-tests-fire-at
  (let [p (advisor/infer (store/seed-db)
                         {:op :plan-settlement :basket-id "basket-1" :out-of-scope? true})]
    (is (re-find #"transferred the funds" (:rationale p))
        "a test hook, not a production path -- it exists to prove the governor blocks it")
    (is (= :propose (:effect p)) "even the hook does not fake an effect")))

(deftest trace-carries-what-an-auditor-needs-and-no-payload
  (let [request {:op :plan-settlement :basket-id "basket-1"}
        p (advisor/infer (store/seed-db) request)
        t (advisor/trace request p)]
    (is (= :advisor-proposal (:t t)))
    (is (= [:t :op :basket-id :summary :confidence] (vec (keys t))))
    (is (not (contains? t :value)) "the ledger records the decision, not the whole plan")))

(deftest the-mock-advisor-satisfies-the-protocol
  (let [st (store/seed-db)
        a (advisor/mock-advisor)]
    (is (satisfies? advisor/Advisor a))
    (is (= (advisor/infer st {:op :plan-settlement :basket-id "basket-1"})
           (advisor/-advise a st {:op :plan-settlement :basket-id "basket-1"})))))
