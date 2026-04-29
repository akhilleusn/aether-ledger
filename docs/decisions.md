\# AetherLedger Technical Decisions



This document explains the main backend and fintech-style decisions behind AetherLedger.



\## 1. Why double-entry ledger?



AetherLedger uses double-entry accounting because financial systems must prove that money is not created or destroyed accidentally.



Every successful transaction creates two ledger entries:



\- one debit entry

\- one credit entry



The entries must sum to zero.



\## 2. Why are balances derived instead of stored?



Balances are derived by summing ledger entries instead of storing a mutable balance column.



This keeps the ledger entries as the source of truth and avoids situations where stored balances and transaction history disagree.



In a larger production system, cached balances or snapshots could be added later, but the ledger entries would still remain authoritative.



\## 3. Why idempotency with referenceId?



Payment requests can be retried because of network timeouts or client/provider uncertainty.



AetherLedger uses a caller-provided `referenceId` as an idempotency key. If the same `referenceId` is submitted again, the API rejects the duplicate instead of creating a second transaction.



This is protected by application checks and database uniqueness constraints.



\## 4. Why append-only reversals?



Financial history should not be rewritten.



If a completed transaction needs correction, AetherLedger creates a new reversal transaction instead of editing or deleting the original transaction.



This preserves audit history and makes corrections explicit.



\## 5. Why reconciliation?



Real financial systems often depend on external providers.



Internal transaction state can drift from provider state because of failed callbacks, delayed updates, network issues, or provider-side corrections.



AetherLedger includes reconciliation flows to detect matched transactions, status mismatches, missing external references, and unreconciled records.



\## 6. Why balance holds?



Balance holds model reserved funds.



For example, a payment authorization may reserve money before capture. The user still owns the money, but it should not be available for spending.



AetherLedger separates ledger balance, held amount, and available balance.



\## 7. Why pessimistic locking?



Concurrent transactions can touch the same accounts at the same time.



AetherLedger uses database row locks before balance-affecting writes. To reduce deadlock risk, account locks are acquired in deterministic UUID order.



\## 8. Why transactional outbox?



If the backend saves a transaction and then directly publishes an event, the database write could succeed while event publishing fails.



The transactional outbox pattern stores the event in the database in the same transaction as the ledger change. A relay process can publish it later.



This makes event publishing reliable without requiring Kafka in the portfolio version.



\## 9. Why webhook retry and DLQ?



Webhook receivers can fail.



AetherLedger records webhook delivery attempts, retries failed deliveries, and moves repeatedly failing deliveries into a dead-letter state.



Webhook failure does not break the ledger transaction because delivery is handled asynchronously.



\## 10. Why audit-chain hashing?



The audit chain is a tamper-evident integrity feature.



Each audit-chain record stores a hash based on the previous hash and current event data. If an old audit record is modified, chain verification can detect the broken sequence.



This is a lightweight hash-chain technique, not blockchain.



\## 11. Why internal API key for ops endpoints?



Operational endpoints expose sensitive integrity and audit information.



AetherLedger protects `/api/v1/ops/\*\*` endpoints with an internal API key loaded from the environment and sent through `X-Internal-Api-Key`.



If the key is missing, wrong, or not configured, the system fails closed.



\## 12. Why Testcontainers PostgreSQL?



AetherLedger uses Testcontainers with real PostgreSQL because ledger correctness depends on realistic database behavior.



H2 can behave differently around locking, constraints, SQL dialect, numeric precision, and Flyway migrations.



For a fintech-style backend, correctness matters more than fake speed.



\## 13. Why Flyway migrations?



Flyway makes database changes explicit, versioned, and reproducible.



Instead of relying on Hibernate to auto-create schema, Flyway documents how the database evolves.



\## 14. Why Gitleaks and Trivy?



Gitleaks checks for committed secrets.



Trivy checks dependencies and filesystem configuration for known vulnerabilities.



This makes security part of the delivery pipeline, not only a manual final check.



\## 15. Why not Kafka yet?



The project uses transactional outbox and webhook delivery, but does not add Kafka yet.



Kafka would be realistic in a larger event-driven system, but adding it now would increase infrastructure complexity.



The current version focuses on reliable event storage, relay, retry, and failure inspection.



\## 16. What would be improved next?



If AetherLedger moved closer to production, the next improvements would be:



\- JWT authentication and account-level permissions

\- Kafka adapter for outbox events

\- cloud deployment

\- Prometheus/Grafana monitoring

\- balance snapshots for faster reads

\- multi-currency support

\- real external provider integration

\- stricter rate limiting



The current version intentionally focuses on financial correctness, auditability, reconciliation, retry safety, and production-style testing.

