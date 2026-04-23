package com.aetherledger.api.dto;

import com.aetherledger.domain.entity.LedgerTransaction;
import com.aetherledger.domain.enums.ExternalStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransactionDetailResponse(
    @Schema(description = "System-assigned transaction UUID")
    UUID transactionId,
    @Schema(description = "Caller-supplied idempotency key")
    String referenceId,
    @Schema(description = "Transaction lifecycle status", allowableValues = {"PENDING", "SUCCESS", "FAILED"})
    String status,
    @Schema(description = "UTC timestamp when the transaction was created")
    Instant createdAt,
    @Schema(description = "If this is a reversal, the UUID of the original transaction; null otherwise")
    UUID reversalOfTransactionId,
    @Schema(description = "If this transaction has been reversed, the UUID of the reversal transaction; null otherwise")
    UUID reversedByTransactionId,
    @Schema(description = "Provider's reference ID recorded during reconciliation; null if not yet reconciled")
    String externalReferenceId,
    @Schema(description = "Status reported by the external provider; null if not yet reconciled",
            allowableValues = {"PENDING", "SUCCESS", "FAILED", "NOT_FOUND"})
    ExternalStatus externalStatus,
    @Schema(description = "UTC timestamp when reconciliation data was last recorded; null if not yet reconciled")
    Instant reconciledAt,
    @Schema(description = "Computed reconciliation outcome. NOT_RECONCILED: no attempt made. MATCHED: internal and external agree. STATUS_MISMATCH: statuses disagree. MISSING_EXTERNAL_REFERENCE: reconciled without external data.",
            allowableValues = {"NOT_RECONCILED", "MISSING_EXTERNAL_REFERENCE", "MATCHED", "STATUS_MISMATCH"})
    String reconciliationResult,
    @Schema(description = "Ledger entries written for this transaction. SUCCESS transactions have exactly 2 entries; PENDING and FAILED have 0.")
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
