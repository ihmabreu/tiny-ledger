# Testing

Six tiers. The risk of having six is that they all end up testing "deposit €10 works" and the
suite grows expensive without growing informative. So each tier here has a stated job, and a
scenario belongs to exactly one of them.

| Tier | Command | Runs | Its job | Deliberately not its job |
|---|---|---|---|---|
| Unit | `:app:test` | 103 tests | Domain and service logic in isolation, especially edge cases | HTTP, JSON, wiring |
| Integration | `:app:integrationTest` | 30 tests | HTTP semantics and conformance to the OpenAPI contract | Business edge cases |
| BDD | `:app:bddTest` | 14 scenarios | Acceptance criteria in the language of the brief | Technical edge cases |
| E2E | `:app:e2eTest` | 5 tests | The packaged artefact actually boots and serves | Exhaustive coverage |
| Concurrency | `:app:concurrencyTest` | 10 tests | Correctness under contention | Throughput |
| Load | `:load-tests:gatlingRun` | 2 simulations | Throughput and latency | Correctness |

`./gradlew build` runs the first five. All six can be run individually; the tiers are separated
by JUnit 5 tags and each executes in its own JVM, because `@QuarkusTest` and
`@QuarkusIntegrationTest` classes cannot coexist in one.

---

## Unit — `tag: unit`

Plain JUnit 5 and AssertJ, no Quarkus, no HTTP, no mocking framework. The domain depends on
nothing, so the tests need nothing; `DefaultLedgerService` takes the in-memory repository
directly, because a real implementation that is 40 lines long is a better collaborator than a
mock of it. The `Clock` is fixed, so timestamps are asserted exactly.

This is where the awkward cases live: zero and negative amounts, an amount with more precision
than the currency allows, JPY's zero decimal places, adding EUR to GBP, withdrawing exactly the
overdraft limit versus one cent past it, a `fold()` applied twice, pagination at `offset ==
size`, `limit` above the maximum.

`./gradlew :app:test` also produces the JaCoCo report at
`app/build/reports/jacoco/test/html/index.html`.

## Integration — `tag: integration`

`@QuarkusTest` with a real HTTP server and RestAssured. The job here is the things unit tests
cannot see: status codes, headers, JSON field names, serialisation of money as strings,
validation messages, pagination parameters.

Every request and response is validated against
[`META-INF/openapi.yaml`](../app/src/main/resources/META-INF/openapi.yaml) by
`swagger-request-validator`, wired in as a RestAssured filter. **This is what makes "API-first"
more than a slogan**: the hand-written contract is executable, and an undocumented field or a
changed type fails the build rather than surprising a client.

The validator is strict in both directions, which means it also rejects deliberately malformed
*requests* before they reach the server. `AbstractContractTest` therefore exposes two clients:
`api()` (validated) for normal tests, and `rawApi()` (unvalidated) for the handful that need to
assert how the server responds to contract-violating input such as `limit=abc`.

`ApiDocumentTest` asserts that `/q/openapi` serves the committed file byte-for-byte — annotation
scanning is disabled, and this test is what keeps it that way.

## BDD — `tag: bdd`

Cucumber, driving the real HTTP API. Two feature files, fourteen scenarios, written in the
vocabulary of the brief:

```gherkin
Background:
  Given an account "Ada" in EUR with an overdraft allowance of 100.00

Scenario: Spending beyond the agreed overdraft is refused
  When I withdraw 100.01 EUR from "Ada"
  Then the withdrawal is refused because of insufficient funds
  And the available balance of "Ada" is 0.00 EUR
  And the transaction history of "Ada" is empty
```

The value is not extra coverage — the integration tier already exercises these paths. It is
that the acceptance criteria are stated in a form a non-engineer can read and disagree with.
The last two lines are the business rule people actually care about: **a refused movement must
leave no trace**, neither in the balance nor in the statement.

One scenario is worth singling out. `The overdraft allowance is not a deposit` asserts that an
account opened with a €100 allowance has an empty history and a zero available balance. That is
the modelling decision from [ASSUMPTIONS.md](ASSUMPTIONS.md#domain) written down where a
product owner can check it.

Scenarios are kept at business-rule level. There is no `Scenario: limit=abc returns 400`; that
belongs in the integration tier.

The runner pins both `glue` and `features`:

```java
@CucumberOptions(glue = "com.teya.tinyledger.bdd", features = "classpath:features")
```

Left unannotated, Cucumber scans the whole classpath for step definitions. That is slow, and it
drags every test-scope dependency through the scan — which is what produced the wall of
`Failed to load class net.bytebuddy.agent.VirtualMachine$...` lines in the build output, as
ByteBuddy's platform probes were pulled in and each non-matching OS variant logged a miss.
Narrowing the glue removes both the scan and that noise.

The `features` half is not decoration. Adding `@CucumberOptions` also switches the feature-path
default from *the whole classpath* to *the annotated class's own package* — and no `.feature`
files live in `com/teya/tinyledger/bdd`. Setting `glue` alone would discover **zero scenarios and
still report the build green**, which is the worst possible failure mode for a test tier. Both
are pinned, and the scenario count is what proves it.

## End-to-end — `tag: e2e`

`@QuarkusIntegrationTest` against the packaged `quarkus-run.jar` — a separate process, no test
classpath, no `@QuarkusTest` magic, no injected beans. Black box.

This tier exists for the failure mode every other tier is blind to: an application that passes
all its tests and then does not start. Missing reflection registration, a resource not packaged,
a misconfigured bean — these only appear in the real artefact.

Five tests: the service boots and serves the contract; a full account lifecycle over HTTP;
the balance and history agree; a refused withdrawal behaves; Swagger UI is reachable.

Note that `@QuarkusIntegrationTest` runs **without** the test profile, so the demo seeder is
active here. Rather than fight that, the tests assert on the seeded accounts — which
incidentally verifies the seeder itself, something no other tier does.

## Concurrency — `tag: concurrency`

Ten tests, thousands of virtual threads, released simultaneously from a `CountDownLatch`.
Documented in full in [CONCURRENCY.md](CONCURRENCY.md#how-it-is-tested).

The design principle: **make failures arithmetic, not statistical.** A €100.00 allowance and a
flood of €1.00 withdrawals must accept *exactly* 100 and refuse the rest. A race shows up as
101, not as an occasional flake, so the test either passes or names a real bug.

## Load and performance

Gatling, in its own Gradle module (`load-tests`) with its own dependency set, run against a
running service rather than inside the test JVM — anything else measures the test harness.

```bash
./gradlew :app:build -x test -x bddTest -x integrationTest -x concurrencyTest -x e2eTest
java -jar app/build/quarkus-app/quarkus-run.jar &

./gradlew :load-tests:gatlingRun \
  --simulation=com.teya.tinyledger.loadtest.MultiAccountThroughputSimulation
./gradlew :load-tests:gatlingRun \
  --simulation=com.teya.tinyledger.loadtest.HotAccountContentionSimulation
```

Target another environment with `-Dtinyledger.baseUrl=http://host:port`. Reports are written to
`load-tests/build/reports/gatling/`.

Two simulations, because one of them would be flattering and misleading:

- **`MultiAccountThroughputSimulation`** spreads traffic across many accounts — the realistic
  shape, measuring parallelism across independent locks.
- **`HotAccountContentionSimulation`** aims everything at a single account — the pathological
  shape, measuring the cost of the serialisation that the overdraft rule requires.

Both carry assertions (p95 latency, zero failures), so a regression fails the run rather than
producing a report nobody reads.

### Measured results

Apple Silicon (M-series, 14 cores), JDK 21, Docker Desktop with 4 CPUs. The service ran in a
container limited to `--cpus=3 --memory=2g`; the Gatling generator ran natively on the host.
One architecture at a time, so the two runs are not competing for the same cores.

Treat these as a comparison between shapes and architectures, not as a capacity plan.

**Native `linux/arm64`**

| Simulation | Requests | Throughput | p95 | p99 | max | Failures |
|---|---|---|---|---|---|---|
| Multi-account throughput | 230,265 | 2,558 req/s | 1 ms | 1 ms | 36 ms | 0 |
| Hot-account contention | 192,000 | 3,200 req/s | 1 ms | 1 ms | 8 ms | 0 |

**Emulated `linux/amd64`** (x86_64 image on an arm64 host)

| Simulation | Requests | Throughput | p95 | p99 | max | Failures |
|---|---|---|---|---|---|---|
| Multi-account throughput | 230,265 | 2,558 req/s | 1 ms | 2 ms | 250 ms | 0 |
| Hot-account contention | 192,000 | 3,200 req/s | 1 ms | 2 ms | 41 ms | 0 |

Three things are worth reading carefully here.

**Throughput is identical across all four runs, and that is not a finding.** Both simulations
use an open injection model at a fixed arrival rate, so requests per second is set by the
*profile*, not by the server. Identical rps means only that the service kept up with the
offered load in every configuration. Anyone reporting these numbers as "the service does 3,200
rps" would be quoting the load generator back at themselves. The server-side signal is
latency.

**The architecture difference shows up entirely in the tail.** Median and p95 are
indistinguishable — emulation is not on the hot path for a request that does a few hundred
nanoseconds of in-memory work. But p99 doubles (1 ms → 2 ms) and the maximum degrades sharply
(36 ms → 250 ms multi-account, 8 ms → 41 ms hot-account), with the standard deviation moving
from 0 to 2 ms. That is the shape of binary-translation overhead landing on the occasional
request: JIT compilation, GC pauses and page faults all cost more under emulation, and those
are exactly the events that produce outliers. Startup time moves the same way, 2 s native
versus 4 s emulated.

The practical conclusion: **build and run the native image for the platform you deploy on.**
The emulated image is correct and perfectly usable for a smoke test, and it is a poor basis for
a latency SLO.

**The hot-account run is still not slower than the multi-account run** on either architecture —
in fact its tail is *tighter*. The critical section is a few hundred nanoseconds of in-memory
work, so at these rates lock contention is nowhere near the limiting factor; the HTTP stack is.
The honest conclusion is that the per-account lock is not currently a bottleneck, not that it
could never be one. An account taking two orders of magnitude more traffic would tell a
different story, and that is the point at which sharding the aggregate would be worth its
complexity.

**Re-measured after idempotency keys were added**, since that check runs inside the same
critical section and on every write. Both simulations were re-run (230,265 and 192,000
requests, 0 failures, p95 and p99 unchanged at 1 ms). The table above is *not* restated from
that run: it was taken against a CPU-limited container (`--cpus=3 --memory=2g`) and the re-run
was host-native against a bare JVM, so the tail figures are not comparable and substituting
them would be quietly dishonest. What the re-run does establish is that the added work — a
single `HashMap` lookup and insert under a lock that was already held — is not measurable at
these rates, which is what one would expect and is now checked rather than assumed. The same
run also confirmed the ledger's central invariant end to end: after 96,000 concurrent
movements on one account, `availableBalance` equalled the sum of the full paged history exactly
and all 96,000 transaction identifiers were distinct.

### Reproducing the cross-architecture comparison

```bash
# Publish a multi-architecture image to a throwaway local registry, because a
# Docker daemon cannot hold a manifest list.
docker run -d --rm --name tl-registry -p 5001:5000 registry:2

./gradlew :app:build -x test -x bddTest -x integrationTest -x concurrencyTest -x e2eTest \
  -Dquarkus.profile=multiarch \
  -Dquarkus.container-image.push=true \
  -Dquarkus.container-image.registry=localhost:5001 \
  -Dquarkus.container-image.insecure=true

# Then, per architecture (arm64 | amd64):
docker run -d --rm --name tl-bench --platform linux/arm64 \
  --cpus=3 --memory=2g -p 18080:8080 localhost:5001/teya/tiny-ledger:1.0.0

./gradlew :load-tests:gatlingRun -Dtinyledger.baseUrl=http://localhost:18080 \
  --simulation=com.teya.tinyledger.loadtest.MultiAccountThroughputSimulation
```

## Concurrency across architectures

The concurrency tier is run on **both** architectures, and this is a correctness exercise
rather than a performance one.

`x86_64` has a strong memory model (Total Store Order); `aarch64` is weakly ordered and will
reorder stores that x86 never would. A missing `volatile` or an under-synchronised publication
is therefore a bug that can pass consistently on one architecture and fail on the other — and
"passes on the developer's laptop, corrupts balances in production" is precisely the failure
this project's use of a volatile snapshot is exposed to.

| Architecture | How | Tests | Result | Wall clock |
|---|---|---|---|---|
| `linux/arm64` | native | 10 | all pass | 1 m 58 s |
| `linux/amd64` | emulated | 10 | all pass | 3 m 28 s |

```bash
docker run --rm --platform linux/arm64 --cpus=3 --memory=4g \
  -u "$(id -u):$(id -g)" \
  -v "$PWD:/work" -v "$PWD/.gradle-arm64:/gradle-home" \
  -e GRADLE_USER_HOME=/gradle-home -w /work \
  eclipse-temurin:21-jdk \
  ./gradlew :app:concurrencyTest --no-daemon \
    -Dorg.gradle.project.buildDir=/work/build-arm64
```

One caveat, stated so the evidence is not oversold: the `amd64` run is *emulated*, and an
emulator reproduces the target's memory model only as faithfully as it chooses to. The arm64
result is the stronger of the two — passing on the weakly-ordered architecture is the harder
claim, and it is the one obtained natively here. A native x86 run in CI closes the remaining
gap, which is part of why the CI image job covers both platforms.

The load tests are **compiled but not executed** in CI. Latency measured on a shared runner is
noise, and a job that fails on noise trains everyone to ignore it.

---

## Naming

Every `@Test` and `@ParameterizedTest` method is named
`methodNameBeingTested_behaviourInTest_expectedResult` — three parts, two underscores, no
exceptions. A failure in CI prints the method name and nothing else, so the name has to answer
"what broke?" on its own:

```java
recordMovement_withdrawalExceedingOverdraft_throwsInsufficientFundsException
getHistory_limitExceedingMaximum_returnsBadRequest
recordMovement_concurrentWithdrawalsExceedingAllowance_enforcesExactLimitWithoutBreach
```

The first segment names the production method under test, which is what makes the convention
worth the verbosity: the tests covering a method can be found by searching for its name, and a
method with no tests is visible by its absence. `@DisplayName` still carries the readable prose
for reports — the two are complementary, and the method name is the one that survives being
read out of a stack trace.

Three kinds of method are deliberately exempt, because none of them is a test:

- **Lifecycle hooks** (`setUp`, `recordFiveMovements` under `@BeforeEach`) — these describe
  fixture construction, not an assertion.
- **Cucumber step definitions** in `bdd/LedgerSteps` — their names mirror the Gherkin step they
  bind to (`theAvailableBalanceIs`), and the feature file is the readable artefact there.
- **Private helpers** (`openAccount`, `deposit`) — plumbing.

## Test isolation

Two isolation properties are worth naming, because both were bugs before they were features.

**Ports.** Test instances bind an ephemeral port (`%test.quarkus.http.test-port=0`) instead of
the fixed default of 8081. A tier can therefore be run while a service is already running
locally, and two tiers can never collide. RestAssured reads the assigned port from Quarkus, so
nothing in the tests has to know the number.

**Shared build outputs.** Every Quarkus boot used to write `app/build/openapi/openapi.yaml`
(`quarkus.smallrye-openapi.store-schema-directory`). With Gradle parallel execution enabled,
two tiers boot at once, both write that path, and the build fails intermittently with
`FileAlreadyExistsException`. Nothing reads that file — and with `mp.openapi.scan.disable=true`
it is only ever a copy of the hand-written contract — so it is now switched off under the test
profile. Parallel execution is also left off; the reasoning is in
[`gradle.properties`](../gradle.properties).

The general lesson is worth keeping: test tiers that share a working directory are not isolated
just because they run in separate JVMs.

---

## What is deliberately not tested

- **Mutation testing** — would be the natural next step for the domain; the value objects are
  exactly the kind of code where line coverage flatters weak assertions.
- **Restart/persistence behaviour** — there is no persistence to test.
- **Multi-instance behaviour** — the service is single-node by construction
  ([ASSUMPTIONS.md](ASSUMPTIONS.md#consciously-out-of-scope)). A test suggesting otherwise would
  be worse than none.
