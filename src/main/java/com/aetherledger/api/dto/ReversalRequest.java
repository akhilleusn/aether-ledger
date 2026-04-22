package com.aetherledger.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReversalRequest(
    @NotBlank(message = "referenceId must not be blank")
    @Size(max = 255, message = "referenceId must not exceed 255 characters")
    String referenceId
) {}
