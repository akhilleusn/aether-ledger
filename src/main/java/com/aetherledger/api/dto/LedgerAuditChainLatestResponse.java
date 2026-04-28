package com.aetherledger.api.dto;

import com.aetherledger.domain.entity.LedgerAuditChainEntry;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Tip of the ledger audit hash chain — the most recently appended entry")
public record LedgerAuditChainLatestResponse(

    @Schema(description = "Monotonically increasing position of this entry in the chain")
    long sequenceNumber,

    @Schema(description = "SHA-256 hash of this entry's fields — the current chain tip value")
    String currentHash,

    @Schema(description = "SHA-256 hash of the preceding entry; null for the genesis entry")
    String previousHash,

    @Schema(description = "Type of ledger event that produced this entry")
    String eventType,

    @Schema(description = "Domain entity type (LEDGER_TRANSACTION or HOLD)")
    String entityType,

    @Schema(description = "UUID of the domain entity that triggered this event")
    UUID entityId,

    @Schema(description = "UTC timestamp when this entry was appended")
    Instant createdAt

) {

    public static LedgerAuditChainLatestResponse from(LedgerAuditChainEntry entry) {
        return new LedgerAuditChainLatestResponse(
            entry.getSequenceNumber(),
            entry.getCurrentHash(),
            entry.getPreviousHash(),
            entry.getEventType(),
            entry.getEntityType(),
            entry.getEntityId(),
            entry.getCreatedAt()
        );
    }
}
