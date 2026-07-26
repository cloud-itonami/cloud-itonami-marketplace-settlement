(ns settleops.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [marketplace.settlement :as settle]
            [settleops.advisor :as advisor]
            [settleops.governor :as governor]
            [settleops.store :as store]))

(def now "2026-06-15T00:00:00Z")
(def ctx {:actor-id "settle-actor" :phase 3 :now now})

(defn- db [] (store/seed-db))

(defn- advise [st op & [req]]
  (advisor/-advise (advisor/mock-advisor) st (merge {:op op} req)))

(defn- check [st op & [req]]
  (governor/check (merge {:op op} req) ctx (advise st op req) st))

;; ───────────────────────── conservation ─────────────────────────

(deftest a-conserving-plan-passes
  (let [v (check (db) :plan-settlement {:basket-id "basket-1"})]
    (is (false? (:hard? v)) (pr-str (:violations v)))
    (is (true? (:ok? v)))))

(deftest a-plan-that-does-not-add-up-is-a-hard-block
  (testing "money that does not add up is never a confidence question,
            and it is checked here as well as inside the library"
    (let [st (db)
          p (store/plan-for st "basket-1")
          broken (assoc p :plan/conserved? false :plan/seller-payout-total-minor 1)
          v (governor/check {:op :plan-settlement :basket-id "basket-1"} ctx
                            {:op :plan-settlement :basket-id "basket-1"
                             :effect :propose :confidence 0.99
                             :value {:basket-id "basket-1" :plan broken}}
                            st)]
      (is (true? (:hard? v)))
      (is (some #{:not-conserved} (mapv :rule (:violations v)))))))

(deftest the-real-arithmetic-conserves
  (testing "the advisor cannot pay a seller more by being persuasive —
            the numbers come from marketplace.settlement"
    (let [st (db)
          p (store/plan-for st "basket-1")]
      (is (true? (:plan/conserved? p)))
      (is (= 4500 (:plan/gross-minor p)))
      (is (= (:plan/gross-minor p)
             (+ (:plan/seller-payout-total-minor p)
                (:plan/commission-total-minor p))))
      (testing "and the fixed fee is charged to the buyer on top, not skimmed"
        (is (= 4550 (:plan/buyer-charge-minor p)))))))

;; ───────────────────────── payout destinations ─────────────────────────

(deftest an-unverified-payout-destination-blocks-the-plan
  (testing "basket-2 contains merchant.gamma, whose destination the STORE
            records as unverified — caught before approval, not at
            execution time"
    (let [v (check (db) :plan-settlement {:basket-id "basket-2"})]
      (is (true? (:hard? v)))
      (is (some #{:payout-destination-unverified} (mapv :rule (:violations v)))))))

(deftest a-missing-payout-destination-names-the-seller
  (let [st (store/mem-store
            {:baskets {"b" [(settle/basket-line {:seller "merchant.nobody" :offer "o"
                                                 :amount-minor 100 :qty 1})]}})
        v (check st :plan-settlement {:basket-id "b"})]
    (is (true? (:hard? v)))
    (is (some #{:missing-payout-destination} (mapv :rule (:violations v))))))

(deftest binding-a-destination-always-escalates
  (testing "deciding WHERE a seller's money goes is the money-equivalent of
            issuing an identity"
    (let [v (check (db) :bind-payout-destination
                   {:patch {:seller "merchant.delta" :rail :x402
                            :address "0xdddd" :verified? true :confidence 0.99}})]
      (is (false? (:hard? v)) (pr-str (:violations v)))
      (is (true? (:high-stakes? v)))
      (is (false? (:ok? v)) "no confidence value can make this automatic"))))

(deftest a-structurally-broken-destination-is-a-hard-block
  (doseq [patch [{:seller "merchant.delta" :rail :carrier-pigeon :address "x"}
                 {:seller "merchant.delta" :rail :x402 :address ""}
                 {:seller "" :rail :x402 :address "0xdddd"}]]
    (let [v (check (db) :bind-payout-destination {:patch patch})]
      (is (true? (:hard? v)) (pr-str patch)))))

(deftest an-unverified-flag-on-the-draft-is-not-itself-a-block
  (testing "verification is an out-of-band act — the human approving the
            binding is the one asserting it happened, so the draft's own
            :verified? false must not pre-empt that"
    (let [v (check (db) :bind-payout-destination
                   {:patch {:seller "merchant.delta" :rail :x402
                            :address "0xdddd" :verified? false}})]
      (is (false? (:hard? v)) (pr-str (:violations v)))
      (is (true? (:escalate? v))))))

;; ───────────────────────── escrow release ─────────────────────────

(defn- with-escrow [st basket-id & {:keys [state release-after]
                                    :or {state :held release-after "2026-06-08T00:00:00Z"}}]
  (let [e (-> (settle/escrow {:id "esc-1" :plan (store/plan-for st basket-id)
                              :basket basket-id :opened-at "2026-06-01T00:00:00Z"
                              :release-after release-after})
              (assoc :escrow/state state))]
    (store/commit-record! st {:op :open-escrow :value {:escrow e}})
    st))

(deftest release-of-a-delivered-in-window-escrow-escalates-then-is-clean
  (let [st (with-escrow (db) "basket-1")
        v (check st :propose-release {:basket-id "basket-1" :patch {:escrow-id "esc-1"}})]
    (is (false? (:hard? v)) (pr-str (:violations v)))
    (is (true? (:high-stakes? v)))
    (is (false? (:ok? v)) "authorising a release is always a human's call")))

(deftest release-without-delivery-is-a-hard-block
  (testing "an elapsed hold window must NEVER auto-release an undelivered
            order — both conditions, not either"
    (let [st (with-escrow (db) "basket-3")   ; basket-3 is not delivered
          v (check st :propose-release {:basket-id "basket-3" :patch {:escrow-id "esc-1"}})]
      (is (true? (:hard? v)))
      (is (some #{:escrow-not-releasable} (mapv :rule (:violations v)))))))

(deftest release-before-the-window-is-a-hard-block
  (let [st (with-escrow (db) "basket-1" :release-after "2026-12-01T00:00:00Z")
        v (check st :propose-release {:basket-id "basket-1" :patch {:escrow-id "esc-1"}})]
    (is (true? (:hard? v)))
    (is (some #{:escrow-not-releasable} (mapv :rule (:violations v))))))

(deftest releasing-a-disputed-escrow-is-permanently-blocked
  (testing "the only way out of :disputed is a named human's decision —
            no actor resolves a payment dispute (ADR-2607264000 D5)"
    (let [st (with-escrow (db) "basket-1" :state :disputed)
          v (check st :propose-release {:basket-id "basket-1" :patch {:escrow-id "esc-1"}})]
      (is (true? (:hard? v)))
      (is (some #{:escrow-disputed} (mapv :rule (:violations v))))
      (testing "and it is reported as its own distinct fact, not folded into
                'not releasable' — a human reading the ledger needs to know
                WHICH is true"
        (is (not-any? #{:escrow-not-releasable} (mapv :rule (:violations v))))))))

(deftest releasing-an-unknown-escrow-is-a-hard-block
  (let [v (check (db) :propose-release {:basket-id "basket-1" :patch {:escrow-id "esc-nope"}})]
    (is (true? (:hard? v)))
    (is (some #{:escrow-unknown} (mapv :rule (:violations v))))))

(deftest releasing-an-already-released-escrow-is-a-hard-block
  (let [st (with-escrow (db) "basket-1" :state :released)
        v (check st :propose-release {:basket-id "basket-1" :patch {:escrow-id "esc-1"}})]
    (is (true? (:hard? v)))
    (is (some #{:escrow-not-held} (mapv :rule (:violations v))))))

;; ───────────────────────── structural checks ─────────────────────────

(deftest effect-must-be-propose
  (let [st (db)
        v (governor/check {:op :plan-settlement :basket-id "basket-1"} ctx
                          (assoc (advise st :plan-settlement {:basket-id "basket-1"})
                                 :effect :commit)
                          st)]
    (is (true? (:hard? v)))
    (is (some #{:effect-not-propose} (mapv :rule (:violations v))))))

(deftest op-outside-the-allowlist-is-a-scope-violation
  (testing "no op that directly transfers funds is ever in the allowlist"
    (let [v (governor/check {:op :transfer-funds} ctx
                            {:op :transfer-funds :effect :propose :confidence 0.99}
                            (db))]
      (is (true? (:hard? v)))
      (is (some #{:op-not-allowed} (mapv :rule (:violations v)))))))

(deftest scope-exclusion-blocks-claims-of-having-moved-money
  (let [st (db)
        p (advisor/infer st {:op :plan-settlement :basket-id "basket-1" :out-of-scope? true})
        v (governor/check {:op :plan-settlement :basket-id "basket-1"} ctx p st)]
    (is (true? (:hard? v)))
    (is (some #{:scope-excluded} (mapv :rule (:violations v))))))

(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "every legitimate proposal talks about payouts, settlements and
            releases — the excluded terms are phrased as COMPLETED
            transfers so the happy path never self-blocks"
    (let [st (with-escrow (db) "basket-1")]
      (doseq [[op req] [[:plan-settlement {:basket-id "basket-1"}]
                        [:bind-payout-destination {:patch {:seller "merchant.delta"
                                                           :rail :x402 :address "0xd"}}]
                        [:open-escrow {:basket-id "basket-1"
                                       :patch {:opened-at now :release-after now}}]
                        [:propose-release {:basket-id "basket-1" :patch {:escrow-id "esc-1"}}]
                        [:flag-settlement-concern {:basket-id "basket-1"
                                                   :patch {:concern "遅延"}}]]]
        (let [v (check st op req)]
          (is (not-any? #{:scope-excluded} (mapv :rule (:violations v))) (str op)))))))

(deftest low-confidence-escalates
  (let [st (db)
        v (governor/check {:op :plan-settlement :basket-id "basket-1"} ctx
                          (assoc (advise st :plan-settlement {:basket-id "basket-1"})
                                 :confidence 0.2)
                          st)]
    (is (false? (:hard? v)))
    (is (true? (:escalate? v)))))

(deftest settlement-concern-always-escalates
  (let [v (check (db) :flag-settlement-concern
                 {:basket-id "basket-1" :patch {:concern "x" :confidence 0.99}})]
    (is (true? (:high-stakes? v)))
    (is (false? (:ok? v)))))

(deftest plans-are-never-custodial
  (let [p (store/plan-for (db) "basket-1")]
    (is (false? (:plan/custodial? p)))
    (is (true? (:plan/non-adjudicating p)))))
