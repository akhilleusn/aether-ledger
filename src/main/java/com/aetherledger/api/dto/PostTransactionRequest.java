package com.aetherledger.api.dto;

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
public record PostTransactionRequest(

    @NotNull(message = "debitAccountId is required")
    UUID debitAccountId,

    @NotNull(message = "creditAccountId is required")
    UUID creditAccountId,

    @NotNull(message = "amount is required")
    @Positive(message = "amount must be greater than zero")
    BigDecimal amount,

    @NotBlank(message = "referenceId is required")
    String referenceId

) {}
