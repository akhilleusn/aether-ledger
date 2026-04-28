package com.aetherledger.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Result of a full ledger audit hash chain integrity verification")
public record AuditChainVerificationResponse(

    @Schema(description = "true only when every entry's hash is intact and correctly linked to its predecessor")
    boolean valid,

    @Schema(description = "Total number of chain entries examined")
    long checkedRecords,

    @Schema(description = "Sequence number of the first broken entry; null when valid is true")
    Long brokenAtSequenceNumber,

    @Schema(description = "UUID of the first entry whose hash does not match; null when valid is true")
    String brokenAtId,

    @Schema(description = "Human-readable verification summary")
    String message

) {}
