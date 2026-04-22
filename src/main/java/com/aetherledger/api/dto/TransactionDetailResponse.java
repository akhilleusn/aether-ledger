package com.aetherledger.api.dto;

import com.aetherledger.domain.entity.LedgerTransaction;
import com.aetherledger.domain.enums.ExternalStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransactionDetailResponse(
    UUID transactionId,
    String referenceId,
    String status,
    Instant createdAt,
    UUID reversalOfTransactionId,
    UUID reversedByTransactionId,
    String externalReferenceId,
    ExternalStatus externalStatus,
    Instant reconciledAt,
    String reconciliationResult,
    List<LedgerEntryView> entries
) {

    public static TransactionDetailResponse from(LedgerTransaction tx) {
        List<LedgerEntryView> entryViews = tx.getLedgerEntries().stream()
            .map(LedgerEntryView::from)
            .toList();
        return new TransactionDetailResponse(
            tx.getId(),
            tx.getReferenceId(),
            tx.getStatus().name(),
            tx.getCreatedAt(),
            tx.getReversalOfTransactionId(),
            tx.getReversedByTransactionId(),
            tx.getExternalReferenceId(),
            tx.getExternalStatus(),
            tx.getReconciledAt(),
            tx.computeReconciliationResult().name(),
            entryViews
        );
    }
}
