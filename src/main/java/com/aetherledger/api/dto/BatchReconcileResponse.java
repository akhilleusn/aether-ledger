package com.aetherledger.api.dto;

import java.util.List;

/**
 * Summary result returned by {@code POST /api/v1/reconciliation/batch}.
 *
 * <p>Counts are always consistent: {@code updatedCount + missingTransactionCount == totalItems}.
 * Among updated items: {@code matchedCount + mismatchCount + missingExternalReferenceCount == updatedCount}.
 */
public record BatchReconcileResponse(
    int totalItems,
    int updatedCount,
    int matchedCount,
    int mismatchCount,
    int missingTransactionCount,
    int missingExternalReferenceCount,
    List<BatchReconcileItemResult> results
) {}
