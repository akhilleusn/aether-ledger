package com.aetherledger.api.dto;

import com.aetherledger.domain.entity.Account;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
    @Schema(description = "System-assigned account UUID")
    UUID id,
    @Schema(description = "Account display name")
    String name,
    @Schema(description = "Account type", allowableValues = {"USER", "SYSTEM", "MERCHANT"})
    String type,
    @Schema(description = "Current balance derived from all posted ledger entries. Positive = net credit position; negative = net debit position.")
    BigDecimal currentBalance,
    @Schema(description = "UTC timestamp when the account was created")
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
