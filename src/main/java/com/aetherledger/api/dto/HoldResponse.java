package com.aetherledger.api.dto;

import com.aetherledger.domain.entity.Hold;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Representation of a balance hold")
public record HoldResponse(

    @Schema(description = "System-assigned hold UUID")
    UUID id,

    @Schema(description = "Account UUID on which the hold was placed")
    UUID accountId,

    @Schema(description = "Reserved amount")
    BigDecimal amount,

    @Schema(description = "Caller-supplied idempotency key")
    String referenceId,

    @Schema(description = "Hold lifecycle status", allowableValues = {"ACTIVE", "CAPTURED", "RELEASED"})
    String status,

    @Schema(description = "UTC timestamp when the hold was created")
    Instant createdAt,

    @Schema(description = "UTC timestamp when the hold was captured, null if not yet captured")
    Instant capturedAt,

    @Schema(description = "UTC timestamp when the hold was released, null if not yet released")
    Instant releasedAt

) {

    public static HoldResponse from(Hold hold) {
        return new HoldResponse(
            hold.getId(),
            hold.getAccountId(),
            hold.getAmount(),
            hold.getReferenceId(),
            hold.getStatus().name(),
            hold.getCreatedAt(),
            hold.getCapturedAt(),
            hold.getReleasedAt()
        );
    }
}
