package com.aetherledger.api.dto;

import com.aetherledger.domain.enums.OutboxEventType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(description = "Request body for creating a new webhook subscription")
public record CreateWebhookSubscriptionRequest(

    @Schema(description = "Target URL to receive webhook deliveries (http or https only)",
            example = "https://example.com/webhooks/ledger")
    @NotBlank(message = "targetUrl is required")
    @Pattern(
        regexp = "^https?://\\S{2,}$",
        message = "targetUrl must be a valid http or https URL"
    )
    String targetUrl,

    @Schema(description = "Domain event type to subscribe to",
            allowableValues = {
                "TRANSACTION_POSTED", "TRANSACTION_COMPLETED", "TRANSACTION_REVERSED",
                "TRANSACTION_RECONCILED", "BATCH_RECONCILIATION_COMPLETED"
            })
    @NotNull(message = "eventType is required")
    OutboxEventType eventType
) {}
