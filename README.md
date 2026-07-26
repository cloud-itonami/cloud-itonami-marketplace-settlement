# cloud-itonami-marketplace-settlement

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

## Six HARD checks (permanent, un-overridable)

| Check | What it catches |
|---|---|
| **Plan not conserved** | payouts + commission ≠ gross |
| **Payout destination** | any seller in the plan without a destination the **store** records as verified |
| **Escrow not releasable** | hold window not elapsed **or** delivery not confirmed — both, not either |
| **Disputed escrow** | releasing a `:disputed` escrow, ever |
| **Effect not `:propose`** | a proposal claiming to directly actuate |
| **Scope exclusion** | any claim to have moved funds; any op outside the allowlist |

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

## The two money moments always need a human

| Op | Auto-committable? | Why |
|---|---|---|
| `:plan-settlement` | yes | a computation; moves nothing, recompute freely |
| `:open-escrow` | yes | a record; moves nothing |
| `:bind-payout-destination` | **never** | decides *where* a seller's money goes — the money-equivalent of issuing an identity |
| `:propose-release` | **never** | decides *when* money leaves — planning is reversible, releasing is not |
| `:flag-settlement-concern` | **never** | the step before a human looks |

Both the governor's `always-escalate-ops` and every phase's `:auto` set
enforce this independently — two layers, not one.

```bash
clojure -M:dev:run   # multi-seller split, blocked destination, human-gated release
clojure -M:test      # 30 tests, 87 assertions
clojure -M:lint
```

## Rollout phases

| Phase | Writes | Auto-commits |
|---|---|---|
| 0 read-only | — | — |
| 1 assisted-planning | `:plan-settlement` | — |
| 2 assisted-escrow | + `:open-escrow` | — |
| 3 supervised-auto | all | `:plan-settlement` `:open-escrow` |

Everything auto-committable is a computation or a record. Nothing
auto-committable moves value.
