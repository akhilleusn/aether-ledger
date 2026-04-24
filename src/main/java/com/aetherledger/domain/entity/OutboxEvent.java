package com.aetherledger.domain.entity;

import com.aetherledger.domain.enums.OutboxEventType;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A durable outbox record written in the same transaction as the business
 * operation that produced it.
 *
 * <p>All fields except {@link #published} are write-once ({@code updatable = false}).
 * A relay process reads unpublished rows, delivers the {@link #payload} to a
 * message broker, and flips {@link #published} to {@code true}.
 *
 * <p>The table has no foreign keys into other tables — the outbox is a
 * transport concern, not a relational one.
 */
@Entity
@Table(
    name = "outbox_events",
    indexes = {
        @Index(name = "idx_outbox_unpublished", columnList = "created_at")
    }
)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Discriminator matching {@link OutboxEventType}; stored as its name. */
    @Column(name = "event_type", nullable = false, updatable = false, length = 60)
    private String eventType;

    /** The primary key of the aggregate that produced this event. */
    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    /** Logical type of the aggregate (e.g. {@code "LEDGER_TRANSACTION"}). */
    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 60)
    private String aggregateType;

    /** JSON payload containing all fields a consumer needs to process the event. */
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String payload;

    /**
     * {@code false} until a relay successfully delivers this event to a broker.
     * This is the only field that may be updated after initial creation.
     */
    @Column(name = "published", nullable = false)
    private boolean published = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Reserved for JPA proxying — do not call from application code. */
    protected OutboxEvent() {}

    /**
     * Factory method used exclusively by {@link com.aetherledger.service.OutboxService}.
     */
    public static OutboxEvent of(OutboxEventType type,
                                 UUID aggregateId,
                                 String aggregateType,
                                 String payload) {
        Objects.requireNonNull(type,          "type must not be null");
        Objects.requireNonNull(aggregateId,   "aggregateId must not be null");
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        Objects.requireNonNull(payload,       "payload must not be null");

        OutboxEvent event  = new OutboxEvent();
        event.eventType    = type.name();
        event.aggregateId  = aggregateId;
        event.aggregateType = aggregateType;
        event.payload      = payload;
        return event;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    /** Marks this event as delivered; called by the relay after successful publish. */
    public void markPublished() {
        this.published = true;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public UUID    getId()            { return id; }
    public String  getEventType()     { return eventType; }
    public UUID    getAggregateId()   { return aggregateId; }
    public String  getAggregateType() { return aggregateType; }
    public String  getPayload()       { return payload; }
    public boolean isPublished()      { return published; }
    public Instant getCreatedAt()     { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OutboxEvent other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() { return getClass().hashCode(); }

    @Override
    public String toString() {
        return "OutboxEvent{id=" + id + ", eventType='" + eventType + "', aggregateId=" + aggregateId + "}";
    }
}
