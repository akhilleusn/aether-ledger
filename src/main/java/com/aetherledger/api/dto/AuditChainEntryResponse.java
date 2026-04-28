package com.aetherledger.api.dto;

import com.aetherledger.domain.entity.LedgerAuditChainEntry;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "A single entry in the tamper-evident ledger audit hash chain")
public record AuditChainEntryResponse(

    @Schema(description = "System-assigned entry UUID")
    UUID id,

    @Schema(description = "Monotonically increasing position in the chain")
    long sequenceNumber,

    @Schema(description = "Type of ledger event that produced this entry")
    String eventType,

    @Schema(description = "Domain entity type (LEDGER_TRANSACTION or HOLD)")
    String entityType,

    @Schema(description = "UUID of the domain entity that triggered this event")
    UUID entityId,

    @Schema(description = "SHA-256 hex digest of the deterministic event payload string")
    String payloadHash,

    @Schema(description = "currentHash of the preceding entry; null for the genesis entry")
    String previousHash,

    @Schema(description = "SHA-256 hex digest of (sequenceNumber|eventType|entityType|entityId|payloadHash|previousHash)")
    String currentHash,

    @Schema(description = "UTC timestamp when this entry was appended")
    Instant createdAt

) {

    public static AuditChainEntryResponse from(LedgerAuditChainEntry entry) {
        return new AuditChainEntryResponse(
            entry.getId(),
            entry.getSequenceNumber(),
            entry.getEventType(),
            entry.getEntityType(),
            entry.getEntityId(),
            entry.getPayloadHash(),
            entry.getPreviousHash(),
            entry.getCurrentHash(),
            entry.getCreatedAt()
        );
    }
}
