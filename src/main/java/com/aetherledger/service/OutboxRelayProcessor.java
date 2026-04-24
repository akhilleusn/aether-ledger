package com.aetherledger.service;

import com.aetherledger.domain.entity.OutboxEvent;
import com.aetherledger.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Publishes a single outbox event and marks it as delivered, all within its
 * own dedicated transaction.
 *
 * <h3>Why a separate bean?</h3>
 * Spring's AOP proxy intercepts {@code @Transactional} only on calls that
 * cross bean boundaries.  If the {@code REQUIRES_NEW} method lived in the
 * same class as the outer relay loop, Spring would invoke it directly
 * (bypassing the proxy) and the new transaction would never start.  Placing
 * it here ensures the proxy is always in the call path.
 *
 * <h3>Transaction contract</h3>
 * <ul>
 *   <li>Each call runs in a brand-new, independent transaction.</li>
 *   <li>A publisher exception rolls back only this event's transaction —
 *       the caller's loop continues with the next event.</li>
 *   <li>The event row is locked with {@code SELECT … FOR UPDATE} before any
 *       read or write, so two concurrent relay instances cannot publish the
 *       same event twice.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxRelayProcessor {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventPublisher publisher;

    /**
     * Publishes the event identified by {@code eventId} and flips its
     * {@code published} flag to {@code true} within a new transaction.
     *
     * <p>If the event has already been published (e.g., by a concurrent relay
     * that won the lock race), this method returns silently without calling
     * the publisher.
     *
     * @param eventId the ID of the outbox event to publish
     * @throws RuntimeException if the publisher throws; the caller is
     *                          responsible for catching and continuing
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void publishAndMark(UUID eventId) {
        OutboxEvent event = outboxEventRepository.findByIdWithLock(eventId)
            .orElseThrow(() -> new IllegalStateException(
                "Outbox event not found during relay: id=" + eventId));

        if (event.isPublished()) {
            log.debug("Outbox event already published by concurrent relay — skipping: id={}", eventId);
            return;
        }

        publisher.publish(event);

        event.markPublished();
        outboxEventRepository.save(event);

        log.debug("Outbox event marked published: id={} type={} publishedAt={}",
            event.getId(), event.getEventType(), event.getPublishedAt());
    }
}
