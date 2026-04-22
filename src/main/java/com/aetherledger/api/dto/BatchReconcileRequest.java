package com.aetherledger.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record BatchReconcileRequest(
    @NotNull(message = "items list is required")
    @Size(min = 1,   message = "batch must contain at least one item")
    @Size(max = 500, message = "batch must not exceed 500 items")
    List<@Valid BatchReconcileItem> items
) {}
