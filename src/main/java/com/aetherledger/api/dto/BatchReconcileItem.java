package com.aetherledger.api.dto;

import com.aetherledger.domain.enums.ExternalStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record BatchReconcileItem(
    @NotBlank(message = "referenceId must not be blank")
    @Size(max = 255, message = "referenceId must not exceed 255 characters")
    String referenceId,

    @Size(max = 255, message = "externalReferenceId must not exceed 255 characters")
    String externalReferenceId,

    @NotNull(message = "externalStatus is required")
    ExternalStatus externalStatus
) {}
