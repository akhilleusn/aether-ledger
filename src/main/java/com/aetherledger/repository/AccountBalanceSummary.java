package com.aetherledger.repository;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * JPQL constructor-expression projection carrying a single account's running balance.
 * Produced by the bulk GROUP BY query in {@link LedgerEntryRepository}.
 */
public record AccountBalanceSummary(UUID accountId, BigDecimal balance) {}
