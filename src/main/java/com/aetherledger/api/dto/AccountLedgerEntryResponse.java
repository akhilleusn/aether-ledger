package com.aetherledger.api.dto;

import com.aetherledger.domain.entity.LedgerEntry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountLedgerEntryResponse(
    UUID entryId,
    UUID transactionId,
    String referenceId,
    BigDecimal amount,
    Instant createdAt
) {

    public static AccountLedgerEntryResponse from(LedgerEntry entry) {
        return new AccountLedgerEntryResponse(
            entry.getId(),
            entry.getLedgerTransaction().getId(),
            entry.getLedgerTransaction().getReferenceId(),
            entry.getAmount(),
            entry.getCreatedAt()
        );
    }
}
