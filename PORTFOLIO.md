# AetherLedger — Portfolio Brief

A production-grade **payment ledger and reconciliation API** built with Java 17 and Spring Boot.
Designed to demonstrate the backend engineering patterns that appear in real fintech systems
— not as a tutorial, but as a ground-up implementation of the hard parts.

---

## 1. What problem it solves

Every payment company, neobank, and marketplace eventually builds a ledger: a system that tracks
who owns what, ensures money is never created or destroyed, and produces an auditable trail of
every movement.

AetherLedger implements the core primitives of that system:

- **Accounts** — named participants (USER, SYSTEM, MERCHANT)
- **Ledger transactions** — atomic double-entry movements between accounts
- **Reconciliation** — verifying internal state against external payment provider records
- **Holds** — reserving funds without moving them (pre-auth pattern)
- **Audit chain** — cryptographic proof that the event history has not been tampered with
- **Webhooks** — reliable event delivery to downstream consumers

---

## 2. Why this is harder than a CRUD app

| CRUD app concern | AetherLedger concern |
|---|---|
| Save a record | Atomically write two ledger entries that sum to zero |
| Update a row | Never update — model corrections as new reversals |
| Delete a record | Append-only: history is immutable by design |
| Handle duplicate requests | Two-layer idempotency guard (pre-flight + constraint) |
| Read a balance | Derive at read-time from entry sums — no stored balance |
| Parallel requests | Pessimistic locks in consistent UUID order to prevent deadlocks |
| External data mismatch | Batch reconciliation with per-item audit records |
| Detecting data corruption | Live integrity reports + immutable point-in-time snapshots |
| Tamper evidence | SHA-256 hash chain across all significant events |

---

## 3. Main architecture

```
HTTP layer (REST + OpenAPI)
        │
        ▼
Service layer  ──► OutboxService ──► ledger_audit_chain
        │                └──► outbox_events (transactional outbox)
        │                           │
        ▼                           ▼
Spring Data JPA (Hibernate)    OutboxRelayJob ──► WebhookDeliveryService
        │                                                  │
        ▼                                                  ▼
PostgreSQL 16 (schema owned by Flyway)            webhook_deliveries / DLQ
```

**Tech stack:**

| Layer | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.5 |
| Persistence | Spring Data JPA + Hibernate, Flyway |
| Database | PostgreSQL 16 |
| API docs | springdoc-openapi (Swagger UI) |
| Testing | JUnit 5 + Testcontainers + MockMvc |
| CI | GitHub Actions |
| Security scanning | Gitleaks (secrets) + Trivy (CVEs) |

---

## 4. Core flows

### 4.1 Create an account

```
POST /api/v1/accounts
  → validate name uniqueness (DB UNIQUE constraint)
  → persist Account with @Version optimistic lock guard
  → return 201 with account UUID
```

Balance is **never stored**. It is computed on read from the sum of all `ledger_entries.amount`
for that account. This makes it impossible for the stored balance to diverge from the history.

---

### 4.2 Post a ledger transaction (immediate)

```
POST /api/v1/ledger-transactions
  1. Pre-flight referenceId check (fast path)
  2. Lock both accounts: SELECT … FOR UPDATE in ascending UUID order
       → prevents the AB/BA deadlock when two transactions touch the same pair
  3. Create LedgerTransaction (status = SUCCESS)
  4. Create two LedgerEntry rows: debit (negative) + credit (positive)
  5. Assert zero-sum invariant in application code (third: DB CHECK constraint)
  6. Persist atomically (cascade)
  7. Write OutboxEvent in the same transaction
  8. Append audit chain entry (SHA-256 hash of event fields chained to previous)
  → return 201 with transaction UUID
```

Duplicate `referenceId` → 409 CONFLICT (caught at both pre-flight and constraint level).

---

### 4.3 Reverse a transaction

```
POST /api/v1/ledger-transactions/{id}/reversal
  1. Lock original transaction (SELECT … FOR UPDATE)
  2. Validate: status must be SUCCESS, not already reversed
  3. Create a new LedgerTransaction (reversalOfTransactionId = original.id)
  4. Flip debit ↔ credit accounts; write two new entries
  5. Link original.reversedByTransactionId = reversal.id (UNIQUE — prevents double reversal)
  6. Persist both in one atomic commit
  7. Write OutboxEvent + audit chain entry
```

The original transaction is **never mutated**. Both sides of the reversal linkage carry
bidirectional references for full audit traceability.

---

### 4.4 Reconcile a transaction

```
POST /api/v1/ledger-transactions/{id}/reconcile
  body: { externalReferenceId, externalStatus }

  → Record externalStatus on the transaction
  → Compute reconciliationResult:
      MATCHED                   internal SUCCESS ↔ external SUCCESS
      STATUS_MISMATCH           internal/external disagree
      MISSING_EXTERNAL_REFERENCE externalReferenceId not provided
  → Persist + write OutboxEvent + audit chain entry
```

`GET /api/v1/ops/integrity/reconciliation` returns aggregate health (matched / mismatched /
not-reconciled counts). `healthy: false` fires when mismatch or missing-ref count > 0.

---

### 4.5 Batch and scheduled reconciliation

```
POST /api/v1/reconciliation/batch  (up to 500 items per call)
  → For each item: locate by referenceId, apply externalStatus, compute result
  → Write one immutable ReconciliationRun + one ReconciliationRunItem per item
  → Aggregate counts in the run record

ScheduledReconciliationJob (@Scheduled, disabled in tests)
  → Pulls PENDING/unreconciled transactions
  → Calls FakeExternalReconciliationClient (swap for real HTTP client)
  → Applies results in bulk
```

Every run is persisted as an **immutable audit record** — operators can replay exactly what was
reconciled, when, and with what outcome.

---

### 4.6 Webhook delivery, retry, and DLQ

```
Business operation (e.g. post transaction)
  └─► OutboxService.publish()   ← same DB transaction, atomic
         └─► outbox_events row (published = false)

OutboxRelayJob (@Scheduled every 30 s, disabled in tests)
  └─► OutboxRelayService.relay()
        └─► For each unpublished event:
              → Match active webhook subscriptions by eventType
              → POST to targetUrl (LoggingWebhookClient in dev; swap for real HTTP)
              → Write WebhookDelivery row (SUCCESS or FAILED)
              → Mark outbox event published

WebhookRetryJob (@Scheduled every 60 s)
  └─► Retries FAILED deliveries with backoff
        → After max attempts → status = DEAD (dead-letter queue)

POST /api/v1/webhook-deliveries/{id}/retry  — manual re-trigger for ops
```

This is the **transactional outbox pattern**: the event write and the business operation
share one database transaction, so they either both commit or both roll back.

---

### 4.7 Audit chain verification

```
Every significant event (transaction posted/reversed/reconciled, hold created/captured/released)
appends one row to ledger_audit_chain:

  payloadHash  = SHA-256(deterministic event payload string)
  currentHash  = SHA-256(sequenceNumber | eventType | entityType
                         | entityId | payloadHash | previousHash)

Sequence assignment is serialised with a PostgreSQL advisory lock so no two
concurrent transactions can claim the same sequence number.

POST /api/v1/ops/ledger-integrity/chain/verify
  → Reads all entries in sequence order
  → Re-derives each currentHash from stored fields
  → Checks previousHash linkage
  → Returns { valid, checkedRecords, brokenAtSequenceNumber, brokenAtId, message }

If any row was altered after it was written, the chain is broken and the
verification response pinpoints the exact sequence number.
```

---

## 5. Security and production-readiness

| Concern | Implementation |
|---|---|
| Secrets | All credentials via environment variables; no hardcoded values |
| `.env` in git | `.gitignore` excludes `.env`; `.env.example` committed instead |
| Ops endpoints | `GET/POST /api/v1/ops/**` require `X-Internal-Api-Key` header |
| Key comparison | `MessageDigest.isEqual` (constant-time) to prevent timing attacks |
| Fail-closed | If `INTERNAL_API_KEY` env var is blank, all ops requests are denied |
| Secret scanning | Gitleaks scans full git history on every push and PR |
| Dependency CVEs | Trivy filesystem scan — HIGH/CRITICAL only, `--ignore-unfixed` |
| Schema safety | Flyway owns migrations; Hibernate is `ddl-auto=validate` only |
| Monetary precision | `NUMERIC(19,4)` everywhere — no floating point |
| Timestamps | `TIMESTAMPTZ` (UTC) everywhere — no locale ambiguity |

---

## 6. Testing strategy

**293 integration tests, zero mocks of infrastructure.**

| Property | Detail |
|---|---|
| Database | Real PostgreSQL 16 via Testcontainers — same schema as production |
| Schema | Flyway runs every migration before any test executes |
| Spring context | Shared across all test classes (fast) via Spring's context cache |
| Isolation | Each test class calls `resetDatabase()` in `@BeforeEach` — 12 DELETEs in FK-safe order |
| Concurrency | Tests for optimistic locking (409), pessimistic locking, hold-capture races |
| Outbox relay | Driven directly in tests; scheduled job is disabled (`@Scheduled` suppressed in test profile) |
| Security | `InternalApiKeyInterceptorTest` — 401 without key, 401 wrong key, pass with correct key |
| Audit chain | Tamper detection: direct `jdbcTemplate.update()` mutates a row, then `/verify` returns `valid: false` |

---

## 7. How to run locally

**Docker Compose (recommended):**

```bash
cp .env.example .env        # set DB credentials and INTERNAL_API_KEY
docker compose up --build
```

App available at `http://localhost:8080`.  
Swagger UI: `http://localhost:8080/swagger-ui.html`

**Maven + local PostgreSQL:**

```bash
export DB_URL=jdbc:postgresql://localhost:5432/aetherledger
export DB_USERNAME=postgres
export DB_PASSWORD=yourpassword
export INTERNAL_API_KEY=dev-key
mvn spring-boot:run
```

**Tests:**

```bash
mvn test --no-transfer-progress   # Docker must be running for Testcontainers
```

**Ops endpoints (require key):**

```bash
curl -H "X-Internal-Api-Key: dev-key" \
  http://localhost:8080/api/v1/ops/ledger-integrity/chain/latest

curl -X POST -H "X-Internal-Api-Key: dev-key" \
  http://localhost:8080/api/v1/ops/ledger-integrity/chain/verify
```

---

## 8. How to explain this in interviews

### "Walk me through the most interesting engineering problem in this project."

> The deadlock prevention in the transaction service. When two concurrent transactions both
> touch accounts A and B — one doing A→B, the other doing B→A — naively locking them in
> arrival order creates a cycle. I prevent this by always acquiring locks in ascending UUID
> order. Both transactions lock the same account first, so the second thread simply waits
> rather than forming a cycle. That's the classic technique for eliminating AB/BA deadlocks.

### "Why is the balance not a stored column?"

> Storing a balance means you have two sources of truth: the entry history and the balance
> field. They can diverge — a bug, a failed partial update, a direct database fix. By deriving
> the balance from the sum of entries at read time, there is exactly one source of truth.
> The entries are immutable so they can never be quietly corrected. This is how production
> ledgers at Stripe and similar companies work.

### "How does idempotency work?"

> Two layers. First, a pre-flight `existsByReferenceId` check — fast, covers 99% of cases.
> Second, if two identical requests race through the pre-flight simultaneously, the UNIQUE
> constraint on `reference_id` in the database catches the duplicate at INSERT time and
> surfaces as a `DataIntegrityViolationException`, which I translate to a 409. The race
> window between the check and the insert is closed by the database, not by application logic.

### "What is the transactional outbox pattern and why use it?"

> If I published a Kafka/webhook event after committing the ledger transaction, a crash between
> the two would lose the event — the ledger moved but no one was notified. The outbox pattern
> writes the event row in the same database transaction as the business operation. If the
> business operation rolls back, the event row rolls back with it. A background job then
> delivers the events from the outbox. Exactly-once semantics are hard; at-least-once from a
> reliable outbox is achievable.

### "Why Testcontainers instead of H2 or mocks?"

> H2 does not enforce the same constraints as PostgreSQL — NUMERIC precision, TIMESTAMPTZ
> semantics, partial indexes, the CHECK constraints that back the enums. If I mock the
> database, my tests can pass on a schema that would fail in production. Every one of the
> 293 tests runs against a real PostgreSQL instance with the real Flyway migrations applied.
> The schema the tests see is byte-for-byte identical to what production sees.

### "What is the audit hash chain and why SHA-256?"

> Every significant event — transaction posted, reversed, reconciled; hold created, captured,
> released — writes a row whose `currentHash` is SHA-256 of the previous row's hash plus the
> current event's fields. If anyone modifies a historical row directly in the database, the
> chain breaks at that sequence number and the verification endpoint reports exactly which
> entry was tampered with. SHA-256 is used because it is cryptographically collision-resistant
> — you cannot find two different inputs that produce the same hash, so you cannot silently
> replace an event payload and keep the hash intact.

---

## 9. Future improvements

| Area | What and why |
|---|---|
| Authentication | JWT or API-key auth for all non-ops endpoints; `INTERNAL_API_KEY` pattern already demonstrates the approach |
| Multi-currency | ISO 4217 currency codes on entries; FX rate ledger for conversion gain/loss tracking |
| Real HTTP webhook client | Replace `LoggingWebhookClient` with `RestClient`; add HMAC-SHA256 signing |
| Cursor pagination | Keyset pagination for `GET /ledger-transactions` — offset pagination degrades at scale |
| Read replica routing | Route `@Transactional(readOnly=true)` queries to a replica |
| Prometheus/Grafana | Micrometer metrics are already exposed; wire to a dashboard |
| Archive partitioning | Partition `ledger_entries` by month; move cold partitions to cheaper storage |
| AI reconciliation insights | `AiInsightGenerator.tryAiGenerate()` stub is already wired; plug in Claude or OpenAI |
