(ns settleops.operation
  "OperationActor -- one settlement request = one supervised actor run,
  expressed as a langgraph-clj StateGraph. The advisor
  (SettlementAdvisor) is sealed into a single node (:advise); its
  proposal is ALWAYS routed through the SettlementGovernor
  (:govern) and the rollout phase gate (:decide) before anything commits
  to the SSoT.

  Everything the actor depends on is injected, so each is a swap, not a
  rewrite:
    - the Store    (MemStore today)   - `store` arg
    - the Advisor  (mock | real LLM)  - :advisor opt
    - the Phase    (0->3 rollout)     - :phase in ctx

  One graph run = one settlement request (intake -> advise -> govern ->
  decide -> commit | hold | approval). No unbounded inner loop -- each
  operation is auditable and checkpointed.

  Human-in-the-loop = real approval workflow: `interrupt-before
  #{:request-approval}` pauses the actor and hands the decision to a
  human finance/treasury reviewer. The approver resumes with
  `{:approval {:status :approved :by \"..\"}}` (or :rejected).

  Because `:bind-payout-destination` and `:propose-release` always
  escalate (governor AND phase, independently), the ONLY path by which a
  payout destination is bound or a release authorised runs through this
  interrupt -- a named human is structurally unavoidable on both, which
  is the point: those are the two moments that decide where money goes
  and when it leaves."
  (:require [langgraph.checkpoint :as cp]
            [langgraph.graph :as g]
            [settleops.advisor :as advisor]
            [settleops.governor :as governor]
            [settleops.phase :as phase]
            [settleops.store :as store]))

(defn- commit-fact [request context proposal]
  {:t           :committed
   :op          (:op request)
   :actor       (:actor-id context)
   :basket-id (:basket-id request)
   :disposition :commit
   :basis       (:cites proposal)
   :summary     (:summary proposal)})

(defn- commit-record [request _context proposal]
  {:op           (:op proposal)
   :basket-id (:basket-id request)
   :value        (or (:value proposal) {})
   :payload      (:value proposal)})

(defn build
  "Compiles an OperationActor graph bound to `store` (any
  `settleops.store/Store`).
  opts:
    :advisor      -- a `settleops.advisor/Advisor` (default: mock-advisor)
    :checkpointer -- langgraph checkpointer (default: in-mem)"
  [store & [{:keys [advisor checkpointer]
             :or   {advisor      (advisor/mock-advisor)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :record      {:default nil}
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (advisor/-advise advisor store request)]
            {:proposal p :audit [(advisor/trace request p)]})))

      (g/add-node :govern
        (fn [{:keys [request context proposal]}]
          {:verdict (governor/check request context proposal store)}))

      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (let [base (phase/verdict->disposition verdict)
                ph   (:phase context phase/default-phase)
                {:keys [disposition reason]} (phase/gate ph request base)]
            (case disposition
              :hold
              {:disposition :hold
               :audit [(cond-> (governor/hold-fact request context verdict)
                         reason (assoc :phase-reason reason :phase ph))]}

              :escalate
              {:disposition :escalate
               :audit [{:t :approval-requested
                        :op (:op request) :basket-id (:basket-id request)
                        :reason (or reason
                                    (cond (:high-stakes? verdict) :always-escalate
                                          :else :low-confidence))
                        :phase ph
                        :confidence (:confidence verdict)}]}

              :commit
              {:disposition :commit
               :record (commit-record request context proposal)
               :audit [(commit-fact request context proposal)]}))))

      (g/add-node :request-approval
        (fn [{:keys [request context proposal approval verdict]}]
          (if (= :approved (:status approval))
            {:disposition :commit
             :record (assoc (commit-record request context proposal)
                            :payload (assoc (:value proposal)
                                            :approved-by (:by approval)))
             :audit [{:t :approval-granted :op (:op request)
                      :basket-id (:basket-id request) :by (:by approval)}]}
            {:disposition :hold
             :audit [(merge (governor/hold-fact request context
                                                (assoc verdict :violations
                                                       [{:rule :approver-rejected}]))
                            {:t :approval-rejected})]})))

      ;; Commit -- the ONLY node that writes the SSoT + audit ledger.
      (g/add-node :commit
        (fn [{:keys [request context proposal record]}]
          (store/commit-record! store record)
          (let [f (commit-fact request context proposal)]
            (store/append-ledger! store f)
            {:audit [f]})))

      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(#{:governor-hold :approval-rejected} (:t %)) audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit   :commit
            :escalate :request-approval
            :hold)))

      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}]
          (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:request-approval}})))
