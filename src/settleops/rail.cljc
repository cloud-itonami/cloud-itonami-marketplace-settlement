(ns settleops.rail
  "Turning an authorised release into rail-specific instructions — and
  the discovery that the two rails are not the same shape at all.

  ## What the x402 rail actually is

  `gftdcojp/nexus-x402` is a **facilitator**, not a payout rail. Its own
  README is explicit: *the facilitator holds no keys*, and payments
  settle *to each seller's own treasury*. Its admin surface is
  `PUT /admin/sellers/<seller>` (register pricing rules) and
  `GET /admin/settlements/<seller>` (read what settled). There is no
  endpoint that sends money, by design.

  That is not a gap to work around. It means the x402 rail's marketplace
  shape is **split-at-pay-time, not transfer-afterwards**: the buyer's
  payment goes directly to each seller's treasury address in the
  proportions `marketplace.settlement` computed, and the operator never
  touches it. The adapter's job on this rail is therefore
  RECONCILIATION — did the money that was supposed to land, land? — not
  disbursement.

  The Stripe rail is the opposite: funds do pass through the platform
  account, so a release becomes a real per-seller transfer request.

  One release, two genuinely different instructions. Pretending
  otherwise — inventing an x402 `POST /transfer` that does not exist —
  would produce code that looks finished and fails in production.

  ## Nothing here moves money

  This namespace is pure: it builds instruction records and compares
  observed settlements against a plan. `settleops.rail.client` holds the
  only I/O, and it will not execute a transfer without an explicit
  operator opt-in (see that namespace).

  Pure: no clock, no network, no randomness."
  (:require [clojure.string :as str]
            [marketplace.settlement :as settle]))

;; ───────────────────────── instructions ─────────────────────────

(def instruction-kinds
  "How money reaches a seller on each rail.

    :direct-split  the buyer pays each seller's own address directly, in
                   the computed proportions. The operator is never in
                   the path. (x402 / on-chain)
    :transfer      funds sat in the platform account and are moved out
                   per seller. (Stripe Connect and similar)"
  #{:direct-split :transfer})

(def ^:private rail->kind
  {:x402          :direct-split
   :stripe        :transfer
   :bank-transfer :transfer})

(defn instruction-kind
  "Which shape a rail takes. nil for an unknown rail."
  [rail]
  (get rail->kind rail))

(defn instruction
  "One seller's instruction, derived from an allocation in a settlement
  plan plus that seller's verified payout destination.

  Amounts come from the plan, never recomputed here — the plan is the
  audited artefact and a second implementation of the arithmetic is a
  second chance to disagree with it."
  [{:keys [escrow-id alloc destination currency]}]
  (let [rail (:payout/rail destination)]
    {:instruction/escrow      escrow-id
     :instruction/seller      (:alloc/seller alloc)
     :instruction/rail        rail
     :instruction/kind        (instruction-kind rail)
     :instruction/to          (:payout/address destination)
     :instruction/amount-minor (:alloc/seller-payout-minor alloc)
     :instruction/currency    currency
     :instruction/executed?   false
     :instruction/custodial?  (= :transfer (instruction-kind rail))}))

(defn instructions-for
  "Every seller instruction for a RELEASED escrow.

  Returns nil unless the escrow is `:released` and names who released
  it. An escrow still `:held` has no instructions — there is nothing to
  reconcile or transfer yet — and one with no named releaser was never
  properly authorised, so producing instructions from it would launder
  a missing signature into a payment."
  [escrow destinations]
  (let [plan (:escrow/plan escrow)]
    (when (and (= :released (:escrow/state escrow))
               (not (str/blank? (str (:escrow/released-by escrow)))))
      (mapv (fn [a]
              (instruction {:escrow-id (:escrow/id escrow)
                            :alloc a
                            :destination (get destinations (:alloc/seller a))
                            :currency (:plan/currency plan)}))
            (:plan/allocations plan)))))

(defn instruction-errors
  "Structural errors on one instruction."
  [i]
  (vec
   (concat
    (when-not (contains? settle/payout-rails (:instruction/rail i))
      [{:rail.error/code :unknown-rail :rail.error/detail (pr-str (:instruction/rail i))}])
    (when-not (contains? instruction-kinds (:instruction/kind i))
      [{:rail.error/code :unknown-instruction-kind}])
    (when (str/blank? (str (:instruction/to i)))
      [{:rail.error/code :missing-destination}])
    (when-not (and (integer? (:instruction/amount-minor i))
                   (not (neg? (:instruction/amount-minor i))))
      [{:rail.error/code :invalid-amount}])
    (when (str/blank? (str (:instruction/escrow i)))
      [{:rail.error/code :missing-escrow}]))))

(defn conserved?
  "Do the instructions add up to exactly what the plan says sellers are
  owed?

  Checked again HERE, on top of the plan's own `:plan/conserved?`,
  because this is the last point before money is described to an
  external system. A leak introduced by a bad destination lookup or a
  dropped allocation would otherwise be invisible until a seller
  complained."
  [escrow instructions]
  (= (get-in escrow [:escrow/plan :plan/seller-payout-total-minor])
     (reduce + 0 (map :instruction/amount-minor instructions))))

;; ───────────────────────── reconciliation ─────────────────────────

(defn reconcile
  "Compare what the plan said each seller should receive against what a
  rail reports actually settled.

  This is the x402 rail's PRIMARY operation, not an afterthought: since
  the buyer pays each seller directly and nobody in the middle can force
  it, the only way to know a seller was paid is to look.

  `observed` is `{seller-id amount-minor}`.

  Returns per-seller `{:seller .. :expected .. :observed .. :status ..}`
  where status is `:settled` / `:short` / `:over` / `:missing`. `:over`
  is reported rather than ignored — a seller receiving more than the
  plan says is as much a defect as receiving less, and silently
  accepting it hides a double payment."
  [instructions observed]
  (mapv (fn [i]
          (let [seller (:instruction/seller i)
                want   (:instruction/amount-minor i)
                got    (get observed seller)]
            {:seller   seller
             :expected want
             :observed got
             :status   (cond
                         (nil? got)  :missing
                         (= got want) :settled
                         (< got want) :short
                         :else        :over)}))
        instructions))

(defn fully-settled?
  "True only when every seller's expected amount was observed exactly."
  [reconciliation]
  (and (seq reconciliation)
       (every? #(= :settled (:status %)) reconciliation)))

(defn unsettled
  "The rows that need a human: anything not exactly `:settled`."
  [reconciliation]
  (vec (remove #(= :settled (:status %)) reconciliation)))
