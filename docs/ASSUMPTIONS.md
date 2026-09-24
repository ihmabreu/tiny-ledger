# Assumptions

The brief is one page long, which means most of the interesting decisions are not in it. This
file records the ones that shaped the code, and what each would cost to reverse.

## What the brief asked for

> Record a money movement (deposit / withdrawal); view the current balance; view the
> transaction history. In-memory storage is fine. No authentication, no logging/monitoring, no
> database transactions required.

Everything below is an interpretation on top of that.

---

## Domain

**Accounts are plural.** The brief says "a ledger", which could be read as a single global
balance. Modelling multiple accounts costs almost nothing — one `ConcurrentHashMap` instead of
one object — and it is what makes the concurrency story interesting: per-account locking gives
real parallelism across accounts, which a single global balance could never show. A
single-account ledger is the degenerate case of this one.

**Currency is fixed when the account is opened.** An account is denominated in exactly one
currency, and every movement against it must match. The alternative — a multi-currency account
— needs exchange rates, rate timestamps and a conversion audit trail, which is a different
(and much larger) problem than the one being asked about. A movement in the wrong currency is
rejected with `422 CURRENCY_MISMATCH` rather than silently converted.

**Amounts must be strictly positive.** Direction is carried by `TransactionType`
(`DEPOSIT`/`WITHDRAWAL`), not by the sign of the amount. Allowing a negative deposit would
create two ways to express the same thing, and two ways to express the same thing is two code
paths to keep in agreement. A zero-amount movement is rejected as well: it is almost always a
client bug, and recording it would add noise to the statement for no benefit.

**Amounts are scaled to the currency's minor unit.** The scale comes from
`java.util.Currency.getDefaultFractionDigits()`, not from a hard-coded `2`. EUR gets two
decimal places, JPY gets none, and neither needs a special case. Values arriving with too much
precision are rejected rather than rounded — a ledger silently discarding a fraction of a cent
is how reconciliation breaks.

**The overdraft allowance is account metadata, not a transaction.** It is a credit limit the
bank extends, not money that was deposited. Recording it as a transaction would make
`sum(transactions)` disagree with the money that actually moved, which is exactly the
invariant worth protecting. See [ARCHITECTURE.md](ARCHITECTURE.md#the-balance-model).

**There is no opening-balance field.** Accounts start empty and are funded by a deposit. This
keeps the `availableBalance == sum(transactions)` invariant true from the first moment, rather
than true-after-the-first-transaction.

**There are no transfers.** The brief asks for deposits and withdrawals — movements between
the account and the outside world. A transfer is a different feature: it is two movements that
must succeed or fail together, which drags in multi-account locking with a deadlock-avoidance
ordering rule, and (in any real system) a saga or a database transaction. Deliberately out of
scope. What is provided instead is an optional free-text `reference` on every movement
("Salary", "ATM withdrawal"), which covers the traceability need without pretending to
transactional integrity that is not there.

**History is newest-first and paginated.** A statement is read from the most recent movement
backwards. Pagination is not premature optimisation: an append-only history has no upper bound,
and an endpoint that returns *everything* is a denial-of-service vector waiting for a busy
account. Default page size 20, maximum 100.

---

## Time

**The clock is injected**, as a `java.time.Clock` CDI bean, never `Instant.now()` at a call
site. Tests can then pin time and assert on exact timestamps. This is also why transaction
*ordering* does not depend on the clock: a pinned clock makes every timestamp identical, and
two movements on a fast machine can share a wall-clock instant anyway. Each transaction
carries a monotonic `sequence` number, and that is what defines order.

**`sequence` is not exposed in the API.** It is an internal ordering and audit device. Putting
it in the contract would freeze an implementation detail into a public promise.

---

## API

**Versioned from day one** (`/api/v1`). Adding a version prefix later means breaking every
existing client at the moment you can least afford it. See
[API_VERSIONING.md](API_VERSIONING.md).

**Monetary values are JSON strings, not JSON numbers.** Most JSON parsers decode numbers as
IEEE-754 doubles, and `0.1 + 0.2 != 0.3` is not a property a ledger should inherit from its
serialisation format.

**`400` and `422` mean different things.** `400` is a malformed request — a bad currency code,
a negative amount, `limit=abc`. `422` is a well-formed request refused by a business rule —
insufficient funds, currency mismatch. The distinction is useful to clients: a `422` may
succeed if retried after a deposit, whereas retrying a `400` unchanged never will.

**Errors share one shape** (`code`, `message`, `timestamp`, optional `details`). The `code` is
a stable machine-readable token; the `message` is for humans and may be reworded without it
counting as a breaking change.

**The OpenAPI document is hand-written and served verbatim.** Quarkus' annotation scanning is
switched off (`mp.openapi.scan.disable=true`), so `/q/openapi` returns the same bytes that
were reviewed and committed. Generated specs drift to describe whatever the code happens to do;
a hand-written one states what the code is *supposed* to do, and the contract tests then hold
the code to it.

---

## Storage and lifecycle

**Storage is in memory and state is lost on restart.** Explicitly allowed by the brief. The
`AccountRepository` interface is the seam a persistent implementation would slot into; nothing
above it knows how storage works.

**History is append-only.** There is no update or delete. Corrections in a real ledger are made
by posting a compensating entry, never by editing the past.

**Demo data is seeded at startup** so the API is explorable the moment it boots, and is
disabled in every test profile so no tier is testing against accidental state.

---

## Consciously out of scope

| Left out | Why | Where it would go |
|---|---|---|
| Authentication / authorisation | Excluded by the brief | A JAX-RS filter plus an account-ownership check in `LedgerService` |
| Persistence | Excluded by the brief | A second `AccountRepository` implementation |
| Database transactions | Excluded by the brief; the per-account lock provides atomicity within one JVM | `@Transactional` around the service methods |
| Logging / monitoring | Excluded by the brief | Quarkus Micrometer; the service layer is the natural place to time |
| Transfers between accounts | Different problem — see above | A new use case with ordered multi-account locking |
| Multi-currency accounts | Needs FX rates and a conversion audit trail | A rate provider behind the service |
| Interest, fees, scheduled movements | Not asked for | Scheduled jobs posting ordinary transactions |
| Horizontal scale | Per-account locks are per JVM; two instances would not see each other's state | Shared store with optimistic locking, or partitioning accounts across instances |

The last row is the most important one to say out loud: **this service is correct on a single
node and only on a single node.** The in-memory store makes that unavoidable, and no amount of
locking inside one JVM changes it.
