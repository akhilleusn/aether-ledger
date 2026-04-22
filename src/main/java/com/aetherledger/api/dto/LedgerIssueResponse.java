package com.aetherledger.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Per-transaction integrity violation detail, returned as a list by
 * {@code GET /api/v1/ops/integrity/ledger/issues}.
 *
 * <p>{@code issueTypes} contains one or more of:
 * <ul>
 *   <li>{@code ZERO_SUM_VIOLATION} — SUCCESS transaction whose entries do not sum to zero</li>
 *   <li>{@code UNEXPECTED_ENTRY_COUNT} — entry count inconsistent with transaction status</li>
 * </ul>
 */
public record LedgerIssueResponse(
    UUID          transactionId,
    String        referenceId,
    String        status,
    long          entryCount,
    BigDecimal    entrySum,
    List<String>  issueTypes
) {}
