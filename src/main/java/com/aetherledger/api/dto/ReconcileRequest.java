package com.aetherledger.api.dto;

import com.aetherledger.domain.enums.ExternalStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Request body for recording a single-transaction reconciliation outcome")
public record ReconcileRequest(
    @Schema(description = "The provider's own reference for this transaction, if available.", example = "EXT-TXN-987654")
    @Size(max = 255, message = "externalReferenceId must not exceed 255 characters")
    String externalReferenceId,

    @Schema(description = "Status as reported by the external payment provider.",
            allowableValues = {"PENDING", "SUCCESS", "FAILED", "NOT_FOUND"})
    @NotNull(message = "externalStatus is required")
    ExternalStatus externalStatus
) {}
