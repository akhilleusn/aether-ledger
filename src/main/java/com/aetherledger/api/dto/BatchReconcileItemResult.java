package com.aetherledger.api.dto;

import com.aetherledger.domain.enums.ReconciliationResult;

import java.util.UUID;

/**
 * Per-item outcome within a {@link BatchReconcileResponse}.
 *
 * <p>{@code status} is {@code UPDATED} when the internal transaction was found
 * and its reconciliation fields were written, {@code NOT_FOUND} when no
 * transaction matched the supplied {@code referenceId}.
 *
 * <p>When {@code status} is {@code NOT_FOUND}, {@code transactionId} and
 * {@code reconciliationResult} are {@code null}.
 */
public record BatchReconcileItemResult(
    String referenceId,
    UUID   transactionId,
    String reconciliationResult,
    String status
) {

    public static BatchReconcileItemResult notFound(String referenceId) {
        return new BatchReconcileItemResult(referenceId, null, null, "NOT_FOUND");
    }

    public static BatchReconcileItemResult updated(
            String referenceId,
            UUID transactionId,
            ReconciliationResult result) {
        return new BatchReconcileItemResult(referenceId, transactionId, result.name(), "UPDATED");
    }
}
