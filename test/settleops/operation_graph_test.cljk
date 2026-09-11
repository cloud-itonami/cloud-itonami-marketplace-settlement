(ns settleops.operation-graph-test
  "Integration tests for `settleops.operation/build` -- proves the REAL
  compiled `langgraph.graph` StateGraph runs end-to-end.

  The headline tests are the two money moments:
  `release-can-only-be-authorised-by-a-human` and
  `binding-a-payout-destination-can-only-be-authorised-by-a-human`."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [marketplace.acceptance :as accept]
            [marketplace.settlement :as settle]
            [settleops.operation :as operation]
            [settleops.store :as store]))

(def now "2026-06-15T00:00:00Z")
(def ^:private op-context {:actor-id "settle-01" :phase 3 :now now})

(defn- exec
  ([actor tid request] (exec actor tid request op-context))
  ([actor tid request context]
   (g/run* actor {:request request :context context} {:thread-id tid})))

(defn- capture-patch
  "A コード決済 capture for `basket`, shaped as the host hands it over
  after a PSP webhook. `amount` defaults to what the plan says is owed."
  [st basket & {:keys [amount source]
                :or {source :webhook}}]
  (let [expected (or amount (:plan/buyer-charge-minor (store/plan-for st basket)))]
    {:request (accept/payment-request
               {:order basket :rail :code-payment :mode :mpm-dynamic :psp "psp.test"
                :expected-minor expected :currency "JPY"
                :expires-at "2026-06-02T00:10:00Z" :reference "psp-ref-1"})
     :attestation (accept/psp-attestation
                   {:psp "psp.test" :transaction-id (str "psp-tx-" basket)
                    :amount-minor expected :currency "JPY"
                    :attested-at "2026-06-02T00:05:00Z" :source source})}))

(defn- with-capture
  "Record that the buyer paid, the way an approved `:record-payment-capture`
  run does. Every release test needs this now: without it the governor's
  funds gate refuses before any human is asked."
  [st basket]
  (store/commit-record! st (assoc {:op :record-payment-capture}
                                  :value (assoc (capture-patch st basket)
                                                :order basket)))
  st)

(defn- with-escrow [st basket-id & {:keys [state release-after paid?]
                                    :or {state :held release-after "2026-06-08T00:00:00Z"
                                         paid? true}}]
  (when paid? (with-capture st basket-id))
  (store/commit-record!
   st {:op :open-escrow
       :value {:escrow (-> (settle/escrow {:id "esc-1" :plan (store/plan-for st basket-id)
                                           :basket basket-id
                                           :opened-at "2026-06-01T00:00:00Z"
                                           :release-after release-after})
                           (assoc :escrow/state state))}})
  st)

(deftest planning-auto-commits-in-phase-3
  (testing "computing a plan is a COMPUTATION — it may run unattended
            because it moves nothing"
    (let [st (store/seed-db)
          actor (operation/build st)
          result (exec actor "t-plan" {:op :plan-settlement :basket-id "basket-1"})]
      (is (= :done (:status result)))
      (is (= :commit (:disposition (:state result))))
      (let [p (store/plan st "basket-1")]
        (is (some? p))
        (is (true? (:plan/conserved? p)))
        (is (false? (:plan/custodial? p)))))))

(deftest a-basket-with-an-unverified-destination-hard-holds
  (let [st (store/seed-db)
        actor (operation/build st)
        result (exec actor "t-gamma" {:op :plan-settlement :basket-id "basket-2"})]
    (is (= :done (:status result)) "not :interrupted — no human is asked")
    (is (= :hold (:disposition (:state result))))
    (is (some #{:payout-destination-unverified}
              (map :rule (:violations (first (store/ledger st))))))
    (is (nil? (store/plan st "basket-2")) "no plan was recorded")))

(deftest binding-a-payout-destination-can-only-be-authorised-by-a-human
  (testing "deciding WHERE a seller's money goes is never automatic"
    (let [st (store/seed-db)
          actor (operation/build st)
          held (exec actor "t-bind"
                     {:op :bind-payout-destination
                      :patch {:seller "merchant.delta" :rail :x402
                              :address "0xdddd" :verified? true}})]
      (is (= :interrupted (:status held)))
      (is (nil? (store/payout-destination st "merchant.delta")))
      (let [approved (g/run* actor {:approval {:status :approved :by "treasury-01"}}
                             {:thread-id "t-bind" :resume? true})]
        (is (= :commit (:disposition (:state approved))))
        (is (some? (store/payout-destination st "merchant.delta")))
        (is (= "treasury-01" (:approved-by (:payload (first (store/settlement-log st))))))))))

(deftest release-can-only-be-authorised-by-a-human
  (testing "authorising a release is the moment money leaves — the escrow
            stays :held until a named human resumes the run"
    (let [st (with-escrow (store/seed-db) "basket-1")
          actor (operation/build st)
          held (exec actor "t-release"
                     {:op :propose-release :basket-id "basket-1"
                      :patch {:escrow-id "esc-1"}})]
      (is (= :interrupted (:status held)))
      (is (= :held (:escrow/state (store/escrow st "esc-1"))))
      (let [approved (g/run* actor {:approval {:status :approved :by "treasury-01"}}
                             {:thread-id "t-release" :resume? true})]
        (is (= :commit (:disposition (:state approved))))
        (is (= :released (:escrow/state (store/escrow st "esc-1"))))
        (is (= "treasury-01" (:escrow/released-by (store/escrow st "esc-1")))
            "the escrow records WHO authorised it")))))

(deftest a-rejected-release-leaves-the-escrow-held
  (let [st (with-escrow (store/seed-db) "basket-1")
        actor (operation/build st)
        _held (exec actor "t-rel-rej"
                    {:op :propose-release :basket-id "basket-1"
                     :patch {:escrow-id "esc-1"}})
        rejected (g/run* actor {:approval {:status :rejected :by "treasury-01"}}
                         {:thread-id "t-rel-rej" :resume? true})]
    (is (= :hold (:disposition (:state rejected))))
    (is (= :held (:escrow/state (store/escrow st "esc-1"))))))

(deftest releasing-an-undelivered-order-never-reaches-a-human
  (testing "basket-3 is not delivered — the graph routes straight to :hold
            rather than offering it for approval"
    (let [st (with-escrow (store/seed-db) "basket-3")
          actor (operation/build st)
          result (exec actor "t-undelivered"
                       {:op :propose-release :basket-id "basket-3"
                        :patch {:escrow-id "esc-1"}})]
      (is (= :done (:status result)) "not :interrupted")
      (is (= :hold (:disposition (:state result))))
      (is (= :held (:escrow/state (store/escrow st "esc-1"))))
      (is (some #{:escrow-not-releasable}
                (map :rule (:violations (first (store/ledger st)))))))))

(deftest releasing-a-disputed-escrow-never-reaches-a-human
  (testing "no actor resolves a payment dispute, and no human approval
            inside THIS actor can release a disputed escrow —
            marketplace.settlement/resolve-dispute is the only door"
    (let [st (with-escrow (store/seed-db) "basket-1" :state :disputed)
          actor (operation/build st)
          result (exec actor "t-disputed"
                       {:op :propose-release :basket-id "basket-1"
                        :patch {:escrow-id "esc-1"}})]
      (is (= :done (:status result)))
      (is (= :hold (:disposition (:state result))))
      (is (= :disputed (:escrow/state (store/escrow st "esc-1"))))
      (is (some #{:escrow-disputed}
                (map :rule (:violations (first (store/ledger st)))))))))

(deftest settlement-concern-escalates-and-threads-the-real-proposal
  (let [distinctive (str "TEST-CONCERN-" (rand-int 1000000000))
        st (store/seed-db)
        actor (operation/build st)
        held (exec actor "t-concern"
                   {:op :flag-settlement-concern :basket-id "basket-1"
                    :patch {:concern distinctive}})]
    (is (= :interrupted (:status held)))
    (let [approved (g/run* actor {:approval {:status :approved :by "treasury-01"}}
                           {:thread-id "t-concern" :resume? true})]
      (is (= :done (:status approved)))
      (is (= distinctive (:concern (:payload (first (store/settlement-log st)))))))))

;; ───────────────────────── the funds gate ─────────────────────────

(deftest an-unpaid-custodial-order-is-refused-before-a-human-is-asked
  (testing "basket-1 pays merchant.beta by stripe, so the buyer's money
            passes through the operator — and nobody recorded that it
            arrived. Releasing here would pay sellers out of the
            operator's own money."
    (let [st (with-escrow (store/seed-db) "basket-1" :paid? false)
          actor (operation/build st)
          result (exec actor "t-unpaid"
                       {:op :propose-release :basket-id "basket-1"
                        :patch {:escrow-id "esc-1"}})]
      (is (= :done (:status result)) "not :interrupted — no human is offered this")
      (is (= :hold (:disposition (:state result))))
      (is (some #{:payment-not-recorded}
                (map :rule (:violations (first (store/ledger st))))))
      (is (= :held (:escrow/state (store/escrow st "esc-1"))))
      (is (nil? (store/acceptance st "basket-1"))
          "and an absent acceptance stays absent — nothing is defaulted in"))))

(deftest opening-an-escrow-on-an-unpaid-custodial-order-is-refused-too
  (testing "the gate is not only at release: an escrow over money that
            never arrived is a record of something that did not happen"
    (let [st (store/seed-db)
          actor (operation/build st)
          result (exec actor "t-unpaid-escrow"
                       {:op :open-escrow :basket-id "basket-1"
                        :patch {:opened-at now :release-after now}})]
      (is (= :hold (:disposition (:state result))))
      (is (some #{:payment-not-recorded}
                (map :rule (:violations (first (store/ledger st))))))
      (is (empty? (store/all-escrows st))))))

(deftest an-x402-only-basket-needs-no-acceptance-record
  (testing "on the direct-split rail the buyer paid each seller's treasury
            directly, so there is no operator-side receipt to demand.
            Demanding one would break the rail already in production."
    (let [st (store/with-delivery (store/seed-db) "basket-3" true)
          _  (with-escrow st "basket-3" :paid? false)
          actor (operation/build st)
          held (exec actor "t-x402" {:op :propose-release :basket-id "basket-3"
                                     :patch {:escrow-id "esc-1"}})]
      (is (nil? (store/acceptance st "basket-3")))
      (is (= :interrupted (:status held))
          "it reaches a human, which it could not do if the gate had fired")
      (let [approved (g/run* actor {:approval {:status :approved :by "treasury-01"}}
                             {:thread-id "t-x402" :resume? true})]
        (is (= :commit (:disposition (:state approved))))
        (is (= :released (:escrow/state (store/escrow st "esc-1"))))))))

(deftest recording-a-capture-is-never-automatic-and-is-derived-not-taken
  (testing "this op writes the evidence the funds gate reads, so an actor
            that could auto-commit it would be unlocking its own gate"
    (let [st (store/seed-db)
          actor (operation/build st)
          held (exec actor "t-capture"
                     {:op :record-payment-capture :basket-id "basket-1"
                      :patch (capture-patch st "basket-1")})]
      (is (= :interrupted (:status held)) "phase 3 does not auto-commit it")
      (is (nil? (store/acceptance st "basket-1")))
      (let [approved (g/run* actor {:approval {:status :approved :by "treasury-01"}}
                             {:thread-id "t-capture" :resume? true})
            a (store/acceptance st "basket-1")]
        (is (= :done (:status approved)))
        (testing "what is stored is what acceptance/capture derived"
          (is (= :captured (:accept/state a)))
          (is (= 4550 (:accept/captured-minor a)))
          (is (= "psp-tx-basket-1" (:accept/psp-transaction a)))
          (is (= :webhook (:accept/attested-by a)))
          (is (true? (accept/releasable-to-settlement? a))))))))

(deftest a-buyers-completion-screen-never-becomes-a-recorded-capture
  (testing "the standard attack on コード決済 is a faked 決済完了画面, and it
            must not reach a human as an approvable proposal"
    (let [st (store/seed-db)
          actor (operation/build st)
          result (exec actor "t-buyer-screen"
                       {:op :record-payment-capture :basket-id "basket-1"
                        :patch (capture-patch st "basket-1" :source :buyer-screen)})]
      (is (= :done (:status result)) "not :interrupted")
      (is (= :hold (:disposition (:state result))))
      (is (some #{:buyer-presented-evidence}
                (map :rule (:violations (first (store/ledger st))))))
      (is (nil? (store/acceptance st "basket-1"))))))

(deftest a-short-payment-blocks-the-release-it-would-have-funded
  (testing "the buyer typed less than was owed; paying sellers in full
            would pay the difference out of the operator's own money"
    (let [st (store/seed-db)]
      (store/commit-record! st {:op :record-payment-capture
                                :value (assoc (capture-patch st "basket-1" :amount 1000)
                                              :order "basket-1")})
      (with-escrow st "basket-1" :paid? false)
      (let [actor (operation/build st)
            result (exec actor "t-short"
                         {:op :propose-release :basket-id "basket-1"
                          :patch {:escrow-id "esc-1"}})
            rules (map :rule (:violations (first (store/ledger st))))]
        (is (= :hold (:disposition (:state result))))
        (is (or (some #{:payment-does-not-cover-plan} rules)
                (some #{:payment-short} rules))
            (str "expected a payment refusal, got " (vec rules)))
        (is (= :held (:escrow/state (store/escrow st "esc-1"))))))))

(deftest phase-gates-are-wired-into-the-compiled-graph
  (testing "phase 1 has not enabled escrow yet"
    ;; basket-3 is x402-only, so the funds gate does not apply and the
    ;; phase gate is the only thing left to refuse — which is what this
    ;; test is about.
    (let [st (store/seed-db)
          actor (operation/build st)
          result (exec actor "t-phase1"
                       {:op :open-escrow :basket-id "basket-3"
                        :patch {:opened-at now :release-after now}}
                       (assoc op-context :phase 1))]
      (is (= :hold (:disposition (:state result))))
      (is (= :phase-disabled (:phase-reason (first (store/ledger st)))))))
  (testing "phase 0 writes nothing"
    (let [st (store/seed-db)
          actor (operation/build st)
          result (exec actor "t-phase0"
                       {:op :plan-settlement :basket-id "basket-1"}
                       (assoc op-context :phase 0))]
      (is (= :hold (:disposition (:state result))))
      (is (empty? (store/settlement-log st))))))

;; ───────────────────────── refunds ─────────────────────────

(deftest a-refund-needs-a-human-and-is-booked-against-the-capture
  (let [st (with-capture (store/seed-db) "basket-1")
        actor (operation/build st)
        held (exec actor "t-refund" {:op :propose-refund :basket-id "basket-1"
                                     :patch {:amount-minor 550 :reason "一部返品"}})]
    (is (= :interrupted (:status held)) "money going back is never automatic")
    (is (empty? (store/refunds st "basket-1")))
    (let [approved (g/run* actor {:approval {:status :approved :by "treasury-01"}}
                           {:thread-id "t-refund" :resume? true})
          a (store/acceptance st "basket-1")
          [r] (store/refunds st "basket-1")]
      (is (= :done (:status approved)))
      (is (= "treasury-01" (:refund/requested-by r)) "attributed to the approver")
      (is (= 550 (accept/refunded-minor a)))
      (is (= 4000 (accept/net-captured-minor a)))
      (testing "and the release it would have funded is now short"
        (is (false? (accept/releasable-to-settlement? a)))))))

(deftest a-rejected-refund-books-nothing
  (let [st (with-capture (store/seed-db) "basket-1")
        actor (operation/build st)
        _ (exec actor "t-refund-rej" {:op :propose-refund :basket-id "basket-1"
                                      :patch {:amount-minor 550}})
        rejected (g/run* actor {:approval {:status :rejected :by "treasury-01"}}
                         {:thread-id "t-refund-rej" :resume? true})]
    (is (= :hold (:disposition (:state rejected))))
    (is (empty? (store/refunds st "basket-1")))
    (is (= 4550 (accept/net-captured-minor (store/acceptance st "basket-1"))))))

(deftest a-refund-after-a-release-never-reaches-a-human
  (testing "that money went to sellers; paying the buyer too is paying twice"
    (let [st (with-escrow (store/seed-db) "basket-1")
          actor (operation/build st)
          _ (exec actor "t-rel" {:op :propose-release :basket-id "basket-1"
                                 :patch {:escrow-id "esc-1"}})
          _ (g/run* actor {:approval {:status :approved :by "treasury-01"}}
                    {:thread-id "t-rel" :resume? true})
          result (exec actor "t-refund-after" {:op :propose-refund :basket-id "basket-1"
                                               :patch {:amount-minor 100}})]
      (is (= :released (:escrow/state (store/escrow st "esc-1"))))
      (is (= :done (:status result)) "not :interrupted")
      (is (= :hold (:disposition (:state result))))
      ;; The release committed first, so the hold is not the ledger's head.
      (is (some #{:refund-after-release}
                (mapcat #(map :rule (:violations %))
                        (filter #(= :governor-hold (:t %)) (store/ledger st)))))
      (is (empty? (store/refunds st "basket-1"))))))
