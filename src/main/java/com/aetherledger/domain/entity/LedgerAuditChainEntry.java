package com.aetherledger.domain.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A single entry in the tamper-evident ledger audit hash chain.
 *
 * <p>Each entry stores a SHA-256 {@code currentHash} that is derived from:
 * <pre>
 *   SHA-256(sequenceNumber | eventType | entityType | entityId | payloadHash | previousHash)
 * </pre>
 * where {@code previousHash} is {@code "GENESIS"} for the first entry.
 *
 * <p>All columns are immutable after creation ({@code updatable = false}).
 * Modifying any persisted row corrupts the chain, which is detected by
 * re-deriving the hash during verification.
 */
@Entity
@Table(
    name = "ledger_audit_chain",
    indexes = {
        @Index(name = "idx_ledger_audit_chain_entity_id",  columnList = "entity_id"),
        @Index(name = "idx_ledger_audit_chain_event_type", columnList = "event_type"),
        @Index(name = "idx_ledger_audit_chain_created_at", columnList = "created_at")
    }
)
public class LedgerAuditChainEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "sequence_number", nullable = false, updatable = false, unique = true)
    private long sequenceNumber;

    @Column(name = "event_type", nullable = false, updatable = false, length = 100)
    private String eventType;

    @Column(name = "entity_type", nullable = false, updatable = false, length = 100)
    private String entityType;

    @Column(name = "entity_id", nullable = false, updatable = false)
    private UUID entityId;

    @Column(name = "payload_hash", nullable = false, updatable = false, length = 64)
    private String payloadHash;

    /** {@code null} only for the genesis entry (sequenceNumber == 1). */
    @Column(name = "previous_hash", updatable = false, length = 64)
    private String previousHash;

    @Column(name = "current_hash", nullable = false, updatable = false, length = 64)
    private String currentHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Reserved for JPA proxying — do not call from application code. */
    protected LedgerAuditChainEntry() {}

    public static LedgerAuditChainEntry create(
            long   sequenceNumber,
            String eventType,
            String entityType,
            UUID   entityId,
            String payloadHash,
            String previousHash,
            String currentHash) {

        LedgerAuditChainEntry e = new LedgerAuditChainEntry();
        e.sequenceNumber = sequenceNumber;
        e.eventType      = eventType;
        e.entityType     = entityType;
        e.entityId       = entityId;
        e.payloadHash    = payloadHash;
        e.previousHash   = previousHash;
        e.currentHash    = currentHash;
        return e;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public UUID    getId()             { return id; }
    public long    getSequenceNumber() { return sequenceNumber; }
    public String  getEventType()      { return eventType; }
    public String  getEntityType()     { return entityType; }
    public UUID    getEntityId()       { return entityId; }
    public String  getPayloadHash()    { return payloadHash; }
    public String  getPreviousHash()   { return previousHash; }
    public String  getCurrentHash()    { return currentHash; }
    public Instant getCreatedAt()      { return createdAt; }
}
