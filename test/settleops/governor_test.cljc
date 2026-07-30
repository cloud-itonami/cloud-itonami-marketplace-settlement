(ns settleops.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [marketplace.acceptance :as accept]
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

(defn- capture-value
  "A PSP-attested コード決済 capture for `basket`, as `:record-payment-capture`
  carries it."
  [st basket & {:keys [amount source] :or {source :webhook}}]
  (let [expected (or amount (:plan/buyer-charge-minor (store/plan-for st basket)))]
    {:order basket
     :request (accept/payment-request
               {:order basket :rail :code-payment :mode :mpm-dynamic :psp "psp.test"
                :expected-minor expected :currency "JPY"
                :expires-at "2026-06-02T00:10:00Z" :reference "psp-ref-1"})
     :attestation (accept/psp-attestation
                   {:psp "psp.test" :transaction-id "psp-tx-1" :amount-minor expected
                    :currency "JPY" :attested-at "2026-06-02T00:05:00Z" :source source})}))

(defn- with-capture [st basket & opts]
  (store/commit-record! st {:op :record-payment-capture
                            :value (apply capture-value st basket opts)})
  st)

(defn- with-escrow [st basket-id & {:keys [state release-after paid?]
                                    :or {state :held release-after "2026-06-08T00:00:00Z"
                                         paid? true}}]
  (when paid? (with-capture st basket-id))
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

;; ───────────────────────── the funds gate (HARD 7) ─────────────────────────

(defn- rules [v] (set (mapv :rule (:violations v))))

(deftest custodial-is-decided-by-the-payout-rails-not-by-a-flag
  (let [st (db)]
    (testing "basket-1 pays merchant.beta by stripe -- the money passed
              through the operator, so its arrival is checkable"
      (is (true? (governor/custodial-plan? st (store/plan-for st "basket-1")))))
    (testing "basket-3 is merchant.alpha on x402 only -- the buyer paid the
              seller's treasury directly and there is no operator receipt"
      (is (false? (governor/custodial-plan? st (store/plan-for st "basket-3")))))))

(deftest an-unrecorded-payment-refuses-and-says-so
  (let [st (with-escrow (db) "basket-1" :paid? false)
        v (check st :propose-release {:basket-id "basket-1" :patch {:escrow-id "esc-1"}})]
    (is (true? (:hard? v)))
    (is (contains? (rules v) :payment-not-recorded))
    (testing "unknown is refused rather than assumed fine -- the whole point"
      (is (nil? (store/acceptance st "basket-1"))))))

(deftest an-x402-only-release-does-not-need-an-acceptance-record
  (let [st (store/with-delivery (db) "basket-3" true)]
    (with-escrow st "basket-3" :paid? false)
    (let [v (check st :propose-release {:basket-id "basket-3" :patch {:escrow-id "esc-1"}})]
      (is (false? (:hard? v)) (pr-str (:violations v)))
      (is (true? (:high-stakes? v)) "still a human's call, just not a funds refusal"))))

(deftest a-short-or-over-payment-is-refused-in-its-own-words
  (testing "short: paying sellers in full would use the operator's money"
    (let [st (db)]
      (with-capture st "basket-1" :amount 1000)
      (with-escrow st "basket-1" :paid? false)
      (let [v (check st :propose-release {:basket-id "basket-1" :patch {:escrow-id "esc-1"}})]
        (is (true? (:hard? v)))
        (is (some #{:payment-short :payment-does-not-cover-plan} (rules v))
            (pr-str (rules v))))))
  (testing "over: the buyer is owed a refund before anyone is paid"
    (let [st (db)]
      (with-capture st "basket-1" :amount 999999)
      (with-escrow st "basket-1" :paid? false)
      (let [v (check st :propose-release {:basket-id "basket-1" :patch {:escrow-id "esc-1"}})]
        (is (true? (:hard? v)))
        (is (some #{:payment-over :payment-does-not-cover-plan} (rules v))
            (pr-str (rules v)))))))

(deftest a-proposal-cannot-buy-itself-a-funds-clearance
  (testing "the gate reads the STORE; a proposal asserting it was paid is
            worth exactly what a proposal asserting :payout/verified? is"
    (let [st (with-escrow (db) "basket-1" :paid? false)
          proposal (assoc (advise st :propose-release
                                  {:basket-id "basket-1" :patch {:escrow-id "esc-1"}})
                          :paid? true
                          :acceptance {:accept/state :captured :accept/captured-minor 4550})
          v (governor/check {:op :propose-release :basket-id "basket-1"} ctx proposal st)]
      (is (true? (:hard? v)))
      (is (contains? (rules v) :payment-not-recorded)))))

(deftest opening-an-escrow-is-gated-on-payment-too
  (let [st (db)
        v (check st :open-escrow {:basket-id "basket-1" :patch {:opened-at now}})]
    (is (true? (:hard? v)))
    (is (contains? (rules v) :payment-not-recorded))))

;; ───────────────────── recording a capture (HARD 8) ─────────────────────

(defn- check-capture [st value & [conf]]
  (governor/check {:op :record-payment-capture :basket-id (:order value)} ctx
                  {:op :record-payment-capture :effect :propose
                   :confidence (or conf 0.85) :value value
                   :summary "入金記録" :rationale "PSP が attest した入金事実の記録のみ。"}
                  st))

(deftest a-well-formed-capture-passes-but-still-escalates
  (let [st (db)
        v (check-capture st (capture-value st "basket-1"))]
    (is (false? (:hard? v)) (pr-str (:violations v)))
    (is (true? (:high-stakes? v)))
    (is (false? (:ok? v))
        "it writes the evidence the funds gate reads, so it is never automatic")))

(deftest a-buyer-presented-capture-is-a-hard-block
  (let [st (db)]
    (doseq [src [:buyer-screen :buyer-screenshot :buyer-claim]]
      (let [v (check-capture st (capture-value st "basket-1" :source src))]
        (is (true? (:hard? v)) (str src))
        (is (contains? (rules v) :buyer-presented-evidence) (str src))))))

(deftest a-capture-recorded-against-the-wrong-order-is-refused
  (let [st (db)
        v (check-capture st (assoc (capture-value st "basket-1") :order "basket-3"))]
    (is (true? (:hard? v)))
    (is (contains? (rules v) :capture-order-mismatch))))

(deftest a-capture-that-does-not-match-a-committed-plan-is-refused
  (let [st (db)]
    (store/commit-record! st {:op :plan-settlement
                              :value {:basket-id "basket-1"
                                      :plan (store/plan-for st "basket-1")}})
    (let [v (check-capture st (capture-value st "basket-1" :amount 1))]
      (is (true? (:hard? v)))
      (is (contains? (rules v) :payment-does-not-cover-plan)))))

(deftest a-capture-with-no-payload-is-refused-not-silently-dropped
  (let [st (db)]
    (is (contains? (rules (check-capture st {:order "basket-1"}))
                   :capture-payload-missing))))

(deftest the-mock-advisors-capture-proposal-passes-its-own-governor
  (testing "the happy path must not self-block, the same property
            default-mock-advisor-proposals-never-self-trip-scope-exclusion
            pins for the other five ops"
    (let [st (db)
          patch (select-keys (capture-value st "basket-1") [:request :attestation])
          v (check st :record-payment-capture {:basket-id "basket-1" :patch patch})]
      (is (false? (:hard? v)) (pr-str (:violations v)))
      (is (true? (:high-stakes? v))))))

;; ───────────────────── refunds (HARD 9) ─────────────────────

(defn- check-refund [st order amount]
  (check st :propose-refund {:basket-id order :patch {:order order :amount-minor amount}}))

(deftest a-refund-needs-a-capture-to-come-from
  (let [st (db)]
    (is (contains? (rules (check-refund st "basket-1" 100)) :refund-without-capture))
    (testing "and a blank order names that instead"
      (is (contains? (rules (check-refund st "" 100)) :refund-order-missing)))))

(deftest a-well-formed-refund-passes-and-escalates
  (let [st (with-capture (db) "basket-1")
        v (check-refund st "basket-1" 4550)]
    (is (false? (:hard? v)) (pr-str (:violations v)))
    (is (true? (:high-stakes? v)))
    (is (false? (:ok? v)) "money leaving toward the buyer is a human's call too")))

(deftest a-refund-cannot-exceed-what-is-still-held
  (let [st (with-capture (db) "basket-1")]
    (is (contains? (rules (check-refund st "basket-1" 4551)) :refund-exceeds-held))
    (is (contains? (rules (check-refund st "basket-1" 0)) :refund-amount-invalid))
    (is (contains? (rules (check-refund st "basket-1" -100)) :refund-amount-invalid))
    (testing "after a partial refund the ceiling is the remainder"
      (store/commit-record! st {:op :propose-refund
                                :value {:order "basket-1" :amount-minor 550}
                                :payload {:approved-by "treasury-01"}})
      (is (false? (:hard? (check-refund st "basket-1" 4000))))
      (is (contains? (rules (check-refund st "basket-1" 4001)) :refund-exceeds-held)))))

(deftest money-that-already-went-to-sellers-cannot-also-go-back
  (let [st (with-escrow (db) "basket-1")]
    (store/commit-record! st {:op :propose-release
                              :value {:escrow-id "esc-1"}
                              :payload {:approved-by "treasury-01"}})
    (is (= :released (:escrow/state (store/escrow st "esc-1"))))
    (is (contains? (rules (check-refund st "basket-1" 100)) :refund-after-release)
        "refunding after a release pays the same money twice")))

(deftest refunding-a-disputed-escrow-would-be-adjudicating
  (let [st (with-escrow (db) "basket-1" :state :disputed)]
    (is (contains? (rules (check-refund st "basket-1" 100)) :refund-resolves-dispute)
        "resolve-dispute is the only door, and it needs a named human")))

(deftest a-fully-refunded-capture-has-nothing-left-to-refund
  (let [st (with-capture (db) "basket-1")]
    (store/commit-record! st {:op :propose-refund
                              :value {:order "basket-1" :amount-minor 4550}
                              :payload {:approved-by "treasury-01"}})
    (is (= :refunded (:accept/state (store/acceptance st "basket-1"))))
    (is (contains? (rules (check-refund st "basket-1" 1)) :refund-without-capture))))

(deftest a-refund-closes-the-funds-gate-behind-it
  (testing "the release that the capture would have funded is refused again"
    (let [st (with-escrow (db) "basket-1")]
      (is (false? (:hard? (check st :propose-release
                                 {:basket-id "basket-1" :patch {:escrow-id "esc-1"}}))))
      (store/commit-record! st {:op :propose-refund
                                :value {:order "basket-1" :amount-minor 4550}
                                :payload {:approved-by "treasury-01"}})
      (let [v (check st :propose-release {:basket-id "basket-1" :patch {:escrow-id "esc-1"}})]
        (is (true? (:hard? v)))
        (is (some #{:payment-not-captured :payment-not-recorded} (rules v))
            (pr-str (rules v))))))
  (testing "and a partial refund leaves it short rather than settled"
    (let [st (with-escrow (db) "basket-1")]
      (store/commit-record! st {:op :propose-refund
                                :value {:order "basket-1" :amount-minor 50}
                                :payload {:approved-by "treasury-01"}})
      (let [v (check st :propose-release {:basket-id "basket-1" :patch {:escrow-id "esc-1"}})]
        (is (true? (:hard? v)))
        (is (some #{:payment-short :payment-does-not-cover-plan} (rules v))
            (pr-str (rules v)))))))

(deftest the-mock-advisors-refund-proposal-passes-its-own-governor
  (let [st (with-capture (db) "basket-1")
        v (check st :propose-refund {:basket-id "basket-1"
                                     :patch {:amount-minor 4550 :reason "未着"}})]
    (is (false? (:hard? v)) (pr-str (:violations v)))
    (is (true? (:high-stakes? v)))))
