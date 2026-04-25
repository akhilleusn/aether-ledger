package com.aetherledger.domain.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An external caller's registration to receive webhook deliveries for a
 * specific domain event type.
 *
 * <p>Soft-deleted via {@link #deactivate()} — rows are never physically removed
 * so delivery history remains traceable.
 */
@Entity
@Table(
    name = "webhook_subscriptions",
    indexes = {
        @Index(name = "idx_webhook_sub_active_event", columnList = "event_type")
    }
)
public class WebhookSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "target_url", nullable = false, length = 2048)
    private String targetUrl;

    /** Stored as the {@link com.aetherledger.domain.enums.OutboxEventType} name. */
    @Column(name = "event_type", nullable = false, updatable = false, length = 60)
    private String eventType;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WebhookSubscription() {}

    public static WebhookSubscription create(String targetUrl, String eventType) {
        Objects.requireNonNull(targetUrl,  "targetUrl must not be null");
        Objects.requireNonNull(eventType,  "eventType must not be null");
        WebhookSubscription sub = new WebhookSubscription();
        sub.targetUrl = targetUrl;
        sub.eventType = eventType;
        sub.active    = true;
        return sub;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    /** Soft-deletes this subscription; delivery attempts will stop. */
    public void deactivate() {
        this.active = false;
    }

    public UUID    getId()        { return id; }
    public String  getTargetUrl() { return targetUrl; }
    public String  getEventType() { return eventType; }
    public boolean isActive()     { return active; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WebhookSubscription other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() { return getClass().hashCode(); }

    @Override
    public String toString() {
        return "WebhookSubscription{id=" + id + ", eventType='" + eventType + "', active=" + active + "}";
    }
}
