package com.aetherledger.api.dto;

/**
 * Reconciliation health snapshot returned by
 * {@code GET /api/v1/ops/integrity/reconciliation}.
 *
 * <p>{@code healthy} is {@code true} only when there are no mismatches and
 * no transactions with a missing external reference.
 */
public record ReconciliationHealthResponse(
    long    totalTransactions,
    long    matchedCount,
    long    mismatchCount,
    long    notReconciledCount,
    long    missingExternalReferenceCount,
    boolean healthy
) {}
