package com.aetherledger.api;

import com.aetherledger.api.dto.ErrorResponse;
import com.aetherledger.api.dto.WebhookDeliveryResponse;
import com.aetherledger.domain.enums.WebhookDeliveryStatus;
import com.aetherledger.repository.WebhookDeliveryRepository;
import com.aetherledger.service.WebhookRetryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/webhook-deliveries")
@RequiredArgsConstructor
@Tag(name = "Webhook Deliveries", description = "Inspect and retry failed webhook delivery attempts.")
public class WebhookDeliveryController {

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookRetryService       retryService;

    @Operation(
        summary = "List failed and dead-lettered deliveries",
        description = "Returns all webhook deliveries in FAILED or DEAD status, ordered newest first. " +
            "FAILED deliveries have pending automatic retries; DEAD deliveries have exhausted all attempts."
    )
    @ApiResponse(responseCode = "200", description = "List of failed/dead deliveries (may be empty)")
    @GetMapping("/failed")
    public List<WebhookDeliveryResponse> listFailed() {
        return deliveryRepository
            .findByStatusInOrderByCreatedAtDesc(
                List.of(WebhookDeliveryStatus.FAILED, WebhookDeliveryStatus.DEAD))
            .stream()
            .map(WebhookDeliveryResponse::from)
            .toList();
    }

    @Operation(
        summary = "Manually retry a delivery",
        description = "Immediately retries a FAILED or DEAD delivery, bypassing the backoff schedule. " +
            "Returns the updated delivery record. Retrying a SUCCESS delivery returns 422."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Retry attempted; inspect status for outcome"),
        @ApiResponse(responseCode = "400", description = "Path variable is not a valid UUID",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Delivery not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "422", description = "Delivery is already in SUCCESS status",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/retry")
    public WebhookDeliveryResponse retry(
            @Parameter(description = "Delivery UUID")
            @PathVariable UUID id) {
        log.debug("Manual retry requested: deliveryId={}", id);
        return WebhookDeliveryResponse.from(retryService.retryOne(id));
    }
}
