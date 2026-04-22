package com.aetherledger.api.dto;

/**
 * Aggregate reconciliation statistics derived from current ledger data.
 * All values are computed at query time; nothing is pre-computed or cached.
 */
public record ReconciliationSummaryResponse(
    long totalTransactions,
    long notReconciledCount,
    long matchedCount,
    long mismatchCount,
    long missingExternalReferenceCount
) {}
