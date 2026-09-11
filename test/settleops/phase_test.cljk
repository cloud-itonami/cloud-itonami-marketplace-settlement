(ns settleops.phase-test
  "The rollout gate. The test that matters most is the LAST one: the three
  money-deciding ops must be absent from every phase's `:auto` set,
  including phase 3, forever."
  (:require [clojure.test :refer [deftest is testing]]
            [settleops.governor :as governor]
            [settleops.phase :as phase]))

(defn- gate [ph op disposition]
  (phase/gate ph {:op op} disposition))

(deftest a-governor-hold-survives-every-phase
  (testing "compliance wins -- no phase can promote a HOLD into a write"
    (doseq [ph (keys phase/phases)
            op phase/write-ops]
      (is (= {:disposition :hold :reason nil} (gate ph op :hold))
          (str "phase " ph " / " op)))))

(deftest phase-0-writes-nothing
  (doseq [op phase/write-ops]
    (is (= {:disposition :hold :reason :phase-disabled} (gate 0 op :commit))
        (str op " must not write in read-only"))))

(deftest an-enabled-op-that-is-not-auto-eligible-still-asks-a-human
  (testing "phase 1 may compute a plan, but a clean governor is not consent"
    (is (= {:disposition :escalate :reason :phase-approval}
           (gate 1 :plan-settlement :commit))))
  (testing "phase 2 adds escrow opening on the same terms"
    (is (= {:disposition :escalate :reason :phase-approval}
           (gate 2 :open-escrow :commit)))
    (is (= {:disposition :hold :reason :phase-disabled}
           (gate 1 :open-escrow :commit))
        "escrow opening is not yet enabled one phase earlier")))

(deftest phase-3-auto-commits-only-computations-and-records
  (is (= {:disposition :commit :reason nil} (gate 3 :plan-settlement :commit)))
  (is (= {:disposition :commit :reason nil} (gate 3 :open-escrow :commit)))
  (testing "an escalate stays an escalate -- the phase never upgrades it"
    (is (= {:disposition :escalate :reason nil} (gate 3 :plan-settlement :escalate)))))

(deftest an-unknown-phase-falls-back-to-the-default-not-to-permissive
  (let [unknown (gate 99 :plan-settlement :commit)]
    (is (= (gate phase/default-phase :plan-settlement :commit) unknown))
    (testing "and the fallback still refuses the money-deciding ops"
      (is (= :escalate (:disposition (gate 99 :propose-release :commit)))))))

(deftest verdict-to-disposition-is-hold-then-escalate-then-commit
  (is (= :hold (phase/verdict->disposition {:hard? true :escalate? true})))
  (is (= :escalate (phase/verdict->disposition {:escalate? true})))
  (is (= :commit (phase/verdict->disposition {})))
  (testing "a hard verdict wins even when nothing else is set"
    (is (= :hold (phase/verdict->disposition {:hard? true})))))

(deftest write-ops-is-the-governors-allowlist-not-a-second-copy
  (is (= governor/allowed-ops phase/write-ops)
      "two lists of writable ops would drift; this is one list, referenced"))

(deftest the-three-money-deciding-ops-are-never-auto-in-any-phase
  (let [money-ops #{:bind-payout-destination :propose-release :flag-settlement-concern}]
    (doseq [[ph {:keys [auto]}] phase/phases
            op money-ops]
      (is (not (contains? auto op))
          (str "phase " ph " must never auto-commit " op)))
    (testing "a structural fact, so even the highest phase asks a human"
      (doseq [op money-ops]
        (is (= :escalate (:disposition (gate 3 op :commit)))
            (str op " at phase 3"))))
    (testing "the ops that MAY auto-commit are exactly the two that move nothing"
      (is (= #{:plan-settlement :open-escrow} (:auto (get phase/phases 3)))))))

(deftest a-refund-is-writable-only-where-a-release-is
  (testing "both are irreversible outward movements; a phase that could
            refund but not release would be a strange half-state"
    (is (= {:disposition :hold :reason :phase-disabled} (gate 1 :propose-refund :commit)))
    (is (= {:disposition :hold :reason :phase-disabled} (gate 2 :propose-refund :commit)))
    (is (= {:disposition :escalate :reason :phase-approval}
           (gate 3 :propose-refund :commit))))
  (testing "and it is in no phase's :auto set"
    (doseq [[ph {:keys [auto]}] phase/phases]
      (is (not (contains? auto :propose-refund)) (str "phase " ph)))))
