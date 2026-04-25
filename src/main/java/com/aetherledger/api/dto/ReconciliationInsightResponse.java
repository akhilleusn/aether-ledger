package com.aetherledger.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "Operator-facing explanation of a transaction's reconciliation state")
public record ReconciliationInsightResponse(

    @Schema(description = "System-assigned transaction UUID")
    UUID transactionId,

    @Schema(description = "Caller-supplied idempotency key")
    String referenceId,

    @Schema(description = "Internal ledger status",
            allowableValues = {"PENDING", "SUCCESS", "FAILED"})
    String internalStatus,

    @Schema(description = "Status reported by the external provider; null if not yet reconciled",
            allowableValues = {"PENDING", "SUCCESS", "FAILED", "NOT_FOUND"}, nullable = true)
    String externalStatus,

    @Schema(description = "Computed reconciliation outcome",
            allowableValues = {"NOT_RECONCILED", "MISSING_EXTERNAL_REFERENCE", "MATCHED", "STATUS_MISMATCH"})
    String reconciliationResult,

    @Schema(description = "Operator-facing explanation of the reconciliation state or anomaly")
    String explanation,

    @Schema(description = "Suggested checks for the operator to investigate further")
    List<String> recommendedChecks
) {}
