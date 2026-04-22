package com.aetherledger.api.dto;

import com.aetherledger.domain.entity.LedgerTransaction;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbound response body for a successfully posted ledger transaction.
 *
 * <p>Deliberately decoupled from the {@link LedgerTransaction} JPA entity:
 * callers receive a stable, serialisation-friendly contract that is independent
 * of future internal model changes.
 *
 * @param transactionId the system-assigned UUID of the new transaction
 * @param referenceId   the caller-supplied idempotency key echoed back
 * @param status        string representation of {@code TransactionStatus}
 * @param createdAt     UTC timestamp of when the transaction was recorded
 */
public record TransactionResponse(
    UUID transactionId,
    String referenceId,
    String status,
    Instant createdAt
) {

    public static TransactionResponse from(LedgerTransaction tx) {
        return new TransactionResponse(
            tx.getId(),
            tx.getReferenceId(),
            tx.getStatus().name(),
            tx.getCreatedAt()
        );
    }
}
