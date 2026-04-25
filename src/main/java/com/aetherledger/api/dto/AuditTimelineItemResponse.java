package com.aetherledger.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

@Schema(description = "A single event in a transaction's chronological audit timeline")
public record AuditTimelineItemResponse(

    @Schema(description = "Type of event",
            allowableValues = {
                "TRANSACTION_CREATED",
                "LEDGER_ENTRIES_WRITTEN",
                "REVERSAL_OF",
                "REVERSED_BY",
                "RECONCILIATION_RECORDED",
                "OUTBOX_EVENT_QUEUED",
                "OUTBOX_EVENT_PUBLISHED"
            })
    String eventType,

    @Schema(description = "UTC timestamp when this event occurred")
    Instant occurredAt,

    @Schema(description = "Human-readable description of the event")
    String description,

    @Schema(description = "System component that produced this event",
            allowableValues = {"ledger", "reconciliation", "outbox"})
    String source,

    @Schema(description = "Additional key-value pairs providing event-specific context")
    Map<String, Object> metadata
) {}
