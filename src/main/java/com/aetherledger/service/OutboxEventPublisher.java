package com.aetherledger.service;

import com.aetherledger.domain.entity.OutboxEvent;

/**
 * Port through which the outbox relay delivers events to an external system.
 *
 * <p>Implementations are called inside a {@code REQUIRES_NEW} transaction.
 * If the method throws, the transaction rolls back and the event stays
 * unpublished so the next relay iteration can retry it.
 *
 * <p>Implementations must be idempotent with respect to external state: a
 * crash after the external publish but before the DB commit means the relay
 * will call this method again for the same event.
 */
public interface OutboxEventPublisher {

    /**
     * Deliver the given event to an external broker or system.
     *
     * @param event the outbox event to publish
     * @throws RuntimeException if the publish attempt fails; the relay will
     *                          catch this and leave the event unpublished for
     *                          retry
     */
    void publish(OutboxEvent event);
}
