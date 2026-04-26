package com.aetherledger.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Central registry of all AetherLedger application-level metrics.
 *
 * <p>Wraps {@link MeterRegistry} with named methods so metric names and tags
 * are defined in one place and call sites stay clean.  Every counter is
 * idempotent — Micrometer returns the existing counter if it has already been
 * registered with the same name and tags.
 *
 * <p>Exposed via {@code GET /actuator/metrics/aetherledger.*}.
 */
@Service
@RequiredArgsConstructor
public class LedgerMetrics {

    private final MeterRegistry registry;

    // -------------------------------------------------------------------------
    // Transactions
    // -------------------------------------------------------------------------

    /** Immediate SUCCESS transaction posted via {@code POST /ledger-transactions}. */
    public void transactionPosted() {
        counter("aetherledger.transactions.posted",
            "Total immediate SUCCESS transactions posted");
    }

    /** PENDING transaction promoted to SUCCESS via the complete endpoint. */
    public void transactionCompleted() {
        counter("aetherledger.transactions.completed",
            "Pending transactions completed to SUCCESS");
    }

    /** PENDING transaction transitioned to FAILED via the fail endpoint. */
    public void transactionFailed() {
        counter("aetherledger.transactions.failed",
            "Pending transactions marked FAILED");
    }

    /** Reversal transaction posted. */
    public void transactionReversed() {
        counter("aetherledger.transactions.reversed",
            "Transactions reversed");
    }

    // -------------------------------------------------------------------------
    // Reconciliation (API-triggered)
    // -------------------------------------------------------------------------

    /** API-triggered reconciliation with result MATCHED. */
    public void reconciliationMatched() {
        counter("aetherledger.reconciliation.matched",
            "Transactions reconciled with status MATCHED");
    }

    /** API-triggered reconciliation with result STATUS_MISMATCH. */
    public void reconciliationMismatched() {
        counter("aetherledger.reconciliation.mismatched",
            "Transactions reconciled with status STATUS_MISMATCH");
    }

    // -------------------------------------------------------------------------
    // Scheduled reconciliation job
    // -------------------------------------------------------------------------

    public void scheduledReconciliationProcessed() {
        counter("aetherledger.scheduled.reconciliation.processed",
            "Transactions processed by the scheduled reconciliation job");
    }

    public void scheduledReconciliationSkipped() {
        counter("aetherledger.scheduled.reconciliation.skipped",
            "Transactions skipped (already claimed) by the scheduled reconciliation job");
    }

    public void scheduledReconciliationFailed() {
        counter("aetherledger.scheduled.reconciliation.failed",
            "Transactions that failed during scheduled reconciliation");
    }

    // -------------------------------------------------------------------------
    // Outbox relay
    // -------------------------------------------------------------------------

    /** Outbox event successfully delivered to the broker, tagged by event type. */
    public void outboxPublished(String eventType) {
        Counter.builder("aetherledger.outbox.published")
            .description("Outbox events successfully published to the broker")
            .tag("eventType", eventType)
            .register(registry)
            .increment();
    }

    /** Outbox event that failed to publish (broker error). */
    public void outboxPublishFailed() {
        counter("aetherledger.outbox.publish.failures",
            "Outbox events that failed to publish");
    }

    // -------------------------------------------------------------------------
    // Webhook deliveries
    // -------------------------------------------------------------------------

    /** Webhook delivery that completed successfully, tagged by event type. */
    public void webhookDelivered(String eventType) {
        Counter.builder("aetherledger.webhooks.delivered")
            .description("Successful webhook deliveries")
            .tag("eventType", eventType)
            .register(registry)
            .increment();
    }

    /** Webhook delivery that failed, tagged by event type. */
    public void webhookFailed(String eventType) {
        Counter.builder("aetherledger.webhooks.failed")
            .description("Failed webhook deliveries")
            .tag("eventType", eventType)
            .register(registry)
            .increment();
    }

    // -------------------------------------------------------------------------
    // Holds
    // -------------------------------------------------------------------------

    public void holdCreated() {
        counter("aetherledger.holds.created", "Holds placed on accounts");
    }

    public void holdCaptured() {
        counter("aetherledger.holds.captured", "Holds captured (funds moved to clearing)");
    }

    public void holdReleased() {
        counter("aetherledger.holds.released", "Holds released (reservation cancelled)");
    }

    // -------------------------------------------------------------------------
    // Internal helper
    // -------------------------------------------------------------------------

    private void counter(String name, String description) {
        Counter.builder(name)
            .description(description)
            .register(registry)
            .increment();
    }
}
