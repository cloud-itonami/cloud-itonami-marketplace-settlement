(ns settleops.operation-graph-test
  "Integration tests for `settleops.operation/build` -- proves the REAL
  compiled `langgraph.graph` StateGraph runs end-to-end.

  The headline tests are the two money moments:
  `release-can-only-be-authorised-by-a-human` and
  `binding-a-payout-destination-can-only-be-authorised-by-a-human`."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [marketplace.settlement :as settle]
            [settleops.operation :as operation]
            [settleops.store :as store]))

(def now "2026-06-15T00:00:00Z")
(def ^:private op-context {:actor-id "settle-01" :phase 3 :now now})

(defn- exec
  ([actor tid request] (exec actor tid request op-context))
  ([actor tid request context]
   (g/run* actor {:request request :context context} {:thread-id tid})))

(defn- with-escrow [st basket-id & {:keys [state release-after]
                                    :or {state :held release-after "2026-06-08T00:00:00Z"}}]
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

(deftest phase-gates-are-wired-into-the-compiled-graph
  (testing "phase 1 has not enabled escrow yet"
    (let [st (store/seed-db)
          actor (operation/build st)
          result (exec actor "t-phase1"
                       {:op :open-escrow :basket-id "basket-1"
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
