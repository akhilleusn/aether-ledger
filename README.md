# AetherLedger

A production-grade **double-entry financial ledger** built with Java 17 and Spring Boot 3.  
Designed to model the core transaction, reconciliation, and audit capabilities found at fintech companies and payment processors.

---

## Why this project exists

Every payment company, neobank, and marketplace eventually needs a ledger: a system of record that tracks who owns what, enforces that money is never created or destroyed, and produces an auditable trail of every movement.

Off-the-shelf ledger systems are opaque, vendor-locked, or insufficiently flexible. AetherLedger models the core primitives from scratch:

- accounts that hold balances
- double-entry transactions that move value between them
- a two-phase flow for transactions requiring external confirmation
- reversals that correct mistakes without mutating history
- reconciliation that verifies the ledger against external payment providers
- operational integrity checks that detect data corruption in production

The design is directly informed by patterns used in production ledgers at companies like Stripe, Brex, and Shopify Pay.

---

## Core domain concepts

### Accounts

An account is a named participant in the ledger. Every movement of value is between two accounts.

| Type | Description |
|---|---|
| `USER` | An end-user's wallet or balance |
| `SYSTEM` | An internal float, escrow, or fee pool |
| `MERCHANT` | A payee that receives funds |

Account names are unique across the system. Account balances are derived at read time by summing all ledger entries — they are never stored as a column, which prevents the balance from ever becoming inconsistent with the entry history.

### Ledger transactions

A transaction is the unit of value transfer. It always involves exactly one debit account and one credit account. It carries a caller-supplied `referenceId` that acts as a globally unique idempotency key.

**Lifecycle states:**

```
PENDING ──► SUCCESS
PENDING ──► FAILED
```

| Status | Ledger entries written | Use case |
|---|---|---|
| `SUCCESS` | Yes — exactly 2 | Immediate settlement; reversal source |
| `PENDING` | No | Waiting for external confirmation |
| `FAILED` | No | Rejected after confirmation |

### Ledger entries

An entry is the atomic record of a single account's participation in a transaction. Two entries are always written together — one debit, one credit — and their amounts always sum to zero. Entries are strictly immutable after creation: they have no `@Version`, no update paths, and no delete paths.

**Sign convention:** positive amount = credit (value in), negative amount = debit (value out).

### Reversals

A reversal corrects a completed transaction by creating a new offsetting transaction — it does not mutate the original. The new transaction debits the original credit account and credits the original debit account for the same amount, netting the positions back to zero.

A transaction can only be reversed once (`reversedByTransactionId` has a UNIQUE constraint). Reversal linkage is bidirectional: the original records `reversedByTransactionId` and the reversal records `reversalOfTransactionId`.

### Reconciliation

Reconciliation is the process of verifying that internal ledger state matches what an external payment provider reports. After running a batch reconciliation, each transaction gets an `externalStatus` and a computed `reconciliationResult`:

| Result | Meaning |
|---|---|
| `NOT_RECONCILED` | No reconciliation attempted |
| `MATCHED` | Internal status and external status agree |
| `STATUS_MISMATCH` | Internal and external statuses disagree — requires investigation |
| `MISSING_EXTERNAL_REFERENCE` | Reconciled without providing external data |

Every `POST /reconciliation/batch` call creates an immutable `ReconciliationRun` audit record so operators can replay exactly what happened, when, and what the outcome was per item.

---

## Key business invariants

### Double-entry / zero-sum

Every `SUCCESS` transaction has exactly two ledger entries whose amounts sum to zero. This is enforced at three independent layers:

1. **Application layer** — `assertDoubleEntryInvariant()` runs before any `INSERT`
2. **Database layer** — a `CHECK (amount <> 0)` constraint on `ledger_entries`; the integrity report catches violations post-hoc
3. **Ops endpoint** — `GET /api/v1/ops/integrity/ledger` counts zero-sum violations across the entire ledger

### Append-only history

Ledger entries and reconciliation run records are write-once. All JPA columns on `LedgerEntry`, `ReconciliationRun`, and `ReconciliationRunItem` are marked `updatable = false`. Corrections are made exclusively through the reversal mechanism, which adds new entries rather than changing existing ones.

### Idempotency by referenceId

`referenceId` is a caller-supplied, globally unique idempotency key that must be provided for every transaction. Submitting the same `referenceId` twice returns `409 CONFLICT` rather than creating a duplicate. The implementation uses a two-layer guard: a pre-flight `existsByReferenceId` check (the fast path) and a `DataIntegrityViolationException` catch on the `UNIQUE` database constraint (the race-condition guard).

### Concurrency safety

Account rows are locked with `SELECT ... FOR UPDATE` before any balance-affecting write. To prevent deadlocks between concurrent transactions touching the same two accounts in opposite order, locks are always acquired in ascending UUID order — a standard technique for imposing a global lock-acquisition sequence.

---

## Main features

- **Immediate and two-phase transactions** — settle instantly or hold in PENDING until externally confirmed or rejected
- **Transactional reversals** — fully audited, bidirectionally linked, single-use
- **Batch reconciliation** — reconcile up to 500 transactions per call against external provider records; every run is persisted as an immutable audit trail
- **Reconciliation health report** — real-time aggregate statistics: matched, mismatched, not-reconciled, missing external reference
- **Ledger integrity checks** — detect zero-sum violations and unexpected entry counts across the entire ledger
- **Per-violation drill-down** — list the specific transactions that are driving an unhealthy integrity report
- **Paginated transaction list** — newest-first, configurable page size up to 100
- **Idempotent write operations** — safe to retry without risk of duplication
- **Webhook subscriptions and delivery tracking** — external systems subscribe to ledger domain events (`POST /api/v1/webhook-subscriptions`). The outbox relay dispatches a signed HTTP POST to each matching active subscriber after publishing each event, recording a `WebhookDelivery` row with status (`SUCCESS` / `FAILED`), attempt count, and error detail. Failure simulation via "fail" in the target URL; soft-delete via `DELETE /{id}`; delivery history preserved permanently
- **AI-ready reconciliation insights** — `GET /{id}/reconciliation-insight` returns an operator-facing explanation of any reconciliation anomaly with possible causes, recommended checks, a risk level (`LOW` / `MEDIUM` / `HIGH`), and an `insightSource` field (`RULE_BASED` or `AI`). The default engine is deterministic and requires no external calls; enable a real provider via `ai.insights.enabled=true` and `AI_INSIGHTS_API_KEY`. AI failures fall back to the rule-based engine silently — the endpoint never fails due to provider unavailability
- **Uniform error envelope** — every error returns `{ status, errorCode, message, timestamp, path }` with machine-readable `errorCode` values
- **OpenAPI / Swagger UI** — full API documentation with schema descriptions, example values, and error response shapes

---

## Tech stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.2.5 |
| Persistence | Spring Data JPA + Hibernate |
| Database | PostgreSQL 16 |
| Schema management | Flyway |
| API documentation | springdoc-openapi 2.5.0 (Swagger UI) |
| Build tool | Maven |
| Containerisation | Docker + Docker Compose |
| Test database | Testcontainers (PostgreSQL 16-alpine) |
| Locking | Pessimistic write locks (JPA `PESSIMISTIC_WRITE`) |
| Concurrency safety | `@Version` optimistic locking on `LedgerTransaction` |
| CI | GitHub Actions |

---

## Running locally

### Option A — Docker Compose (recommended)

Requires Docker Desktop or any Docker Engine with Compose v2.

```bash
# Optional: set credentials (defaults to postgres/postgres)
cp .env.example .env
# edit .env with your preferred DB_USERNAME and DB_PASSWORD

docker compose up --build
```

Flyway runs automatically on startup. The application is available at `http://localhost:8080`.

**Swagger UI:** `http://localhost:8080/swagger-ui.html`

To stop and remove volumes:
```bash
docker compose down -v
```

---

### Option B — Maven + local PostgreSQL

**Prerequisites:** Java 17+, Maven 3.8+, PostgreSQL 16

```bash
export DB_URL=jdbc:postgresql://localhost:5432/aetherledger
export DB_USERNAME=postgres
export DB_PASSWORD=yourpassword

mvn spring-boot:run
```

Flyway applies all migrations automatically on first boot.

---

## Running the tests

The test suite uses **Testcontainers** to run all integration tests against a real PostgreSQL instance. Docker must be running before executing tests.

```bash
mvn test
```

All 156 tests are full integration tests — no mocks, no H2, no in-memory substitutes. Flyway applies every migration against the container before any test run, so the schema is byte-for-byte identical to production.

**Test isolation:** each test class cleans up its own data in `@BeforeEach` in foreign-key-safe order. A single PostgreSQL container is shared across all test classes within a run, and Spring's context cache reuses one application context, keeping the suite fast.

> **Windows note:** the Surefire plugin is pre-configured to connect Testcontainers to Docker Desktop's Linux engine via the named pipe `\\.\pipe\docker_engine_linux`. On Linux and macOS, tests run without any additional configuration.

---

## API quick reference

### Create an account

```bash
curl -X POST http://localhost:8080/api/v1/accounts \
  -H "Content-Type: application/json" \
  -d '{"name": "Alice Wallet", "type": "USER"}'
```

### Post an immediate transaction

Debits Alice, credits Bob. Ledger entries are written immediately.

```bash
curl -X POST http://localhost:8080/api/v1/ledger-transactions \
  -H "Content-Type: application/json" \
  -d '{
    "debitAccountId":  "<alice-account-id>",
    "creditAccountId": "<bob-account-id>",
    "amount":          "150.00",
    "referenceId":     "PAY-2024-001"
  }'
```

### Create a pending transaction

Captures intent without writing ledger entries. Status: `PENDING`.

```bash
curl -X POST http://localhost:8080/api/v1/ledger-transactions/pending \
  -H "Content-Type: application/json" \
  -d '{
    "debitAccountId":  "<alice-account-id>",
    "creditAccountId": "<merchant-account-id>",
    "amount":          "75.00",
    "referenceId":     "PEND-2024-001"
  }'
```

### Complete a pending transaction

Writes ledger entries and transitions status to `SUCCESS`.

```bash
curl -X POST http://localhost:8080/api/v1/ledger-transactions/<transaction-id>/complete
```

### Fail a pending transaction

Rejects the transaction. No ledger entries written. Status: `FAILED`.

```bash
curl -X POST http://localhost:8080/api/v1/ledger-transactions/<transaction-id>/fail
```

### Reverse a transaction

Creates a new offsetting transaction. The original is immutable.

```bash
curl -X POST http://localhost:8080/api/v1/ledger-transactions/<transaction-id>/reversal \
  -H "Content-Type: application/json" \
  -d '{"referenceId": "REV-PAY-2024-001"}'
```

### Reconcile a transaction against an external record

```bash
curl -X POST http://localhost:8080/api/v1/ledger-transactions/<transaction-id>/reconcile \
  -H "Content-Type: application/json" \
  -d '{
    "externalReferenceId": "EXT-TXN-987654",
    "externalStatus":      "SUCCESS"
  }'
```

### Batch reconcile

Matches up to 500 provider records against internal transactions in a single call.

```bash
curl -X POST http://localhost:8080/api/v1/reconciliation/batch \
  -H "Content-Type: application/json" \
  -d '{
    "items": [
      {"referenceId": "PAY-2024-001", "externalReferenceId": "EXT-001", "externalStatus": "SUCCESS"},
      {"referenceId": "PAY-2024-002", "externalReferenceId": "EXT-002", "externalStatus": "FAILED"},
      {"referenceId": "PAY-2024-003", "externalStatus": "NOT_FOUND"}
    ]
  }'
```

### Ledger integrity report

```bash
curl http://localhost:8080/api/v1/ops/integrity/ledger
```

```json
{
  "totalTransactions": 1248,
  "successfulTransactions": 1190,
  "pendingTransactions": 45,
  "failedTransactions": 13,
  "reversedTransactionsCount": 7,
  "zeroSumViolationsCount": 0,
  "transactionsWithUnexpectedEntryCount": 0,
  "healthy": true
}
```

### Reconciliation health

```bash
curl http://localhost:8080/api/v1/ops/integrity/reconciliation
```

---

## Project structure

```
src/main/java/com/aetherledger/
│
├── api/                          # HTTP layer — controllers and DTOs
│   ├── AccountController
│   ├── LedgerTransactionController
│   ├── ReconciliationController
│   ├── OpsController             # Integrity and health endpoints
│   ├── GlobalExceptionHandler    # Unified error mapping
│   └── dto/                      # Request/response record types
│
├── config/
│   └── OpenApiConfig             # API title, description, contact
│
├── domain/
│   ├── entity/                   # JPA entities
│   │   ├── Account
│   │   ├── LedgerTransaction
│   │   ├── LedgerEntry
│   │   ├── ReconciliationRun
│   │   ├── ReconciliationRunItem
│   │   └── OutboxEvent           # Transactional outbox record
│   └── enums/                    # AccountType, TransactionStatus,
│                                 # ExternalStatus, ReconciliationResult,
│                                 # OutboxEventType
│
├── exception/                    # Typed domain exceptions
│                                 # (all extend LedgerException)
│
├── repository/                   # Spring Data repositories with custom
│                                 # JPQL, pessimistic locks, and aggregates
│
└── service/                      # Business logic
    ├── AccountService
    ├── LedgerTransactionService  # Core transaction orchestration
    ├── ReconciliationService     # Batch reconciliation + audit
    ├── ScheduledReconciliationService  # Cron-driven auto-reconciliation
    ├── OpsService                # Integrity aggregates
    ├── OutboxService             # Writes outbox rows inside transactions
    ├── OutboxEventPublisher      # Port interface for broker delivery
    ├── LoggingOutboxEventPublisher  # Simulated adapter (swap for Kafka)
    ├── OutboxRelayService        # Relay loop — fetch → publish → mark
    ├── OutboxRelayProcessor      # Per-event REQUIRES_NEW transaction
    ├── OutboxRelayJob            # @Scheduled relay trigger
    └── command/                  # PostTransactionCommand
                                  # ReverseTransactionCommand

src/main/resources/
├── application.properties
└── db/migration/
    ├── V1__baseline_schema.sql   # Accounts, transactions, entries, FKs
    ├── V2__reconciliation.sql    # Reconciliation run tables
    ├── V3__create_outbox_events.sql  # Outbox table + partial index
    └── V4__add_outbox_published_at.sql  # published_at column

.github/workflows/
└── ci.yml                        # GitHub Actions: test on every push
Dockerfile                        # Multi-stage build (Maven → JRE)
docker-compose.yml                # postgres + app, healthcheck, volumes
```

---

## Future improvements

- **AI provider integration** — set `ai.insights.enabled=true`, `ai.insights.provider=claude` (or `openai`), and supply `AI_INSIGHTS_API_KEY` via env var. Implement `AiInsightGenerator.tryAiGenerate()` with the real provider call; the fallback and response contract stay unchanged
- **Authentication and authorization** — JWT-based auth with per-account access control
- **Multi-currency support** — ISO 4217 currency codes on transactions and entries, FX rate ledger
- **Cursor-based pagination** — replace offset pagination with keyset pagination for large ledgers
- **Read replica routing** — route `@Transactional(readOnly = true)` queries to a read replica
- **Metrics and alerting** — Micrometer + Prometheus gauges for zero-sum violation count, pending transaction backlog, and reconciliation match rate
- **Archive/cold storage** — partition `ledger_entries` by month and move old partitions to cheaper storage

---

## Why this is a strong portfolio project for fintech/backend roles

**Real domain complexity.** Double-entry bookkeeping, idempotency keys, pessimistic locking, and reconciliation are patterns that appear in production systems at every payments company. Knowing how to implement them correctly — and why they exist — signals genuine understanding of the domain.

**Production engineering decisions, not tutorial choices.** Flyway over `ddl-auto=create`, `SELECT ... FOR UPDATE` over application-level locks, `NUMERIC(19,4)` over `DOUBLE`, `Instant` over `Date`, Testcontainers over H2 — each decision has a concrete production justification.

**Append-only audit design.** Financial systems require tamper-proof history. The approach here — `updatable = false` on every column, reversals as new records, immutable reconciliation run items — mirrors the audit requirements in SOX-regulated environments.

**Concurrency correctness.** The deadlock-prevention strategy (ascending UUID lock order) and the double-layer idempotency guard (pre-flight check + constraint catch) are exactly the problems that bite engineering teams in production and rarely appear in standard tutorials.

**Operational visibility.** The integrity and reconciliation health endpoints exist because production ledgers can silently accumulate inconsistencies — a corrupt entry count, a mismatched reconciliation status — that are only caught if someone is actively looking. Building the detection tooling alongside the feature code is a production mindset.
