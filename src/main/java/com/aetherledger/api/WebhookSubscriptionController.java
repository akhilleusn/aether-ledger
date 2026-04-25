package com.aetherledger.api;

import com.aetherledger.api.dto.CreateWebhookSubscriptionRequest;
import com.aetherledger.api.dto.ErrorResponse;
import com.aetherledger.api.dto.WebhookSubscriptionResponse;
import com.aetherledger.service.WebhookSubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for webhook subscription management.
 *
 * <p>External callers register a URL and event type; the ledger platform
 * delivers a POST to that URL each time a matching domain event is published
 * by the outbox relay.
 */
@RestController
@RequestMapping("/api/v1/webhook-subscriptions")
@RequiredArgsConstructor
@Tag(name = "Webhook Subscriptions",
     description = "Register external URLs to receive real-time webhook deliveries for ledger domain events.")
public class WebhookSubscriptionController {

    private final WebhookSubscriptionService webhookSubscriptionService;

    @Operation(
        summary = "Create a webhook subscription",
        description = "Registers a target URL to receive webhook deliveries for the specified domain event type. " +
            "Only http and https URLs are accepted. The subscription is active immediately."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Subscription created"),
        @ApiResponse(responseCode = "400", description = "Invalid URL or unknown event type",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WebhookSubscriptionResponse create(@RequestBody @Valid CreateWebhookSubscriptionRequest request) {
        return WebhookSubscriptionResponse.from(
            webhookSubscriptionService.create(request.targetUrl(), request.eventType())
        );
    }

    @Operation(
        summary = "List active webhook subscriptions",
        description = "Returns all subscriptions that have not been deactivated, ordered by creation time."
    )
    @ApiResponse(responseCode = "200", description = "List of active subscriptions (may be empty)")
    @GetMapping
    public List<WebhookSubscriptionResponse> listActive() {
        return webhookSubscriptionService.listActive().stream()
            .map(WebhookSubscriptionResponse::from)
            .toList();
    }

    @Operation(
        summary = "Deactivate a webhook subscription",
        description = "Soft-deletes the subscription by setting active=false. " +
            "No further deliveries will be attempted for this subscription. " +
            "Existing delivery history is preserved."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Subscription deactivated"),
        @ApiResponse(responseCode = "400", description = "Path variable is not a valid UUID",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Subscription not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(
            @Parameter(description = "Subscription UUID")
            @PathVariable UUID id) {
        webhookSubscriptionService.deactivate(id);
    }
}
