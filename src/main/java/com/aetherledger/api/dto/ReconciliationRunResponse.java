package com.aetherledger.api.dto;

import com.aetherledger.domain.entity.ReconciliationRun;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full audit detail for a single reconciliation run, returned by
 * {@code GET /api/v1/reconciliation/runs/{id}}.
 *
 * <p>Contains every per-item outcome so operators can diagnose exactly which
 * provider records were matched, mismatched, or not found.
 */
public record ReconciliationRunResponse(
    UUID   runId,
    Instant createdAt,
    int    totalItems,
    int    updatedCount,
    int    matchedCount,
    int    mismatchCount,
    int    missingTransactionCount,
    int    missingExternalReferenceCount,
    List<BatchReconcileItemResult> results
) {

    public static ReconciliationRunResponse from(ReconciliationRun run) {
        List<BatchReconcileItemResult> results = run.getItems().stream()
            .map(item -> new BatchReconcileItemResult(
                item.getReferenceId(),
                item.getTransactionId(),
                item.getReconciliationResult(),
                item.getItemStatus()
            ))
            .toList();

        return new ReconciliationRunResponse(
            run.getId(),
            run.getCreatedAt(),
            run.getTotalItems(),
            run.getUpdatedCount(),
            run.getMatchedCount(),
            run.getMismatchCount(),
            run.getMissingTransactionCount(),
            run.getMissingExternalReferenceCount(),
            results
        );
    }
}
