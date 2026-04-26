package com.aetherledger.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Request body for placing a balance hold on an account")
public record CreateHoldRequest(

    @Schema(description = "Account UUID on which to place the hold")
    @NotNull(message = "accountId is required")
    UUID accountId,

    @Schema(description = "Amount to reserve. Must be strictly positive.", example = "50.00")
    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be greater than zero")
    BigDecimal amount,

    @Schema(description = "Caller-supplied idempotency key. Must be unique across all holds.", example = "HOLD-ORDER-9182")
    @NotBlank(message = "referenceId must not be blank")
    @Size(max = 255, message = "referenceId must not exceed 255 characters")
    String referenceId

) {}
