package com.aetherledger.service;

import com.aetherledger.domain.entity.OutboxEvent;
import com.aetherledger.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates a single relay pass: fetches up to 100 unpublished outbox
 * events and delegates each one to {@link OutboxRelayProcessor} for
 * publication and marking.
 *
 * <h3>Transaction design</h3>
 * This method is intentionally <em>non-transactional</em>.  The initial read
 * of unpublished events runs in the short-lived, auto-committed transaction
 * that Spring Data JPA opens for repository calls.  Each subsequent
 * {@link OutboxRelayProcessor#publishAndMark} call opens its own independent
 * {@code REQUIRES_NEW} transaction.  This isolation means:
 * <ul>
 *   <li>A publisher failure for event N does not roll back events 1…N-1.</li>
 *   <li>The outer loop continues processing events N+1…100 regardless.</li>
 *   <li>Failed events remain with {@code published = false} and are picked
 *       up automatically by the next scheduled relay pass.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxRelayService {

    private final OutboxEventRepository  outboxEventRepository;
    private final OutboxRelayProcessor   processor;
    private final WebhookDeliveryService webhookDeliveryService;
    private final LedgerMetrics          ledgerMetrics;

    /**
     * Executes one relay pass.
     *
     * @return a {@link RelayResult} summarising how many events were
     *         attempted, published, and failed
     */
    public RelayResult relay() {
        List<OutboxEvent> candidates =
            outboxEventRepository.findTop100ByPublishedFalseOrderByCreatedAtAsc();

        int published = 0;
        int failed    = 0;

        for (OutboxEvent event : candidates) {
            try {
                processor.publishAndMark(event.getId());
                published++;
                ledgerMetrics.outboxPublished(event.getEventType());
                // Dispatch webhooks in a separate try-catch: a delivery failure
                // must never affect the outbox relay's published/failed counts.
                try {
                    webhookDeliveryService.dispatchForEvent(event);
                } catch (Exception e) {
                    log.warn("Webhook dispatch failed for outbox event id={}: {}",
                        event.getId(), e.getMessage());
                }
            } catch (Exception e) {
                log.warn("Failed to publish outbox event: id={} type={} reason={}",
                    event.getId(), event.getEventType(), e.getMessage());
                failed++;
                ledgerMetrics.outboxPublishFailed();
            }
        }

        return new RelayResult(candidates.size(), published, failed);
    }

    /**
     * Immutable summary of a single relay pass.
     *
     * @param total     number of unpublished events found at the start of the pass
     * @param published number of events successfully published and marked
     * @param failed    number of events that threw during publish (left unpublished)
     */
    public record RelayResult(int total, int published, int failed) {}
}
