package com.aetherledger.domain.enums;

/**
 * Discriminator for outbox event rows.
 *
 * <p>Each value corresponds to a distinct business action that downstream
 * consumers may need to react to.
 *
 * <ul>
 *   <li>{@link #TRANSACTION_POSTED} — an immediate (non-pending) transaction
 *       was accepted and ledger entries were written.</li>
 *   <li>{@link #TRANSACTION_COMPLETED} — a previously PENDING transaction
 *       transitioned to SUCCESS and ledger entries were written.</li>
 *   <li>{@link #TRANSACTION_REVERSED} — a compensating reversal transaction
 *       was posted against an original SUCCESS transaction.</li>
 *   <li>{@link #TRANSACTION_RECONCILED} — a single transaction was matched
 *       against an external provider record.</li>
 *   <li>{@link #BATCH_RECONCILIATION_COMPLETED} — a batch reconciliation run
 *       finished; the payload carries aggregate counts for the run.</li>
 * </ul>
 */
public enum OutboxEventType {
    TRANSACTION_POSTED,
    TRANSACTION_COMPLETED,
    TRANSACTION_REVERSED,
    TRANSACTION_RECONCILED,
    BATCH_RECONCILIATION_COMPLETED,
    HOLD_CREATED,
    HOLD_CAPTURED,
    HOLD_RELEASED
}
