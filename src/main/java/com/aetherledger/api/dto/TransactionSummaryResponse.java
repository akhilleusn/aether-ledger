package com.aetherledger.api.dto;

import com.aetherledger.domain.entity.LedgerTransaction;

import java.time.Instant;
import java.util.UUID;

public record TransactionSummaryResponse(
    UUID transactionId,
    String referenceId,
    String status,
    Instant createdAt,
    UUID reversalOfTransactionId,
    UUID reversedByTransactionId,
    String reconciliationResult
) {

    public static TransactionSummaryResponse from(LedgerTransaction tx) {
        return new TransactionSummaryResponse(
            tx.getId(),
            tx.getReferenceId(),
            tx.getStatus().name(),
            tx.getCreatedAt(),
            tx.getReversalOfTransactionId(),
            tx.getReversedByTransactionId(),
            tx.computeReconciliationResult().name()
        );
    }
}
