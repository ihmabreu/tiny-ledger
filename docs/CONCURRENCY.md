# Concurrency

A ledger that loses one movement under load is worse than a ledger that is simply down: the
failure is silent, and you find out at reconciliation. This document states what the service
guarantees, how, and how that claim is tested rather than asserted.

## The guarantees

1. **No lost updates.** Every accepted movement appears exactly once in the history and exactly
   once in the balance.
2. **`availableBalance == sum(transactions)`, always.** The incremental snapshot never diverges
   from a full replay of the history.
3. **The overdraft allowance is never breached**, no matter how many withdrawals race.
4. **Sequence numbers are unique and contiguous** — `1..n` with no gaps and no duplicates.
5. **Reads are never torn.** A concurrent reader sees a balance that corresponds to some real
   prefix of the history, never a half-applied movement.
6. **Movements on different accounts do not interfere**, and do not serialise against each
   other.

## How

### The lock lives with the data

The `ReentrantLock` is a private field of `Account`, alongside the history and the snapshot it
protects. Nothing outside the aggregate can mutate that state, so there is no way to forget the
lock at a call site — the only path in is `recordMovement`, and it takes the lock itself.

The alternative — locking in the service layer — puts the lock and the data it guards in
different classes, and every future caller has to remember the convention. Conventions are not
guarantees.

### The critical section is short

Under the lock: validate the currency, evaluate the overdraft policy against the *resulting*
balance, append the transaction, fold the snapshot, publish it. No I/O, no logging, no
allocation-heavy work, no calls back out to other components. A few hundred nanoseconds.

The overdraft check has to be inside the lock. Checking before acquiring it would be a
check-then-act race: two concurrent withdrawals against a €100 balance could both observe €100,
both conclude they are permitted, and both proceed. This is exactly what test 3 below is built
to catch.

### Reads take no lock

```java
private volatile BalanceSnapshot balanceSnapshot;
```

The snapshot is an immutable record published through a `volatile` field. A writer builds a new
snapshot and assigns it; the volatile write gives the reader a happens-before edge over
everything the writer did beforehand — including appending to the history. A reader therefore
observes a snapshot that is consistent with some real prefix of the history, without ever
blocking a writer or being blocked by one.

`GET /balance` is the most frequently called endpoint in almost any ledger. Making it lock-free
means read traffic imposes no cost on write traffic at all.

### `ReentrantLock`, not `synchronized`

This is the decision that pairs with virtual threads. On the Java 21 baseline a virtual thread
that blocks inside a `synchronized` block **pins** its carrier platform thread: the carrier is
held hostage until the monitor is released, and the pool of carriers is small. A thousand
virtual threads contending on a `synchronized` hot account can starve the carrier pool and stall
work that has nothing to do with that account.

A virtual thread blocking on a `ReentrantLock` unmounts instead, freeing its carrier. Under the
hot-account load test the queue drains steadily rather than collapsing.

*(JEP 491 removes most pinning in later releases, but writing code that depends on a specific
JDK's pinning behaviour to be correct is not a trade worth making.)*

### Per-account locking, deliberately

Locking is per `Account`, so throughput scales with the number of accounts being touched, and
operations on the *same* account serialise. That serialisation is not a flaw to be optimised
away — it is what makes the overdraft rule enforceable. Two withdrawals on one account genuinely
must be ordered; there is no correct concurrent answer.

The consequence is honest: a single very hot account is a bottleneck by design. The load tests
measure that case on purpose rather than reporting only the flattering multi-account number.

### The repository

`InMemoryAccountRepository` uses a `ConcurrentHashMap` for lookup and a `CopyOnWriteArrayList`
for registration order. Registration order is tracked explicitly rather than by sorting on
`openedAt`, because tests pin the clock — every account is then opened at the identical instant
and timestamp ordering is undefined. A correctness property should not depend on the clock
ticking fast enough to break a tie.

### Why no lock-free design

`AtomicReference` with a compare-and-swap retry loop would work for the balance. It was not used
because the history and the snapshot must move together atomically, and a CAS loop across two
structures is materially harder to prove correct than a short lock — for no measured gain
(p95 is already 1 ms under 3,200 rps on one hot account). The lock is the boring choice, and
boring is the right tone for a ledger.

---

## How it is tested

The concurrency tier (`./gradlew :app:concurrencyTest`) drives the ledger with thousands of
virtual threads released simultaneously from a `CountDownLatch`, so the threads are actually
contending rather than politely queueing behind ramp-up.

| # | Test | What would fail it |
|---|---|---|
| 1 | `recordMovement_concurrentDeposits_losesNoUpdates` — thousands of concurrent deposits | A non-atomic read-modify-write on the balance |
| 2 | `recordMovement_concurrentMixedMovements_maintainsBalanceEqualToHistorySum` — mixed deposits and withdrawals | Appending to the history and folding the snapshot not being atomic together |
| 3 | `recordMovement_concurrentWithdrawalsExceedingAllowance_enforcesExactLimitWithoutBreach` — many racing withdrawals against a known allowance; **exactly** the affordable number must succeed | Checking the overdraft outside the lock |
| 4 | `recordMovement_concurrentDeposits_assignsUniqueAndContiguousSequenceNumbers` | A racy sequence counter |
| 5 | `recordMovement_concurrentMixedMovements_recordedBalanceMatchesHistoryReplay` — each movement's recorded `availableBalanceAfter` must match a replay of the history to that point | An incremental fold that drifts from a full recomputation |
| 6 | `consistentView_concurrentWithMovements_servesConsistentSnapshotWithoutTornReads` — readers hammer the balance while writers record | Unsafe publication, or a torn read of a mutable snapshot |

Test 3 is the sharpest of the six. With a €100.00 allowance and a flood of €1.00 withdrawals,
**exactly 100** must be accepted and every other one refused with 422. A check-then-act race
shows up as 101 or 130 successes — a failure that is arithmetic, not statistical.

The same properties are then re-asserted over real HTTP in `LedgerApiConcurrencyTest`, because
a guarantee that only holds when you bypass the API layer is not a guarantee a client can use.
That tier also checks that movements on unrelated accounts do not interfere.

### On both memory models

The tier is executed on `aarch64` and on `x86_64`, and that is a correctness requirement rather
than thoroughness for its own sake.

`x86_64` gives you Total Store Order almost for free: stores become visible in program order,
so a missing `volatile` frequently goes unnoticed. `aarch64` is weakly ordered and will reorder
stores that x86 never would. The unsafe-publication bug — a reader seeing a half-constructed
snapshot because the field was not `volatile` — is therefore *architecture-dependent*. It can
pass forever on an x86 CI runner and corrupt balances the day the service is deployed to Graviton
or Ampere.

Since the whole lock-free read path rests on that one `volatile` field, running these tests on a
weakly-ordered machine is the test that actually exercises the claim. Both architectures pass all
ten. Method, timings and the caveat about the emulated leg are in
[TESTING.md](TESTING.md#concurrency-across-architectures).

### And under load

The Gatling simulations cover both shapes — parallel across accounts, and serialised on one
hot account — and are run on both architectures. The architecture difference appears only in the
latency tail, never in correctness. Numbers and method are in
[TESTING.md](TESTING.md#measured-results).
