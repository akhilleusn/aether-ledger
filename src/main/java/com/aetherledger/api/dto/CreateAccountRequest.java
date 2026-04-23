package com.aetherledger.api.dto;

import com.aetherledger.domain.enums.AccountType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Request body for creating a new ledger account")
public record CreateAccountRequest(

    @Schema(description = "Display name for the account. Must be unique across all account types.",
            example = "Alice Savings", maxLength = 100)
    @NotBlank(message = "name must not be blank")
    @Size(max = 100, message = "name must not exceed 100 characters")
    String name,

    @Schema(description = "Account classification. USER: end-user accounts. SYSTEM: internal/escrow accounts. MERCHANT: merchant/payee accounts.",
            allowableValues = {"USER", "SYSTEM", "MERCHANT"})
    @NotNull(message = "type is required")
    AccountType type

) {}
