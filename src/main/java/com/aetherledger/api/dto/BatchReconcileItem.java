package com.aetherledger.api.dto;

import com.aetherledger.domain.enums.ExternalStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "A single item in a batch reconciliation request, representing one external provider record")
public record BatchReconcileItem(
    @Schema(description = "Internal referenceId of the ledger transaction to match against.", example = "PAY-2024-001")
    @NotBlank(message = "referenceId must not be blank")
    @Size(max = 255, message = "referenceId must not exceed 255 characters")
    String referenceId,

    @Schema(description = "The provider's own reference for this transaction. Optional.", example = "EXT-TXN-987654")
    @Size(max = 255, message = "externalReferenceId must not exceed 255 characters")
    String externalReferenceId,

    @Schema(description = "Status as reported by the external payment provider.",
            allowableValues = {"PENDING", "SUCCESS", "FAILED", "NOT_FOUND"})
    @NotNull(message = "externalStatus is required")
    ExternalStatus externalStatus
) {}
