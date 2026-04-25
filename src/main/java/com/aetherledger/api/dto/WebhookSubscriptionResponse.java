package com.aetherledger.api.dto;

import com.aetherledger.domain.entity.WebhookSubscription;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "A registered webhook subscription")
public record WebhookSubscriptionResponse(

    @Schema(description = "System-assigned subscription UUID")
    UUID id,

    @Schema(description = "Target URL that receives webhook deliveries")
    String targetUrl,

    @Schema(description = "Domain event type this subscription listens for")
    String eventType,

    @Schema(description = "Whether this subscription is active; false means deactivated")
    boolean active,

    @Schema(description = "UTC timestamp when the subscription was created")
    Instant createdAt
) {

    public static WebhookSubscriptionResponse from(WebhookSubscription sub) {
        return new WebhookSubscriptionResponse(
            sub.getId(),
            sub.getTargetUrl(),
            sub.getEventType(),
            sub.isActive(),
            sub.getCreatedAt()
        );
    }
}
