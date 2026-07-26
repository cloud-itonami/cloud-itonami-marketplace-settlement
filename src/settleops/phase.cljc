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

  `:bind-payout-destination`, `:propose-release` and
  `:flag-settlement-concern` are deliberately ABSENT from every phase's
  `:auto` set, INCLUDING phase 3 -- a permanent structural fact, not a
  rollout milestone still to come.

  The two that matter are the two that decide about money:

    - `:bind-payout-destination` decides WHERE a seller's money goes.
      Getting it wrong sends funds to an address nobody controls, or to
      an attacker who social-engineered the change. It is the
      money-equivalent of issuing an identity, and is human-gated for
      the same reason.
    - `:propose-release` decides WHEN money leaves. Computing a plan is
      reversible (recompute it); releasing is not.

  Everything auto-committable here is a COMPUTATION or a RECORD. Nothing
  auto-committable moves value. `settleops.governor`'s own
  `always-escalate-ops` enforces the same invariant independently --
  two layers, not one, agree on this."
  (:require [settleops.governor :as governor]))

(def read-ops #{})
(def write-ops governor/allowed-ops)

;; NOTE the invariant: the three money-deciding ops are members of
;; `write-ops` (governor-gated like any write) but are NEVER members of
;; any phase's `:auto` set below. Do not add them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed
  to auto-commit when governor-clean>}."
  {0 {:label "read-only"         :writes #{}                    :auto #{}}
   1 {:label "assisted-planning" :writes #{:plan-settlement}     :auto #{}}
   2 {:label "assisted-escrow"   :writes #{:plan-settlement :open-escrow} :auto #{}}
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
