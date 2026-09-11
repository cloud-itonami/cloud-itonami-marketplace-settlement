(ns settleops.store-test
  "The SSoT. The sharpest test here is
  `a-release-is-attributed-from-the-payload-not-the-proposal`: reading the
  approver from `:value` would silently record every release as
  unattributed, which is exactly what the audit ledger exists to prevent."
  (:require [clojure.test :refer [deftest is testing]]
            [marketplace.acceptance :as accept]
            [marketplace.settlement :as settle]
            [settleops.store :as store]))

(defn- escrow-for [st basket]
  (settle/escrow {:id (str "esc-" basket) :plan (store/plan-for st basket)
                  :basket basket :opened-at "2026-06-01T00:00:00Z"
                  :release-after "2026-06-08T00:00:00Z"}))

;; ───────────────────────── fixtures and derived views ─────────────────────────

(deftest the-demo-fixture-includes-the-case-that-must-be-refused
  (let [st (store/seed-db)]
    (is (true? (:payout/verified? (store/payout-destination st "merchant.alpha"))))
    (is (false? (:payout/verified? (store/payout-destination st "merchant.gamma")))
        "gamma exists so a plan containing them is refused before approval")
    (is (nil? (store/payout-destination st "merchant.nobody")))
    (is (true? (store/delivered? st "basket-1")))
    (is (false? (store/delivered? st "basket-3")))
    (is (false? (store/delivered? st "basket-unknown"))
        "an unknown basket is not delivered -- absence is not confirmation")))

(deftest plan-for-uses-this-stores-published-schedule-and-operator
  (let [st (store/seed-db)
        p (store/plan-for st "basket-1")]
    (is (= "merchant.marketplace-operator" (:plan/operator p)))
    (is (= 50 (:plan/fixed-fee-minor p)))
    (is (= 4500 (:plan/gross-minor p))
        "1200 + 3300 -- :line/amount-minor is ALREADY the extended amount, so
         qty 3 does not multiply it here (the catalog owns prices)")
    (is (true? (:plan/conserved? p)))
    (testing "a commission rate this actor chose for itself would be a conflict"
      (is (= 1000 (:fee/commission-bps (store/fee-schedule st)))))
    (testing "an unknown basket yields nil, not an empty plan"
      (is (nil? (store/plan-for st "basket-unknown"))))))

(deftest destinations-for-reads-the-store-so-verified-is-ground-truth
  (let [st (store/seed-db)
        ds (store/destinations-for st (store/plan-for st "basket-2"))]
    (is (= #{"merchant.alpha" "merchant.gamma"} (set (keys ds))))
    (is (false? (:payout/verified? (ds "merchant.gamma"))))
    (testing "a seller with no record is absent rather than defaulted"
      (let [bare (store/mem-store {:baskets {"b" [(settle/basket-line
                                                   {:seller "merchant.nobody" :offer "o"
                                                    :amount-minor 100 :qty 1})]}})]
        (is (= {} (store/destinations-for bare (store/plan-for bare "b"))))))))

;; ───────────────────────── commit-record! ─────────────────────────

(deftest committing-a-destination-updates-the-directory
  (let [st (store/seed-db)
        d (settle/payout-destination {:seller "merchant.delta" :rail :stripe
                                      :address "acct_delta" :verified? true})]
    (store/commit-record! st {:op :bind-payout-destination :value {:destination d}})
    (is (= d (store/payout-destination st "merchant.delta")))
    (is (= 4 (count (store/all-payout-destinations st))))))

(deftest committing-a-plan-and-an-escrow-records-them
  (let [st (store/seed-db)
        p (store/plan-for st "basket-1")
        e (escrow-for st "basket-1")]
    (is (nil? (store/plan st "basket-1")) "computed is not committed")
    (store/commit-record! st {:op :plan-settlement :value {:basket-id "basket-1" :plan p}})
    (is (= p (store/plan st "basket-1")))
    (store/commit-record! st {:op :open-escrow :value {:escrow e}})
    (is (= :held (:escrow/state (store/escrow st "esc-basket-1"))))
    (is (= 1 (count (store/all-escrows st))))))

(deftest a-release-is-attributed-from-the-payload-not-the-proposal
  (let [st (store/seed-db)]
    (store/commit-record! st {:op :open-escrow :value {:escrow (escrow-for st "basket-1")}})
    (testing "the approver stamped by :request-approval lands on :payload"
      (store/commit-record! st {:op :propose-release
                                :value {:escrow-id "esc-basket-1"}
                                :payload {:escrow-id "esc-basket-1"
                                          :approved-by "treasury-01"}})
      (let [e (store/escrow st "esc-basket-1")]
        (is (= :released (:escrow/state e)))
        (is (= "treasury-01" (:escrow/released-by e)))))
    (testing "an approver present only on the advisor's :value is NOT read"
      (let [st2 (store/seed-db)]
        (store/commit-record! st2 {:op :open-escrow
                                   :value {:escrow (escrow-for st2 "basket-1")}})
        (store/commit-record! st2 {:op :propose-release
                                   :value {:escrow-id "esc-basket-1"
                                           :approved-by "advisor-said-so"}})
        (is (nil? (:escrow/released-by (store/escrow st2 "esc-basket-1")))
            "an unattributed release must stay unattributed, not borrow a name")))))

(deftest releasing-an-escrow-the-store-does-not-have-creates-nothing
  (let [st (store/seed-db)]
    (store/commit-record! st {:op :propose-release :value {:escrow-id "esc-ghost"}
                              :payload {:approved-by "treasury-01"}})
    (is (nil? (store/escrow st "esc-ghost"))
        "a release must not conjure the escrow it claims to release")))

(deftest an-unrecognised-op-changes-no-directory-but-is-still-logged
  (let [st (store/seed-db)
        before (select-keys (store/demo-data) [:destinations :baskets])
        r (store/commit-record! st {:op :something-else :value {:destination "x"}})]
    (is (= {:op :something-else :value {:destination "x"}} r))
    (is (= (:destinations before)
           (into {} (map (juxt :payout/seller identity) (store/all-payout-destinations st)))))
    (is (= 1 (count (store/settlement-log st))) "the record itself is not dropped")))

;; ───────────────────────── ledger and backend ─────────────────────────

(deftest the-ledger-only-grows
  (let [st (store/seed-db)]
    (is (= [] (store/ledger st)))
    (store/append-ledger! st {:t :committed :op :plan-settlement})
    (store/append-ledger! st {:t :governor-hold :op :propose-release})
    (is (= [:committed :governor-hold] (mapv :t (store/ledger st))))
    (store/append-ledger! st {:t :committed :op :open-escrow})
    (is (= 3 (count (store/ledger st))) "earlier facts are never rewritten")))

(deftest the-memory-backend-says-it-is-not-durable
  (is (false? (store/durable? (store/seed-db)))
      "a test backend that claimed durability would hide lost writes"))

(deftest delivery-is-the-fulfilment-sides-fact-and-can-be-mirrored
  (let [st (store/seed-db)]
    (is (false? (store/delivered? st "basket-3")))
    (store/with-delivery st "basket-3" true)
    (is (true? (store/delivered? st "basket-3")))
    (store/with-delivery st "basket-3" nil)
    (is (false? (store/delivered? st "basket-3")) "nil is not delivered")))

(deftest a-bare-mem-store-starts-empty-with-a-usable-schedule
  (let [st (store/mem-store {})]
    (is (= [] (store/all-payout-destinations st)))
    (is (= {} (store/all-baskets st)))
    (is (= [] (store/ledger st)))
    (is (= 1000 (:fee/commission-bps (store/fee-schedule st))))
    (is (some? (store/operator st)))))

;; ───────────────────────── refunds ─────────────────────────

(defn- capture! [st basket]
  (let [expected (:plan/buyer-charge-minor (store/plan-for st basket))]
    (store/commit-record!
     st {:op :record-payment-capture
         :value {:order basket
                 :request (accept/payment-request
                           {:order basket :rail :code-payment :mode :mpm-dynamic
                            :psp "psp.test" :expected-minor expected :currency "JPY"
                            :expires-at "2026-06-02T00:10:00Z"})
                 :attestation (accept/psp-attestation
                               {:psp "psp.test" :transaction-id "psp-tx-1"
                                :amount-minor expected :currency "JPY"
                                :attested-at "2026-06-02T00:05:00Z" :source :webhook})}})
    st))

(defn- refund! [st basket amount & {:keys [by] :or {by "treasury-01"}}]
  (store/commit-record! st {:op :propose-refund
                            :value {:order basket :amount-minor amount
                                    :reason "未着" :requested-at "2026-08-01T00:00:00Z"}
                            :payload (when by {:approved-by by})})
  st)

(deftest a-refund-is-derived-from-the-stored-capture-and-booked-against-it
  (let [st (capture! (store/seed-db) "basket-1")]
    (refund! st "basket-1" 550)
    (let [a (store/acceptance st "basket-1")
          [r] (store/refunds st "basket-1")]
      (testing "the instruction is derived here, not carried by the proposal"
        (is (= :code-payment (:refund/rail r)))
        (is (= "psp-tx-1" (:refund/original-transaction r)))
        (is (= :psp-original-transaction (:refund/via r)))
        (is (= 550 (:refund/amount-minor r)))
        (is (true? (:refund/partial? r))))
      (testing "the approver's name comes from :payload, like a release"
        (is (= "treasury-01" (:refund/requested-by r))))
      (testing "and it is booked so the funds gate can see it"
        (is (= 550 (accept/refunded-minor a)))
        (is (= 4000 (accept/net-captured-minor a)))
        (is (= :captured (:accept/state a)))
        (is (false? (accept/releasable-to-settlement? a)))))))

(deftest a-full-refund-takes-the-acceptance-out-of-captured
  (let [st (capture! (store/seed-db) "basket-1")]
    (refund! st "basket-1" 4550)
    (let [a (store/acceptance st "basket-1")]
      (is (= :refunded (:accept/state a)))
      (is (= 0 (accept/net-captured-minor a)))
      (is (= :missing (:status (accept/settlement-status a)))))))

(deftest an-unattributed-refund-is-not-booked-at-all
  (testing "no approver means no instruction — money must not go back on
            nobody's authority"
    (let [st (capture! (store/seed-db) "basket-1")]
      (refund! st "basket-1" 550 :by nil)
      (is (empty? (store/refunds st "basket-1")))
      (is (= 0 (accept/refunded-minor (store/acceptance st "basket-1"))))
      (testing "and the record is still in the log, so the attempt is visible"
        (is (= 2 (count (store/settlement-log st))))))))

(deftest a-refund-beyond-what-is-held-books-nothing
  (let [st (capture! (store/seed-db) "basket-1")]
    (refund! st "basket-1" 4551)
    (is (empty? (store/refunds st "basket-1")))
    (is (= 4550 (accept/net-captured-minor (store/acceptance st "basket-1"))))
    (testing "and two half refunds are fine, a third is not"
      (refund! st "basket-1" 2275)
      (refund! st "basket-1" 2275)
      (is (= 2 (count (store/refunds st "basket-1"))))
      (refund! st "basket-1" 1)
      (is (= 2 (count (store/refunds st "basket-1")))))))

(deftest a-refund-for-an-order-with-no-capture-books-nothing
  (let [st (store/seed-db)]
    (refund! st "basket-1" 100)
    (is (empty? (store/refunds st "basket-1")))
    (is (nil? (store/acceptance st "basket-1")))
    (is (= [] (store/all-refunds st)))))

(defn- topup! [st basket amount & {:keys [txid tops-up] :or {txid "psp-tx-2"}}]
  (let [current (store/acceptance st basket)
        t (accept/top-up-request current {:expires-at "2026-06-03T00:10:00Z"})]
    (store/commit-record!
     st {:op :record-payment-capture
         :value {:order basket
                 :request (cond-> t tops-up (assoc :accept/tops-up tops-up))
                 :attestation (accept/psp-attestation
                               {:psp "psp.test" :transaction-id txid
                                :amount-minor amount :currency "JPY"
                                :attested-at "2026-06-03T00:05:00Z" :source :webhook})}})
    st))

(defn- short-capture! [st basket amount]
  (store/commit-record!
   st {:op :record-payment-capture
       :value {:order basket
               :request (accept/payment-request
                         {:order basket :rail :code-payment :mode :mpm-static
                          :psp "psp.test"
                          :expected-minor (:plan/buyer-charge-minor (store/plan-for st basket))
                          :currency "JPY"})
               :attestation (accept/psp-attestation
                             {:psp "psp.test" :transaction-id "psp-tx-1"
                              :amount-minor amount :currency "JPY"
                              :attested-at "2026-06-02T00:05:00Z" :source :webhook})}})
  st)

(deftest a-second-capture-tops-the-first-up-instead-of-replacing-it
  (testing "assoc-in alone would have erased the money the buyer already sent"
    (let [st (short-capture! (store/seed-db) "basket-1" 550)]
      (is (= 550 (:accept/captured-minor (store/acceptance st "basket-1"))))
      (is (= 4000 (accept/shortfall-minor (store/acceptance st "basket-1"))))
      (topup! st "basket-1" 4000)
      (let [a (store/acceptance st "basket-1")]
        (is (= 4550 (:accept/captured-minor a)) "the total, not the latest")
        (is (= :settled (:status (accept/settlement-status a))))
        (is (= ["psp-tx-1" "psp-tx-2"] (:accept/psp-transactions a)))))))

(deftest a-second-capture-that-is-not-a-top-up-books-nothing
  (let [st (short-capture! (store/seed-db) "basket-1" 550)]
    (topup! st "basket-1" 4000 :tops-up "psp-tx-somewhere-else")
    (is (= 550 (:accept/captured-minor (store/acceptance st "basket-1")))
        "the first capture survives untouched")
    (testing "and an amount that is not the shortfall is refused by the library"
      (topup! st "basket-1" 1 :txid "psp-tx-3")
      (is (= 550 (:accept/captured-minor (store/acceptance st "basket-1")))))))
