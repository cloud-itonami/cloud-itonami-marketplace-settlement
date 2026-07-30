(ns settleops.advisor-test
  "The advisor is smart-but-untrusted. These tests pin the two properties
  the governor's own tests assume: every proposal is `:propose`-only, and
  the ARITHMETIC is not the advisor's opinion."
  (:require [clojure.test :refer [deftest is testing]]
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
  (testing "and the five it does know are exactly the governor's allowlist"
    (is (= governor/allowed-ops (set (map :op ops))))))

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
