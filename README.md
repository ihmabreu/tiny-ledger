# Tiny Ledger

A small ledger service that records money movements, reports balances and serves
transaction history over a REST API.

Built with **Java 21** and **Quarkus 3.33 LTS**, API-first from a hand-written OpenAPI
contract, served on **virtual threads**, and verified by six distinct test tiers.

- The contract: [`app/src/main/resources/META-INF/openapi.yaml`](app/src/main/resources/META-INF/openapi.yaml)
- The assumptions behind the model: [`docs/ASSUMPTIONS.md`](docs/ASSUMPTIONS.md)
- How it is put together: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- How it behaves under contention: [`docs/CONCURRENCY.md`](docs/CONCURRENCY.md)
- What each test tier is for: [`docs/TESTING.md`](docs/TESTING.md)
- How the API evolves: [`docs/API_VERSIONING.md`](docs/API_VERSIONING.md)
- How the image is built and run, on both architectures:
  [`docs/CONTAINERISATION.md`](docs/CONTAINERISATION.md)

---

## Quick start

Requirements: **JDK 21**. Nothing else — the Gradle wrapper (9.7.1) is committed, and the
service holds all state in memory, so there is no database to install or start.

```bash
./gradlew :app:quarkusDev
```

The service listens on <http://localhost:8080>. Interactive API documentation is at
<http://localhost:8080/q/swagger-ui>, and the raw contract at
<http://localhost:8080/q/openapi>.

Three demo accounts are seeded on startup so the API is explorable immediately. Seeding is
disabled automatically in every test profile; to turn it off locally, run with
`-Dledger.seed.enabled=false`.

| Account       | Currency | Overdraft allowance | Movements seeded            |
|---------------|----------|---------------------|-----------------------------|
| Ada Lovelace  | EUR      | 0.00                | +1200.00, −49.99, −200.00   |
| Grace Hopper  | GBP      | 500.00              | +80.00, −300.00             |
| Alan Turing   | EUR      | 0.00                | none                        |

---

## The API

All endpoints live under `/api/v1`. Monetary amounts are JSON **strings** (`"1200.00"`), never
JSON numbers — see [Money is a string on the wire](#money-is-a-string-on-the-wire).

| Method | Path                                          | Purpose                             |
|--------|-----------------------------------------------|-------------------------------------|
| `POST` | `/api/v1/accounts`                            | Open an account                     |
| `GET`  | `/api/v1/accounts`                            | List accounts                       |
| `GET`  | `/api/v1/accounts/{accountId}`                | Fetch one account                   |
| `GET`  | `/api/v1/accounts/{accountId}/balance`        | Read the current balance            |
| `POST` | `/api/v1/accounts/{accountId}/transactions`   | Record a deposit or a withdrawal    |
| `GET`  | `/api/v1/accounts/{accountId}/transactions`   | Read the transaction history        |

### Walking through it with curl

Open an account. The currency is fixed here and can never change afterwards; the overdraft
allowance is optional and defaults to zero. A new account starts empty — it is funded by
recording a deposit, never by setting a balance field.

```bash
curl -s -X POST http://localhost:8080/api/v1/accounts \
  -H 'Content-Type: application/json' \
  -d '{
        "ownerName": "Katherine Johnson",
        "currency": "EUR",
        "overdraftLimit": "200.00"
      }'
```

```json
{
  "id": "8a39b1ce-3d4b-4802-a3bc-3b9ca5d59c50",
  "ownerName": "Katherine Johnson",
  "currency": "EUR",
  "overdraftLimit": "200.00",
  "availableBalance": "0.00",
  "accountBalance": "200.00",
  "openedAt": "2026-09-22T16:26:03.975903Z"
}
```

Keep the `id` for the calls below:

```bash
ACCOUNT=8a39b1ce-3d4b-4802-a3bc-3b9ca5d59c50
```

Record a deposit. Two fields and one header deserve a note:

- `currency` is mandatory — stating it explicitly lets the ledger reject a movement aimed at
  the wrong account rather than silently assuming the caller meant EUR.
- `occurredAt` is mandatory — it is *your* record of when the movement happened. The ledger
  stores it and hands it back, but never uses it for the balance or the order of the
  statement; those follow the ledger's own `recordedAt` clock. See
  [Two timestamps](#two-timestamps).
- `Idempotency-Key` header is mandatory — it lets you retry a request safely if the response is
  lost. See [Retrying safely](#retrying-safely) below.

```bash
curl -s -X POST http://localhost:8080/api/v1/accounts/$ACCOUNT/transactions \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 3f29b6b0-6e0a-4b8e-9f0a-1a2b3c4d5e6f' \
  -d "{\"type\": \"DEPOSIT\", \"amount\": \"1000.00\", \"currency\": \"EUR\",
       \"reference\": \"Salary\", \"occurredAt\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}"
```

```json
{
  "id": "6153a940-7416-4dca-9516-1022c771a132",
  "accountId": "8a39b1ce-3d4b-4802-a3bc-3b9ca5d59c50",
  "type": "DEPOSIT",
  "amount": "1000.00",
  "currency": "EUR",
  "availableBalanceAfter": "1000.00",
  "reference": "Salary",
  "occurredAt": "2026-09-22T16:26:09Z",
  "recordedAt": "2026-09-22T16:26:10.533677Z"
}
```

Record a withdrawal:

```bash
curl -s -X POST http://localhost:8080/api/v1/accounts/$ACCOUNT/transactions \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 9d3e2a1c-5b7f-4e6d-8a90-2c1b0f9e8d7c' \
  -d "{\"type\": \"WITHDRAWAL\", \"amount\": \"120.50\", \"currency\": \"EUR\",
       \"reference\": \"Groceries\", \"occurredAt\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}"
```

Read the balance. Two figures are reported, and they mean different things — see
[Two balances](#two-balances).

```bash
curl -s http://localhost:8080/api/v1/accounts/$ACCOUNT/balance
```

```json
{
  "accountId": "8a39b1ce-3d4b-4802-a3bc-3b9ca5d59c50",
  "currency": "EUR",
  "availableBalance": "879.50",
  "accountBalance": "1079.50",
  "overdraftLimit": "200.00",
  "transactionCount": 2,
  "calculatedAt": "2026-09-22T16:26:10.572695Z"
}
```

Read the history. It is paginated and ordered most-recent-first:

```bash
curl -s "http://localhost:8080/api/v1/accounts/$ACCOUNT/transactions?limit=10&offset=0"
```

```json
{
  "accountId": "8a39b1ce-3d4b-4802-a3bc-3b9ca5d59c50",
  "transactions": [
    { "id": "ff10c65d-...", "accountId": "8a39b1ce-...", "type": "WITHDRAWAL",
      "amount": "120.50", "currency": "EUR", "availableBalanceAfter": "879.50",
      "reference": "Groceries", "occurredAt": "2026-09-22T16:26:10Z",
      "recordedAt": "2026-09-22T16:26:10.572695Z" },
    { "id": "6153a940-...", "accountId": "8a39b1ce-...", "type": "DEPOSIT",
      "amount": "1000.00", "currency": "EUR", "availableBalanceAfter": "1000.00",
      "reference": "Salary", "occurredAt": "2026-09-22T16:26:09Z",
      "recordedAt": "2026-09-22T16:26:10.533677Z" }
  ],
  "limit": 10,
  "offset": 0,
  "total": 2
}
```

Each entry carries `availableBalanceAfter`, the running balance immediately after that
movement was applied. It turns the history into a statement that can be audited line by line
without re-adding the whole ledger.

Try to spend past the overdraft allowance and the request is refused:

```bash
curl -s -X POST http://localhost:8080/api/v1/accounts/$ACCOUNT/transactions \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 7a6b5c4d-3e2f-410a-9b8c-7d6e5f4a3b2c' \
  -d "{\"type\": \"WITHDRAWAL\", \"amount\": \"99999.00\", \"currency\": \"EUR\",
       \"occurredAt\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}"
```

```json
{
  "code": "INSUFFICIENT_FUNDS",
  "message": "Withdrawal of 99999.00 EUR would exceed the funds available on account 8a39b1ce-... (available balance 879.50 EUR, overdraft limit 200.00 EUR)",
  "timestamp": "2026-09-22T16:26:10.912681Z"
}
```

Send a movement in the wrong currency and it is refused too, with `CURRENCY_MISMATCH`.

### Retrying safely

`POST /transactions` requires an `Idempotency-Key` header — an opaque, caller-generated string
(a UUID is the natural choice). If a response is lost to a timeout or a dropped connection, the
client does not know whether the movement was applied; retrying an ordinary `POST` unprotected
would risk recording it twice. Reusing the *same* key on the retry makes this safe:

```bash
# First attempt — the response never arrives (simulated: pretend the connection dropped here)
curl -s -X POST http://localhost:8080/api/v1/accounts/$ACCOUNT/transactions \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: c2d1e0f9-8a7b-46c5-9d4e-3f2a1b0c9d8e' \
  -d "{\"type\": \"DEPOSIT\", \"amount\": \"50.00\", \"currency\": \"EUR\",
       \"reference\": \"Refund\", \"occurredAt\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}"

# Retry with the identical key: the original 201 response comes back, unchanged; the balance
# does not move a second time.
curl -s -X POST http://localhost:8080/api/v1/accounts/$ACCOUNT/transactions \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: c2d1e0f9-8a7b-46c5-9d4e-3f2a1b0c9d8e' \
  -d "{\"type\": \"DEPOSIT\", \"amount\": \"50.00\", \"currency\": \"EUR\",
       \"reference\": \"Refund\", \"occurredAt\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}"
```

Reusing a key for a *different* `type` or `amount` is refused with `409 Conflict` /
`IDEMPOTENCY_KEY_REUSED`, since that means the key was reused for two distinct movements — a
client bug rather than a safe retry. See [ASSUMPTIONS.md](docs/ASSUMPTIONS.md) for the full
design, including why a movement refused for insufficient funds does not consume its key.

### What the status codes mean

| Status | Meaning                                                                       |
|--------|-------------------------------------------------------------------------------|
| `200`  | Read succeeded                                                                |
| `201`  | Account opened, or transaction recorded (including a replayed `Idempotency-Key`) |
| `400`  | The request is malformed — bad currency code, non-positive amount, bad `limit`, missing `Idempotency-Key`, missing or implausible `occurredAt` |
| `404`  | No such account                                                               |
| `409`  | The `Idempotency-Key` was reused for a movement with a different `type` or `amount` |
| `422`  | The request is well-formed but a business rule rejected it — insufficient funds, currency mismatch |
| `500`  | Unexpected failure                                                            |

The distinction between `400` and `422` is deliberate: `400` says *you sent nonsense*, `422`
says *you sent something sensible that the ledger will not do*. A client can retry a `422`
after depositing funds; retrying a `400` unchanged is pointless.

Every error shares one shape:

```json
{
  "code": "ACCOUNT_NOT_FOUND",
  "message": "No account exists with id 00000000-0000-0000-0000-000000000000",
  "timestamp": "2026-09-22T16:26:04.141979Z"
}
```

`details` is added when a single message is not enough — a payload that fails bean validation
returns one entry per violated field.

---

## Two balances

Most ledgers that support an overdraft eventually confuse "money I have" with "money I can
spend". This one keeps them apart and reports both:

- **`availableBalance`** — the sum of every transaction on the account. This is real money
  moved in and out, nothing else. The invariant `availableBalance == sum(transactions)` holds
  at all times and is asserted directly by the concurrency tests.
- **`accountBalance`** — `availableBalance + overdraftLimit`. This is total spending power.

The overdraft allowance is **account metadata, not a transaction**. It is a credit limit the
bank extends, not money that was deposited, so writing it into the ledger would corrupt the
history and break the invariant above. A withdrawal is accepted when
`availableBalance − amount >= −overdraftLimit`, which is the same as saying `accountBalance`
must not go negative.

There is deliberately no "opening balance" field on account creation. Money enters an account
the same way it always does — as a `DEPOSIT` that appears in the history like any other — so
the invariant holds from the very first second of the account's life.

## Two timestamps

Every movement carries two times, and only one of them is trusted:

| | `recordedAt` | `occurredAt` |
|---|---|---|
| Set by | the ledger | you, the caller |
| Means | when the movement was booked | when you say it happened |
| Drives the balance and the order of the statement | **yes** | **never** |

`occurredAt` is required on every `POST /transactions`. The ledger stores it, returns it
unchanged, and otherwise ignores it — the running balance, the overdraft decision and the
order of the history all follow `recordedAt` and an internal per-account sequence.

That is not an oversight, it is the point. A client clock cannot be relied on: it drifts, it
gets time zones wrong, and over an API it can simply be *asserted* — nothing stops a caller
sending whatever instant suits them. If client time drove the history, a caller could slot a
movement into the middle of their own statement and change every running balance after it. A
ledger that can be rewritten after the fact is not a ledger.

It is still worth recording, because it answers something booking time cannot: *when did this
happen to the customer?* A terminal that was offline for an hour, an app retrying from a
tunnel, an overnight batch — in each case the booking time is when the ledger heard about the
movement, not when it happened.

It is sanity-checked rather than trusted. A value more than **24 hours before** or **5 minutes
after** the booking time is refused with `400`:

```bash
curl -s -X POST http://localhost:8080/api/v1/accounts/$ACCOUNT/transactions \
  -H 'Content-Type: application/json' \
  -d '{"type": "DEPOSIT", "amount": "10.00", "currency": "EUR",
       "occurredAt": "2019-01-01T00:00:00Z"}'
```

```json
{
  "code": "VALIDATION_FAILED",
  "message": "occurredAt 2019-01-01T00:00:00Z is more than 24h before the ledger's own clock (2026-09-24T18:31:02.184Z). A movement this old cannot be accepted; check the client's clock.",
  "timestamp": "2026-09-24T18:31:02.186Z"
}
```

The window is asymmetric on purpose. Arriving late is ordinary — a queued retry, a reconnecting
terminal — whereas a movement claiming to have happened in the future is never legitimate, so
the only tolerance needed ahead of the clock is for ordinary client/server skew. A rejected
movement changes nothing: the check runs before anything is appended, so the balance and
history are left exactly as they were.

Full reasoning, including why the field is required rather than optional, is in
[ASSUMPTIONS.md](docs/ASSUMPTIONS.md#time).

## Money is a string on the wire

Amounts are serialised as JSON strings. JSON numbers are IEEE-754 doubles in most parsers,
and `0.1 + 0.2 != 0.3` is not a property anyone wants in a ledger. Internally every amount is
a `BigDecimal` scaled to the currency's minor unit, taken from `java.util.Currency` rather
than hard-coded to two digits — so JPY is scaled to 0 decimals and EUR to 2 without special
cases.

---

## Building and testing

```bash
./gradlew build          # compiles, then runs every test tier
```

Each tier can be run on its own:

```bash
./gradlew :app:test             # unit            — isolated domain/service logic
./gradlew :app:integrationTest  # integration     — HTTP + OpenAPI contract conformance
./gradlew :app:bddTest          # bdd             — Gherkin acceptance scenarios
./gradlew :app:e2eTest          # e2e             — black box against the packaged jar
./gradlew :app:concurrencyTest  # concurrency     — virtual-thread stress tests
```

The tiers are separated by JUnit 5 tags and each runs in its own JVM, because `@QuarkusTest`
and `@QuarkusIntegrationTest` classes must never share one. What each tier is *for* — and how
the overlap between them is kept deliberate rather than accidental — is set out in
[`docs/TESTING.md`](docs/TESTING.md).

Test instances bind an ephemeral port (`%test.quarkus.http.test-port=0`) rather than the
default 8081, so a tier can be run while a service is already running locally, and two tiers
can never collide on a port. RestAssured picks the assigned port up on its own.

#### Build configuration

[`gradle.properties`](gradle.properties) turns on the two caches that are worth having:

| Setting | Effect |
|---|---|
| `org.gradle.caching` | Task outputs are reused across builds, and across CI runs via `setup-gradle`. A `clean build` that changes nothing drops from ~16s to ~1s. |
| `org.gradle.configuration-cache` | The configuration phase is skipped when its inputs are unchanged. |

`org.gradle.parallel` is deliberately left **off**. It was tried and measured at 16s either
way — there are two subprojects and the critical path is the `:app` test tiers regardless —
and it lets the Quarkus test tiers overlap on one `app/build` directory, which is how an
intermittent `FileAlreadyExistsException` was found. The reasoning is recorded in the file
itself so it does not get re-added on a hunch.

Coverage (JaCoCo) is written to `app/build/reports/jacoco/test/html/index.html` after
`./gradlew :app:test`.

Javadoc:

```bash
./gradlew :app:javadoc   # app/build/docs/javadoc/index.html
```

### Load and performance tests

Gatling simulations live in their own Gradle module. They run against a *running* service, so
start one first:

```bash
./gradlew :app:build -x test -x bddTest -x integrationTest -x concurrencyTest -x e2eTest
java -jar app/build/quarkus-app/quarkus-run.jar &

./gradlew :load-tests:gatlingRun \
  --simulation=com.teya.tinyledger.loadtest.MultiAccountThroughputSimulation

./gradlew :load-tests:gatlingRun \
  --simulation=com.teya.tinyledger.loadtest.HotAccountContentionSimulation
```

Point them elsewhere with `-Dtinyledger.baseUrl=http://host:port`. Reports land in
`load-tests/build/reports/gatling/`. Measured results are in
[`docs/TESTING.md`](docs/TESTING.md#measured-results).

---

## Running as a container

```bash
./gradlew :app:build -Dquarkus.container-image.build=true
docker run --rm -p 8080:8080 teya/tiny-ledger:1.0.0
```

The image is built by **Jib**, which assembles layers directly and therefore needs no
Dockerfile and no QEMU emulation to target a different architecture. Jib defaults to
`linux/amd64`; add `-Dquarkus.jib.platforms=linux/arm64` to build for an arm64 host. A local
build produces one architecture, because the Docker daemon cannot hold a multi-architecture
manifest list — the combined amd64 + arm64 manifest is produced when pushing to a registry, and
CI verifies that against a throwaway `registry:2` container.

Both architectures are built, started and exercised in CI, and every test tier passes on both.
Performance, however, is not portable: under emulation the latency tail degrades noticeably
while the median does not. Full instructions, the local verification recipe and the measured
comparison are in [`docs/CONTAINERISATION.md`](docs/CONTAINERISATION.md) and
[`docs/TESTING.md`](docs/TESTING.md#measured-results).

---

## Project layout

```
app/                     the service
  src/main/java/com/teya/tinyledger/
    domain/              Money, Account, Transaction, BalanceSnapshot, OverdraftPolicy
    domain/exception/    the business-rule failures the service can raise
    repository/          AccountRepository + the in-memory implementation
    service/             LedgerService + the default implementation
    api/                 JAX-RS resources, DTOs, mappers, exception mappers
    config/              clock producer, demo-data seeder
  src/main/resources/
    META-INF/openapi.yaml   the contract — the source of truth, served verbatim
  src/test/java/...      unit, integration, bdd, e2e, concurrency tiers
load-tests/              Gatling simulations (separate module, separate dependency set)
docs/                    architecture, assumptions, concurrency, testing, versioning
.github/workflows/ci.yml every tier, plus amd64 + arm64 image build and smoke test
```

## What is deliberately not here

No authentication, no persistence, no database transactions, no logging or monitoring stack.
The assessment brief excludes them, and adding them would obscure the part that matters. Where
those seams would go — and what would change if they were added — is covered in
[`docs/ASSUMPTIONS.md`](docs/ASSUMPTIONS.md) and
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
