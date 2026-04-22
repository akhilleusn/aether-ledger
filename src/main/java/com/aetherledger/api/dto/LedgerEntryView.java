package com.aetherledger.api.dto;

import com.aetherledger.domain.entity.LedgerEntry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerEntryView(
    UUID entryId,
    UUID accountId,
    String accountName,
    BigDecimal amount,
    Instant createdAt
) {

    public static LedgerEntryView from(LedgerEntry entry) {
        return new LedgerEntryView(
            entry.getId(),
            entry.getAccount().getId(),
            entry.getAccount().getName(),
            entry.getAmount(),
            entry.getCreatedAt()
        );
    }
}
