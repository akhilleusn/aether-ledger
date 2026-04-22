package com.aetherledger.api.dto;

/**
 * Snapshot of ledger structural health, returned by
 * {@code GET /api/v1/ops/integrity/ledger}.
 *
 * <p>{@code healthy} is {@code true} only when both violation counters are zero.
 */
public record LedgerIntegrityReportResponse(
    long    totalTransactions,
    long    successfulTransactions,
    long    pendingTransactions,
    long    failedTransactions,
    long    reversedTransactionsCount,
    long    zeroSumViolationsCount,
    long    transactionsWithUnexpectedEntryCount,
    boolean healthy
) {}
