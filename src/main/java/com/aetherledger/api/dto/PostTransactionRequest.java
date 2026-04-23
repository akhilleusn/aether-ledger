package com.aetherledger.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Inbound request body for POST /api/v1/ledger-transactions.
 *
 * <p>Bean Validation annotations enforce structural correctness at the HTTP
 * boundary before the request reaches the service layer.  Cross-field rules
 * (same-account guard, amount sign in business terms) are validated inside
 * {@code LedgerTransactionService} where precise domain error messages can
 * be generated.
 *
 * @param debitAccountId  UUID of the account that will be debited
 * @param creditAccountId UUID of the account that will be credited
 * @param amount          absolute monetary value; must be strictly positive
 * @param referenceId     caller-supplied idempotency key
 */
@Schema(description = "Request body for posting a ledger transaction")
public record PostTransactionRequest(

    @Schema(description = "UUID of the account to debit (balance decreases)", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    @NotNull(message = "debitAccountId is required")
    UUID debitAccountId,

    @Schema(description = "UUID of the account to credit (balance increases)", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
    @NotNull(message = "creditAccountId is required")
    UUID creditAccountId,

    @Schema(description = "Strictly positive monetary amount. Supports arbitrary decimal precision.", example = "150.00")
    @NotNull(message = "amount is required")
    @Positive(message = "amount must be greater than zero")
    BigDecimal amount,

    @Schema(description = "Caller-supplied idempotency key. Must be globally unique across all transactions.", example = "PAY-2024-001")
    @NotBlank(message = "referenceId is required")
    String referenceId

) {}
