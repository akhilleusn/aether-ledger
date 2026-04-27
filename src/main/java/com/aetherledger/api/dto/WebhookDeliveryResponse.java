package com.aetherledger.api.dto;

import com.aetherledger.domain.entity.WebhookDelivery;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Representation of a single webhook delivery attempt record")
public record WebhookDeliveryResponse(

    @Schema(description = "System-assigned delivery UUID")
    UUID id,

    @Schema(description = "Subscription that triggered this delivery")
    UUID subscriptionId,

    @Schema(description = "Outbox event that was delivered")
    UUID outboxEventId,

    @Schema(description = "Endpoint that received (or failed to receive) the payload")
    String targetUrl,

    @Schema(description = "Domain event type")
    String eventType,

    @Schema(description = "Delivery lifecycle status", allowableValues = {"PENDING", "SUCCESS", "FAILED", "DEAD"})
    String status,

    @Schema(description = "Total number of delivery attempts made so far")
    int attemptCount,

    @Schema(description = "Maximum number of attempts before the delivery is dead-lettered")
    int maxAttempts,

    @Schema(description = "Error message from the most recent failed attempt, null on success")
    String lastError,

    @Schema(description = "UTC timestamp when the delivery record was created")
    Instant createdAt,

    @Schema(description = "UTC timestamp when the delivery succeeded, null otherwise")
    Instant deliveredAt,

    @Schema(description = "UTC timestamp of the next scheduled retry, null for SUCCESS and DEAD")
    Instant nextAttemptAt

) {

    public static WebhookDeliveryResponse from(WebhookDelivery d) {
        return new WebhookDeliveryResponse(
            d.getId(),
            d.getSubscriptionId(),
            d.getOutboxEventId(),
            d.getTargetUrl(),
            d.getEventType(),
            d.getStatus().name(),
            d.getAttemptCount(),
            d.getMaxAttempts(),
            d.getLastError(),
            d.getCreatedAt(),
            d.getDeliveredAt(),
            d.getNextAttemptAt()
        );
    }
}
