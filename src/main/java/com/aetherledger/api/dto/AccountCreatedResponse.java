package com.aetherledger.api.dto;

import com.aetherledger.domain.entity.Account;

import java.time.Instant;
import java.util.UUID;

public record AccountCreatedResponse(
    UUID id,
    String name,
    String type,
    Instant createdAt
) {

    public static AccountCreatedResponse from(Account account) {
        return new AccountCreatedResponse(
            account.getId(),
            account.getName(),
            account.getType().name(),
            account.getCreatedAt()
        );
    }
}
