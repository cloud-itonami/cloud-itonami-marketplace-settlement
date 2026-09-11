# cloud-itonami-marketplace-settlement

**Maturity**: :implemented — mirroring what `blueprint.edn` already
declares (`:itonami.blueprint/maturity :implemented`), stated here so the
fleet's maturity scan reads the declaration instead of guessing from
prose. Implemented means the actor, its governor and its rail adapter
exist and are tested; it does **not** mean money has moved. **No transfer
has ever been executed from this repository** (see *Nothing here moves
money* below), and the deployed Worker is the read/propose surface, not a
payment rail.

Open Business Blueprint (implemented actor): **one buyer payment becomes
N seller payouts — without the operator ever taking custody.**

A marketplace basket differs from a shop basket in exactly one way that
matters to money: its lines belong to **different sellers**. A
single-store order settles to one party; a marketplace order must split,
and every split is a place where money can silently go missing.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph) StateGraph
runtime — here it is **SettlementAdvisor ⊣ SettlementGovernor**. The
arithmetic comes from
[`kotoba-lang/marketplace`](https://github.com/kotoba-lang/marketplace),
which composes [`pay`](https://github.com/kotoba-lang/pay)'s integer
allocation behind its stated *zero network I/O, zero key custody*
boundary. Design record:
[ADR-2607264000](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607264000-marketplace-federated-commerce-layer.edn).

## Nothing here moves money

A settlement plan is a **computation** — an auditable statement of who
should receive what. Every plan carries `:plan/custodial? false` on the
record itself. A release, once a human approves it, *records that
authorisation*; a rail adapter (`nexus-x402` for on-chain USDC, Stripe
for cards) performs the transfer outside this actor.

Any claim to have *already* transferred, paid out or settled funds is a
HARD, permanent scope exclusion. No op that directly transfers funds is
in the allowlist — `:propose-release` **authorises** a transfer for a
rail to perform; it does not perform one.

## Conservation

```clojure
(:plan/conserved? plan)   ; seller payouts + commission == gross, exactly
```

Checked inside `marketplace.settlement` *and* again by the governor —
two layers, because a rounding leak is silent. The library test asserts
it across a matrix of commission rates (0, 1, 250, 1000, 3333, 9999,
10000 bps) and basket shapes.

**Rounding dust goes to the seller, never the operator.** `pay`'s
`split-allocations` routes the remainder to the first entry in the split
vector, and the seller is deliberately placed first. On a marketplace
with many small orders, defaulting that toward the operator would be a
quiet, compounding extraction.

The per-order fixed fee is charged to the **buyer on top of goods**, not
skimmed from the sellers' side — so `:plan/buyer-charge-minor` exceeds
`:plan/gross-minor`, and conservation is still checked against goods.

## Nine HARD checks (permanent, un-overridable)

| Check | What it catches |
|---|---|
| **Plan not conserved** | payouts + commission ≠ gross |
| **Payout destination** | any seller in the plan without a destination the **store** records as verified |
| **Escrow not releasable** | hold window not elapsed **or** delivery not confirmed — both, not either |
| **Disputed escrow** | releasing a `:disputed` escrow, ever |
| **Effect not `:propose`** | a proposal claiming to directly actuate |
| **Scope exclusion** | any claim to have moved funds; any op outside the allowlist |
| **Funds not arrived** | on a custodial flow, opening an escrow or authorising a release with no PSP-attested capture that settles the plan exactly |
| **Capture not derivable** | a payment record that `marketplace.acceptance` would refuse — a buyer's screenshot, an expired code, a bound-amount mismatch |
| **Refund has nothing to come from** | a refund with no capture, after the escrow released, on a disputed escrow, or above what is still held |

### The funds gate

Until this existed, **an unpaid order could be released in full.** Release
required delivery and an elapsed window; nothing asked whether the buyer's
money had arrived. It now does, and the check is *rail-aware* because the
two rails are genuinely different (see below):

| Flow | Acceptance record required? | Why |
|---|---|---|
| x402 (`:direct-split`) | **no** | the buyer paid each seller's own treasury directly; there is no operator-side receipt to demand, and `reconcile` is the evidence |
| stripe / bank-transfer / コード決済 PSP (`:transfer`) | **yes** | the money passed through the operator, so its arrival is a fact this actor can check — and must |

A **missing** acceptance refuses (`:payment-not-recorded`). Unknown is not
"probably fine": that asymmetry is the whole point. A short payment
refuses too — paying sellers in full on a short payment pays the
difference out of the operator's own money — and so does an overpayment,
which owes the buyer a refund first.

The gate reads the **store**, never the proposal. A proposal asserting
`{:paid? true}` is worth exactly what one asserting `:payout/verified?` is
worth, which is nothing.

### A short payment can be chased

`:mpm-static` carries no amount, so a buyer can type one too few digits.
That used to be reported as `:short` and left there. Now
`acceptance/shortfall-minor` names what is owed, `top-up-request` builds a
second code for exactly that (bound to the amount, so the mistake cannot
repeat), and a second capture **tops the first up rather than replacing
it** — one record per order carrying the total, with both PSP transaction
ids kept.

That last part was a live defect: the store wrote captures with
`assoc-in`, so a second capture for an order silently erased the first,
and the money the buyer had already sent vanished from the figure the
funds gate reads. A second capture that is not a top-up of the first is
now refused by name (`:duplicate-capture`).

### Refunds close the gate behind them

`:propose-refund` gives money back to the **buyer**, and the refusals are
the mirror image of the release ones:

- **after a release** → refused. That money went to the sellers; paying the
  buyer as well is paying twice, and the correction path is a dispute or a
  chargeback, not this op.
- **on a disputed escrow** → refused. Refunding *is* deciding the dispute,
  and no actor here adjudicates.
- **above what is still held** → refused. The ceiling is
  `net-captured-minor` (captured − already refunded), so two refunds of
  half each are fine and two of the full amount are not.

A booked refund flows straight back into the funds gate, because
`settlement-status` reads the net figure: a partial refund makes the order
`:short`, a full one makes it `:missing`, and the release that capture
would have funded stops being authorisable. The instruction itself is
**derived by the store** from the stored capture plus the approver's name —
an unattributed refund books nothing at all.

`:payout/verified?` is read from the store, never from the proposal. An
unverified destination is caught **before approval**, not discovered at
execution time — the same failure mode
[ISIC 4791](https://github.com/cloud-itonami/cloud-itonami-isic-4791)
guards with its `:payment-processor-linked?` gate.

"Disputed" is reported as its own distinct violation rather than folded
into "not releasable", because a human reading the ledger needs to know
*which* is true.

## No actor resolves a payment dispute

`:disputed` has **no outgoing edge** in the escrow transition table. The
only way out is `marketplace.settlement/resolve-dispute`, which requires
an explicit outcome and a **named human** and returns nil without one.
There is deliberately no function that reads the evidence and computes
an outcome. This preserves the fleet-wide invariant ISIC 4791
established (ADR-2607264000 D5): *no actor adjudicates.*

## The money moments always need a human

| Op | Auto-committable? | Why |
|---|---|---|
| `:plan-settlement` | yes | a computation; moves nothing, recompute freely |
| `:open-escrow` | yes | a record; moves nothing |
| `:bind-payout-destination` | **never** | decides *where* a seller's money goes — the money-equivalent of issuing an identity |
| `:propose-release` | **never** | decides *when* money leaves — planning is reversible, releasing is not |
| `:record-payment-capture` | **never** | writes the evidence the funds gate reads. It moves nothing, which is exactly what makes it look safe to automate — an actor that can write its own payment evidence can unlock its own release |
| `:propose-refund` | **never** | decides *when* money leaves toward the buyer — the mirror of a release, and writable only where release is (phase 3) |
| `:flag-settlement-concern` | **never** | the step before a human looks |

Both the governor's `always-escalate-ops` and every phase's `:auto` set
enforce this independently — two layers, not one.

## The rail adapter, and what x402 actually is

`settleops.rail` turns an authorised release into rail instructions —
and building it surfaced a design fact worth stating plainly.

**`nexus-x402` is a facilitator, not a payout rail.** Its own README:
*the facilitator holds no keys*, and payments settle *to each seller's
own treasury*. Its admin surface is `PUT /admin/sellers/<seller>` and
`GET /admin/settlements/<seller>`. There is no endpoint that sends
money, by design.

That is not a gap to work around — it means the two rails are genuinely
different shapes:

| Rail | Kind | Custodial? | What a release means |
|---|---|---|---|
| x402 | `:direct-split` | no | the buyer already paid each seller's treasury directly; the job is **reconciliation** |
| Stripe | `:transfer` | yes | funds passed through the platform; the job is a **Connect transfer** |
| コード決済 | — | yes | **not a payout rail at all** — see below |

**コード決済 (QR / barcode code payment) is refused here by name.** It is
the buyer → platform direction, modelled in `marketplace.acceptance`, and
a code-payment PSP settles to ONE merchant's bank account — so it can
never be a per-seller payout destination. An instruction on that rail
fails with `:not-a-payout-rail` rather than `:unknown-rail`, because the
next person to see `:unknown-rail` for `:code-payment` would reasonably
"fix" it by adding an entry to the kind table, which is the exact mistake
the refusal exists to stop. The seller's share leaves the merchant bank
account as a `:bank-transfer` (`acceptance/payout-leg-rail`).

`execute-transfer!` refuses an x402 instruction outright, because there
is nothing to execute. Inventing an x402 `POST /transfer` would have
produced code that looks finished and fails in production.

`reconcile` reports `:over` as loudly as `:short` — a seller receiving
more than the plan says is as much a defect, and silently accepting it
hides a double payment. A seller the rail has no record of is
`:missing`, not zero; those are different facts.

### The client cannot move money by itself

- **No ambient HTTP.** Every network function takes an `http` function
  as an argument. The namespace requires no client and opens no socket.
- **Dry run by default.** `execute-transfer!` returns the request it
  *would* have sent unless `:execute? true` is passed explicitly.
  Building a request and sending it are different acts.
- **A named human, always.** No `:authorised-by` → `:no-named-authoriser`.
- **Idempotent.** The `Idempotency-Key` is derived from escrow + seller,
  so a retry after a timeout cannot pay twice, and `:transfer_group`
  traces every transfer back to the release that justified it.
- **Conservation re-checked** at the last point before a rail, on top of
  the plan's own check — a leak from a bad destination lookup would
  otherwise be invisible until a seller complained.

Every test injects a recording stub. **No transfer has been executed
from this repository.**

```bash
kbb -M:dev:run   # split, unverified destination, the funds gate, human-gated
                     # release, and a refund refused after that release
kbb -M:test      # JVM — 126 tests, 489 assertions
npm ci && npm run test:cljs   # ClojureScript on Node — the SAME 126 / 489
kbb -M:lint
```

Every namespace except `settleops.edge.worker` is `.cljc`, and CLAUDE.md's
runtime priority puts ClojureScript above the JVM — so the suite runs on
both rather than asserting portability. `governor_test` was `.clj` and is
now `.cljc` for that reason: it is the namespace whose refusals matter
most, and it was the one the cljs run could not see.

## Rollout phases

| Phase | Writes | Auto-commits |
|---|---|---|
| 0 read-only | — | — |
| 1 assisted-planning | `:plan-settlement` `:record-payment-capture` | — |
| 2 assisted-escrow | + `:open-escrow` | — |
| 3 supervised-auto | all (incl. `:propose-release` `:propose-refund`) | `:plan-settlement` `:open-escrow` |

`:record-payment-capture` is writable from phase 1, alongside planning,
because from phase 2 on an escrow **cannot** open on a custodial flow
until a capture is recorded. Enabling the gated op without the op that
satisfies the gate would ship a phase that can only refuse.

Everything auto-committable is a computation or a record **nothing else
is gated on**. Nothing auto-committable moves value.
