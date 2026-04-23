# AetherLedger — Architecture

## Overview

AetherLedger is a **modular monolith**: a single deployable unit partitioned internally into well-defined bounded contexts. Each context owns its domain logic, its entities, and its service layer. Controllers and repositories are context-scoped; nothing reaches across context boundaries through the database layer.

This structure was chosen deliberately over a microservice split for this stage of the project. The reasoning is covered at the bottom of this document.

---

## Bounded contexts

### Accounts

Manages the lifecycle of participant accounts — creation, lookup, and balance derivation.

**Key design decision:** account balances are never stored as a column. `currentBalance` is derived at read time by summing all `ledger_entries` rows for the account. This makes the balance impossible to diverge from the entry history — there is no separate field to go stale or be corrupted. The tradeoff is a per-request aggregate query, which is acceptable at current scale and can be addressed with a materialized view or CQRS read model when load requires it.

**Files:**
- `domain/entity/Account.java`
- `repository/AccountRepository.java`
- `service/AccountService.java`
- `api/AccountController.java`

---

### Ledger Transactions

The core of the system. Orchestrates the full lifecycle of a transaction from creation through settlement or rejection, and handles reversals.

**Two-phase flow:**

```
POST /ledger-transactions         → status = SUCCESS  (entries written immediately)
POST /ledger-transactions/pending → status = PENDING  (no entries written)
POST /{id}/complete               → PENDING → SUCCESS  (entries written now)
POST /{id}/fail                   → PENDING → FAILED   (no entries ever written)
```

**Reversal mechanics:** a reversal creates a new `SUCCESS` transaction with debit and credit accounts swapped, for the same amount. The original transaction is immutable. Linkage is bidirectional: `reversedByTransactionId` on the original, `reversalOfTransactionId` on the new transaction. A unique constraint on `reversedByTransactionId` enforces single-reversal-only at the database level.

**Concurrency model:** before any balance-affecting write, both account rows are locked with `SELECT ... FOR UPDATE`. To eliminate deadlocks when two concurrent transactions touch the same two accounts in opposite order, locks are always acquired in ascending UUID order — a global lock-acquisition sequence that prevents the circular-wait condition.

**Idempotency:** `referenceId` is a caller-supplied string that must be unique across all transactions. A duplicate returns `409 CONFLICT`. The guard has two layers:
1. Pre-flight `existsByReferenceId` check — fast path for the common case
2. `DataIntegrityViolationException` catch on the `UNIQUE` database constraint — covers the race window between check and insert

**Files:**
- `domain/entity/LedgerTransaction.java`
- `domain/entity/LedgerEntry.java`
- `repository/LedgerTransactionRepository.java`
- `repository/LedgerEntryRepository.java`
- `service/LedgerTransactionService.java`
- `service/command/PostTransactionCommand.java`
- `service/command/ReverseTransactionCommand.java`
- `api/LedgerTransactionController.java`

---

### Reconciliation

Verifies that internal ledger state matches what an external payment provider reports. Supports both single-transaction and batch (up to 500 items) reconciliation.

**Audit trail:** every `POST /reconciliation/batch` call creates an immutable `ReconciliationRun` record with one `ReconciliationRunItem` per input item. These records cannot be updated or deleted after creation — all JPA columns carry `updatable = false`. This satisfies the audit requirement that operators can replay exactly what was reconciled, when, and with what outcome.

**Reconciliation result logic** (computed at read time from `externalStatus` vs `status`):

| Internal status | External status | Result |
|---|---|---|
| `SUCCESS` | `SUCCESS` | `MATCHED` |
| `SUCCESS` | anything else | `STATUS_MISMATCH` |
| `PENDING` | `PENDING` | `MATCHED` |
| Any | `NOT_FOUND` or absent | `MISSING_EXTERNAL_REFERENCE` |
| No reconciliation attempted | — | `NOT_RECONCILED` |

**Files:**
- `domain/entity/ReconciliationRun.java`
- `domain/entity/ReconciliationRunItem.java`
- `repository/ReconciliationRunRepository.java`
- `service/ReconciliationService.java`
- `api/ReconciliationController.java`

---

### Operational Integrity

Provides aggregate health signals for the ledger without exposing internal entities directly. Intended for operators, alerting systems, and on-call engineers.

**Integrity checks:**
- `zeroSumViolationsCount` — counts `SUCCESS` transactions whose two entries do not sum to zero. Should always be 0; a non-zero value indicates application-layer corruption.
- `transactionsWithUnexpectedEntryCount` — counts `SUCCESS` transactions that do not have exactly 2 entries. Same implication.
- `healthy` — true only when both violation counts are 0.

**Drill-down endpoint:** `GET /api/v1/ops/integrity/ledger/violations` returns the specific transaction IDs driving an unhealthy report, so operators can investigate without a database console.

**Files:**
- `service/OpsService.java`
- `api/OpsController.java`

---

## Persistence strategy

### Schema ownership

Flyway owns the schema. The application never auto-generates DDL (`spring.jpa.hibernate.ddl-auto=validate`). Hibernate validates that entity mappings match the live schema on startup and fails fast if they do not — preventing silent schema drift.

### Entity design

| Entity | Mutable? | Notes |
|---|---|---|
| `Account` | Name/type only | Balance is derived, never stored |
| `LedgerTransaction` | Status, reconciliation fields | `@Version` for optimistic locking |
| `LedgerEntry` | Never | All columns `updatable = false` |
| `ReconciliationRun` | Never | All columns `updatable = false` |
| `ReconciliationRunItem` | Never | All columns `updatable = false` |

The `@Version` column on `LedgerTransaction` guards against lost updates when concurrent requests attempt to complete or fail the same pending transaction.

### Data types

- `NUMERIC(19,4)` for all monetary amounts — exact decimal, no floating-point rounding
- `UUID` primary keys generated by the application (not database sequences) — safe for future sharding, and the ascending-UUID lock ordering technique depends on them being comparable
- `TIMESTAMPTZ` for all timestamps — stored in UTC, returned as `Instant`

### Indexes

`V1__baseline_schema.sql` adds indexes on:
- `ledger_transactions(reference_id)` — unique, powers idempotency lookup
- `ledger_transactions(debit_account_id)`, `(credit_account_id)` — accelerate balance aggregation
- `ledger_entries(transaction_id)` — join path from transaction to entries
- `reconciliation_run_items(reference_id)` — batch lookup

---

## Flyway migration strategy

A single migration file (`V1__baseline_schema.sql`) defines the complete baseline schema: all 5 tables, all indexes, all CHECK constraints, and all foreign keys.

**Rationale for a single V1 baseline:** the project has not yet shipped to production, so there is no existing data to migrate around. A single comprehensive baseline is simpler to read and reason about than a chain of incremental migrations built up during development.

**Forward migration policy:** any future schema change gets its own numbered migration file (`V2__...`, `V3__...`). Migrations are append-only and never modified after being applied. Rollbacks, if needed, are handled by a separate down-migration file — not by editing the forward migration.

`spring.flyway.baseline-on-migrate` is `false` (default). The database must be empty on first run; Flyway creates its `flyway_schema_history` table and applies V1.

---

## Testcontainers testing strategy

The test suite has no mocks, no H2, and no in-memory substitutes. Every test runs against a real PostgreSQL 16 instance managed by Testcontainers.

### Container lifecycle

A single `PostgreSQLContainer` is started in a `static {}` initializer on `AbstractIntegrationTest`, the shared superclass for all test classes. Starting the container in a static initializer — rather than using `@Testcontainers` + `@Container` on the class — means the container is started once per JVM process and stopped via a Testcontainers-registered JVM shutdown hook when the process exits.

This is intentional. The `@Testcontainers` extension calls `afterAll()` after each test class, which stops a `@Container`-annotated static field and leaves all subsequent test classes with a dead connection. The static initializer approach avoids this entirely.

### Spring context reuse

Spring's test context cache reuses one `ApplicationContext` for all test classes that share the same configuration. Combined with the shared container, this means Flyway runs once, the schema is created once, and the application context is wired once per test run — not once per test class.

### Test isolation

Each test class cleans up its own data in `@BeforeEach`, deleting rows in foreign-key-safe order (entries before transactions, run items before runs, etc.). This is explicit and fast — no transaction rollback tricks, no full truncation of all tables between every test.

### Windows configuration

On Windows, Testcontainers must reach Docker Desktop's Linux engine via the named pipe `\\.\pipe\docker_engine_linux`. Two properties configure this:

```
docker.host=npipe:////./pipe/docker_engine_linux   # in ~/.testcontainers.properties
api.version=1.44                                    # passed as JVM system property via Maven Surefire
```

The `api.version` system property overrides the default version negotiation in the shaded `docker-java` library bundled inside the `testcontainers` JAR. Without it, the library sends API version 1.32, which is rejected by the Linux engine (minimum supported: 1.40).

---

## Why modular monolith over microservices

Splitting into microservices before the domain is stable and load demands it introduces costs that are hard to justify at this stage:

**Network calls introduce new failure modes.** The zero-sum invariant requires that ledger entries for a transaction are written atomically. In a monolith this is a local database transaction — the invariant is enforced by the database. Across service boundaries it requires distributed transactions or saga orchestration, both of which are significantly more complex and have different failure semantics.

**Shared schema is a strength here, not a liability.** The integrity ops endpoints work because a single query can scan the entire `ledger_entries` table and `ledger_transactions` table together. With separate services owning separate databases, this kind of cross-cutting health check requires an aggregation service or event-driven materialization.

**The bounded contexts are well-defined but not independently deployable.** Accounts, transactions, reconciliation, and ops all share the same PostgreSQL instance and operate over the same entities. Extracting them would require defining a data ownership boundary that does not yet exist naturally.

**The right time to split is when a specific context has a different scaling requirement or deployment lifecycle than the others.** When reconciliation, for example, needs to process millions of records independently and on a different schedule, it becomes a candidate for extraction. That decision can be made with real traffic data rather than speculation.

The modular monolith keeps the seams clean enough that extraction is feasible when the time comes — each bounded context already has its own service class, its own repository, and its own controller.
