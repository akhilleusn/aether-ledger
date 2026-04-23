package com.aetherledger.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Request body for reversing a ledger transaction")
public record ReversalRequest(
    @Schema(description = "Unique idempotency key for the new reversal transaction. Must not reuse an existing referenceId.", example = "REV-PAY-2024-001")
    @NotBlank(message = "referenceId must not be blank")
    @Size(max = 255, message = "referenceId must not exceed 255 characters")
    String referenceId
) {}
