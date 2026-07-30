(ns settleops.phase
  "Phase 0->3 staged rollout for the marketplace settlement actor.

    Phase 0  read-only          -- no writes, still governor-gated.
    Phase 1  assisted-planning  -- settlement plans may be computed and
                                   recorded, every write needs human
                                   approval.
    Phase 2  assisted-escrow    -- adds escrow opening, still
                                   approval-gated.
    Phase 3  supervised auto    -- governor-clean, high-confidence
                                   `:plan-settlement` and `:open-escrow`
                                   may auto-commit.

  `:bind-payout-destination`, `:propose-release`,
  `:flag-settlement-concern` and `:record-payment-capture` are
  deliberately ABSENT from every phase's `:auto` set, INCLUDING phase 3 --
  a permanent structural fact, not a rollout milestone still to come.

  The three that matter are the two that decide about money and the one
  that decides what counts as proof it arrived:

    - `:bind-payout-destination` decides WHERE a seller's money goes.
      Getting it wrong sends funds to an address nobody controls, or to
      an attacker who social-engineered the change. It is the
      money-equivalent of issuing an identity, and is human-gated for
      the same reason.
    - `:propose-release` decides WHEN money leaves. Computing a plan is
      reversible (recompute it); releasing is not.
    - `:record-payment-capture` writes the evidence the governor's funds
      gate reads. It moves nothing, which makes it look auto-committable,
      and that appearance is the trap: an actor that can write its own
      payment evidence can open its own escrow and authorise its own
      release. A gate whose input the gated party controls is not a gate.

  Everything auto-committable here is a COMPUTATION or a RECORD that
  nothing else is gated on. Nothing auto-committable moves value. `settleops.governor`'s own
  `always-escalate-ops` enforces the same invariant independently --
  two layers, not one, agree on this."
  (:require [settleops.governor :as governor]))

(def read-ops #{})
(def write-ops governor/allowed-ops)

;; NOTE the invariant: the four ops above are members of `write-ops`
;; (governor-gated like any write) but are NEVER members of any phase's
;; `:auto` set below. Do not add them there.
;;
;; `:record-payment-capture` is writable from phase 1 alongside planning,
;; because from phase 2 on an escrow CANNOT open on a custodial flow until
;; a capture is recorded (governor check 7) -- enabling the gated op
;; without the op that satisfies the gate would ship a phase that can only
;; refuse.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed
  to auto-commit when governor-clean>}."
  {0 {:label "read-only"         :writes #{}                    :auto #{}}
   1 {:label "assisted-planning" :writes #{:plan-settlement :record-payment-capture}
      :auto #{}}
   2 {:label "assisted-escrow"   :writes #{:plan-settlement :record-payment-capture
                                           :open-escrow}
      :auto #{}}
   3 {:label "supervised-auto"   :writes write-ops
      :auto #{:plan-settlement :open-escrow}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE
    (:phase-approval), even if the governor was clean."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a SettlementGovernor verdict to a base disposition before the
  phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
