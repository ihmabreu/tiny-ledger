# Next steps: fields the ledger does not have

A critical read of the current account and transaction models against how production ledgers
are actually built, and what it would cost to close each gap.

This is a design note, not a backlog. Nothing here is a defect: the current model is coherent
and its omissions are mostly deliberate (see [ASSUMPTIONS.md](ASSUMPTIONS.md)). The purpose is
to make the *next* set of trade-offs explicit, so the ones worth taking are taken on evidence
and the ones that are not are refused on record.

Every suggestion below is judged against three questions:

1. **Does it protect money?** Does the field prevent a wrong balance, an unreconcilable
   statement, or a movement that should not have been allowed?
2. **Does it earn its place in the contract?** A field in a public API is a promise that
   outlives the sprint that added it. Optional-and-ignored fields are worse than absent ones.
3. **Does it preserve the invariant?** `availableBalance == sum(transactions)` is the property
   the whole design is built around, and the concurrency tier asserts it directly. Anything
   that weakens it needs a very good reason.

---

## The one structural gap that matters

Before any individual field: the model's single real weakness is that **it cannot express a
correction.**

The history is append-only and there is no update or delete, which is correct. But
[ASSUMPTIONS.md](ASSUMPTIONS.md#storage-and-lifecycle) says corrections "are made by posting a
compensating entry, never by editing the past" — and there is currently no way to say *what a
compensating entry compensates*. A reversal today is an ordinary `DEPOSIT` with a `reference` of `"reversal of
that withdrawal earlier"`, which is a free-text string that no consumer can act on.

The consequences are concrete:

- A statement cannot show a refunded payment as refunded. It shows two unrelated movements
  that happen to cancel out.
- `availableBalance` is right, but the *gross* figures are wrong: an account with one €100
  payment and one €100 reversal reports €200 of activity that never happened. Any reporting
  built on the history — turnover, fee bases, tax — inherits that error.
- There is no way to detect a double reversal. Nothing stops the same movement being reversed
  twice, because nothing records that it was reversed once.

This is the gap worth closing first, and it is the cheapest of the lot. See
`relatedTransactionId` below.

---

## Transaction fields

### Recommended

#### `relatedTransactionId` — the link a compensating entry needs

```
relatedTransactionId: UUID | null
```

The identifier of the movement this one corrects, refunds or reverses. `null` for an ordinary
movement, which is the overwhelming majority.

**Why it is the highest-value addition.** It turns the append-only history from a list of
amounts into an auditable record of *intent*. With it, a reversal is machine-readable: a
statement can render it as a reversal, reporting can exclude reversed pairs from gross
turnover, and the ledger can refuse to reverse the same movement twice.

**Why it is cheap.** It is a nullable reference on a record that is already immutable. It does
not participate in the balance arithmetic at all — a reversal is still an ordinary signed
movement that folds into `BalanceSnapshot` exactly as it does today. The invariant is
untouched.

**The one rule it introduces.** The referenced movement must exist on the same account, and
(if double-reversal protection is wanted) must not already be referenced. That check belongs
inside `Account`, under the existing lock, alongside the overdraft check — not in the service
layer, for the same check-then-act reason the overdraft check is there.

**Contract impact:** additive, non-breaking. A new optional response field and a new optional
request field, which [API_VERSIONING.md](API_VERSIONING.md#what-counts-as-a-breaking-change)
classifies as a minor version bump within v1.

#### `category` — what kind of movement this is

```
category: PAYMENT | PAYOUT | FEE | REFUND | CHARGEBACK | ADJUSTMENT | INTEREST
```

**The critique this answers.** `TransactionType` conflates two different questions.
`DEPOSIT`/`WITHDRAWAL` is *direction* — which way the money went, and it is the only thing the
balance arithmetic needs. But a fee, a payout, a chargeback and a customer withdrawal are all
`WITHDRAWAL`, and they are not the same event. Today the difference lives in `reference`, a
free-text field explicitly documented as an annotation, which means the only way to answer
"how much did this account pay in fees last month?" is to grep human prose.

**Why it is a separate field rather than more `TransactionType` values.** This is the important
design point. `TransactionType` carries the `signed()` method that drives the balance —
extending it with `FEE` and `REFUND` would mean the balance arithmetic grows a case per
business category, which is exactly the coupling the enum's current design avoids. Direction
and classification are orthogonal: a `REFUND` can be either direction depending on who is
refunding whom. They should be two fields.

**The honest cost.** An enum in a public contract is hard to change — adding a value is
non-breaking, but removing or renaming one is a v2. The list above is therefore a guess at a
domain, and guessing wrong is expensive. If the categories are not known with confidence, this
field is better deferred than approximated.

#### `Account.status` — lifecycle

```
status: ACTIVE | FROZEN | CLOSED
```

**Why a ledger needs it.** Accounts are never deleted — the history has to outlive the
relationship for retention and audit. So "this account can no longer transact" has to be
expressible as a state rather than an absence. Without it there is no way to implement a legal
hold, a fraud freeze, or a closure, and `DELETE /accounts/{id}` is not an acceptable substitute
in a system of financial record.

**Where the check goes.** Inside `Account.recordMovement(...)`, under the lock, before the overdraft
check — for the same reason the overdraft check is there rather than in the service. A status
read outside the lock is a check-then-act race against a concurrent freeze.

**A deliberate subtlety worth getting right.** `FROZEN` is usually asymmetric in practice:
credits in, debits blocked. A freeze that also rejects incoming money is rarely what the
business wants. That asymmetry is a rule, and rules in this codebase belong in the domain —
most naturally as a small policy interface alongside `OverdraftPolicy`, not as an `if` in the
aggregate.

**New error code:** `ACCOUNT_NOT_ACTIVE`, as a `422`. It is a well-formed request refused by a
business rule, which is exactly the `400`/`422` distinction the API already draws.

### Worth considering, with reservations

#### `status` on a transaction — pending vs posted

```
status: PENDING | POSTED | RELEASED
```

This models the authorisation/settlement split that card acquiring genuinely requires: a tap
places a *hold* that reduces spending power immediately, and settles or expires days later.

**Why it is flagged rather than recommended.** It is not a field. It is a change to the balance
model, and the note should say so plainly. Today there are two figures and one rule:

```
availableBalance = sum(transactions)
accountBalance   = availableBalance + overdraftLimit
```

Holds require a third figure — money that is committed but not yet moved — and the invariant
becomes `availableBalance == sum(POSTED transactions)`, with a separate reserved total netted
off spending power. That is a real design, with real questions attached (does a hold expire? can
it settle for a *different* amount, as card holds routinely do? what happens to the running
`availableBalanceAfter` stamped on each movement when an older hold settles later?).

None of those are hard, but together they are a different ledger, not an extra column. It
should be a deliberate piece of work with its own tests, or not done.

#### `counterparty` — who the money moved to or from

```
counterparty: { name, identifier, institution } | null
```

Useful for reconciliation: matching the ledger against a bank statement or a scheme settlement
file needs more than an amount and a date.

**The reservation.** Right now this is what `reference` is for, and the brief scopes movements
as being between the account and "the outside world" without modelling the outside world at
all. A structured counterparty only pays for itself once something actually consumes it — a
reconciliation process, a payout file, a transfer feature. Adding the structure before the
consumer exists produces a field that is populated inconsistently and trusted by nobody, which
is worse than the free-text it replaced.

**Note also:** it carries personal data. That is a retention and access-control question, and
this service has neither.

#### `feeAmount` / `netAmount` — gross versus net

Acquiring settlements are naturally three numbers: gross, fee, net. Putting all three on one
movement is convenient and reads well on a merchant statement.

**The reservation, and it is a real one.** It breaks the invariant. If a movement carries both
an `amount` of `100.00` and a `feeAmount` of `2.50`, then `sum(transactions)` is ambiguous —
does the balance move by 100.00 or 97.50? Whichever is chosen, the clean property that the
concurrency tier asserts becomes a rule with an exception, and rules with exceptions are how
ledgers acquire quiet bugs.

**The alternative is strictly better:** post the fee as its own movement with
`category: FEE`, linked to the payment via `relatedTransactionId`. Two movements, each
unambiguous, invariant intact, and the statement can still present them as one line because the
link says they belong together. This is how double-entry systems handle it, and it is the right
answer here for the same reason.

### Not recommended

| Field | Why not |
|---|---|
| `metadata` (free-form map) | An untyped bag is where schema discipline goes to die. Every consumer invents its own keys, nothing validates them, and within a year it is load-bearing and undocumented. If a field matters, name it. |
| `valueDate` / `settledAt` | A *third* timestamp, when the two existing ones already needed a table in the README to keep apart. Value dating matters for interest accrual, and this ledger has no interest. Add it with the feature that needs it, not before. |
| `balanceAfter` including overdraft | Deliberately absent, and should stay absent. [API_VERSIONING.md](API_VERSIONING.md#what-counts-as-a-breaking-change) already names this as the archetypal silent breaking change. |
| Soft-delete / `voided` flag on a transaction | Directly contradicts append-only. A voided movement is a reversal, and reversals are movements. |

---

## Account fields

| Field | Verdict | Reasoning |
|---|---|---|
| `status` | **Recommended** | See above. The only one of these that protects money. |
| `closedAt` | Recommended *with* `status` | Closure is an event with a date; `status: CLOSED` alone loses when. Trivial once `status` exists, and meaningless before it. |
| `externalReference` / `customerId` | Consider | The seam by which a ledger account binds to a customer in an upstream system. Cheap and opaque, but pure integration plumbing — it protects nothing on its own, and there is no upstream system here to bind to. |
| `accountType` (`CHECKING`, `SETTLEMENT`, `CLEARING`, …) | Defer | This is the first step toward a chart of accounts, which is the first step toward double entry. Valuable, but only alongside transfers — a classification that nothing routes on is decoration. |
| `ownerName` → structured party | Defer | A real system needs a party model (legal entity, addresses, identifiers), not a string. That is a customer domain, not a ledger domain, and it belongs in a different service. |
| `metadata` | No | As above. |

---

## The bigger question: double entry

Everything above is an incremental patch on a **single-entry** model — each movement touches one
account, and the balance is the sum of the column. That is the right model for a statement, and
it is the right model for this assessment.

A production financial ledger is usually **double entry**: every movement is a balanced set of
at least two postings, and money is never created or destroyed, only moved between accounts.
A €100 deposit is not one row; it is a credit to the customer's liability account and a debit to
a cash asset account, and the two must sum to zero.

**What double entry buys.** A global invariant — the sum of *every* posting in the system is
zero — which catches whole classes of bug that a per-account invariant cannot. Money appearing
from nowhere is impossible by construction rather than by review. It also makes fees, transfers
and settlements fall out naturally instead of needing special cases.

**Why it is not proposed here.** It would replace the domain, not extend it. `Account` as the
aggregate root, `BalanceSnapshot`, `TransactionType.signed()` — all of it is shaped by the
single-entry choice, and the per-account lock that makes the concurrency story work becomes
multi-account locking with an ordering rule to avoid deadlock. That is a rewrite with a
different set of interesting problems, and pretending otherwise would be dishonest about the
cost.

**The pragmatic middle**, and the actual recommendation of this document: `category` plus
`relatedTransactionId` recover most of double entry's *auditability* — you can see what each
movement was for and what it relates to — without any of its structural cost. If the ledger
later needs genuine double entry, those two fields are the ones that survive the migration
intact.

---

## Suggested order

| Step | Change | Why here |
|---|---|---|
| 1 | `relatedTransactionId` | Closes the only real structural gap. Cheapest change with the largest gain; no impact on the balance model. |
| 2 | `Account.status` + `closedAt` | Makes lifecycle expressible. Self-contained, and the check slots in beside the existing overdraft check. |
| 3 | `category` | Highest value of the remaining, but the enum is a long-lived commitment — worth doing only once the real domain vocabulary is known. |
| 4 | Fees as linked `FEE` movements | Needs 1 and 3. Delivers gross/net reporting with the invariant intact. |
| 5 | Pending/posted holds | A deliberate redesign of the balance model, with its own tests. Not an incremental field. |
| — | Transfers, multi-currency | Out of scope, and [ASSUMPTIONS.md](ASSUMPTIONS.md#consciously-out-of-scope) already says why, including where each would go. |
| — | Double entry | Not a next step at all — a different ledger. See above. |

Steps 1–3 are all additive: new optional request fields and new response fields, which are
non-breaking within `/api/v1`. Step 5 is not, and would want a `v2`.

---

## What should not change

Worth stating explicitly, because "add more fields" is an easy instinct and some of these
invite it:

- **`availableBalance == sum(transactions)`.** Every proposal above is checked against this,
  and the two that fail it (`feeAmount`, holds-as-a-field) are the two rejected or deferred.
- **The overdraft allowance stays metadata.** It is not money that moved.
- **`occurredAt` stays untrusted.** More client-supplied timestamps do not become more
  trustworthy by being more numerous.
- **`sequence` stays internal.** It is the ordering device, not a public promise.
- **No `metadata` bag**, anywhere.
