package com.aetherledger.domain.entity;

import com.aetherledger.domain.enums.WebhookDeliveryStatus;
import jakarta.persistence.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable-at-creation record of one webhook delivery attempt.
 *
 * <p>Created when the outbox relay dispatches a published event to a
 * matching active subscription.  The {@link #status}, {@link #attemptCount},
 * {@link #lastError}, and {@link #deliveredAt} fields may be updated to
 * reflect the outcome of the delivery attempt.
 */
@Entity
@Table(
    name = "webhook_deliveries",
    indexes = {
        @Index(name = "idx_webhook_delivery_subscription", columnList = "subscription_id"),
        @Index(name = "idx_webhook_delivery_outbox_event", columnList = "outbox_event_id")
    }
)
public class WebhookDelivery {

    private static final int MAX_ERROR_LENGTH   = 500;
    public  static final int DEFAULT_MAX_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private UUID subscriptionId;

    @Column(name = "outbox_event_id", nullable = false, updatable = false)
    private UUID outboxEventId;

    @Column(name = "target_url", nullable = false, updatable = false, length = 2048)
    private String targetUrl;

    @Column(name = "event_type", nullable = false, updatable = false, length = 60)
    private String eventType;

    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WebhookDeliveryStatus status = WebhookDeliveryStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = DEFAULT_MAX_ATTEMPTS;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    protected WebhookDelivery() {}

    public static WebhookDelivery create(WebhookSubscription subscription, OutboxEvent event) {
        Objects.requireNonNull(subscription, "subscription must not be null");
        Objects.requireNonNull(event,        "event must not be null");
        WebhookDelivery d = new WebhookDelivery();
        d.subscriptionId = subscription.getId();
        d.outboxEventId  = event.getId();
        d.targetUrl      = subscription.getTargetUrl();
        d.eventType      = event.getEventType();
        d.payload        = event.getPayload();
        d.status         = WebhookDeliveryStatus.PENDING;
        d.attemptCount   = 0;
        d.maxAttempts    = DEFAULT_MAX_ATTEMPTS;
        return d;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public void markSuccess() {
        this.status        = WebhookDeliveryStatus.SUCCESS;
        this.deliveredAt   = Instant.now();
        this.nextAttemptAt = null;
        this.attemptCount++;
    }

    /**
     * Records a failed delivery attempt and schedules the next retry,
     * or transitions the delivery to {@link WebhookDeliveryStatus#DEAD} once
     * {@link #maxAttempts} is exhausted.
     *
     * <p>Backoff schedule (by attempt number after this call):
     * <pre>
     *   1 →  1 min
     *   2 →  5 min
     *   3 → 15 min
     *   4+ → 60 min
     * </pre>
     */
    public void markFailed(String error) {
        this.attemptCount++;
        this.lastError = truncate(error);
        if (this.attemptCount >= this.maxAttempts) {
            this.status        = WebhookDeliveryStatus.DEAD;
            this.nextAttemptAt = null;
        } else {
            this.status        = WebhookDeliveryStatus.FAILED;
            this.nextAttemptAt = Instant.now().plus(backoffFor(this.attemptCount));
        }
    }

    private static Duration backoffFor(int attemptNumber) {
        return switch (attemptNumber) {
            case 1  -> Duration.ofMinutes(1);
            case 2  -> Duration.ofMinutes(5);
            case 3  -> Duration.ofMinutes(15);
            default -> Duration.ofMinutes(60);
        };
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > MAX_ERROR_LENGTH ? s.substring(0, MAX_ERROR_LENGTH) : s;
    }

    public UUID                 getId()             { return id; }
    public UUID                 getSubscriptionId() { return subscriptionId; }
    public UUID                 getOutboxEventId()  { return outboxEventId; }
    public String               getTargetUrl()      { return targetUrl; }
    public String               getEventType()      { return eventType; }
    public String               getPayload()        { return payload; }
    public WebhookDeliveryStatus getStatus()        { return status; }
    public int                  getAttemptCount()   { return attemptCount; }
    public int                  getMaxAttempts()    { return maxAttempts; }
    public String               getLastError()      { return lastError; }
    public Instant              getNextAttemptAt()  { return nextAttemptAt; }
    public Instant              getCreatedAt()      { return createdAt; }
    public Instant              getDeliveredAt()    { return deliveredAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WebhookDelivery other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() { return getClass().hashCode(); }
}
