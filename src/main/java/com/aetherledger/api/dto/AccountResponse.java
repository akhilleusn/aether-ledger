package com.aetherledger.api.dto;

import com.aetherledger.domain.entity.Account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
    UUID id,
    String name,
    String type,
    BigDecimal currentBalance,
    Instant createdAt
) {

    public static AccountResponse from(Account account, BigDecimal currentBalance) {
        return new AccountResponse(
            account.getId(),
            account.getName(),
            account.getType().name(),
            currentBalance,
            account.getCreatedAt()
        );
    }
}
