package com.aetherledger.repository;

import com.aetherledger.domain.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence port for the transactional outbox.
 *
 * <p>The relay that forwards events to a message broker uses
 * {@link #findTop100ByPublishedFalseOrderByCreatedAtAsc()} to fetch the next
 * batch of undelivered events in arrival order.  The cap of 100 bounds each
 * relay iteration's work unit; a configurable page size can replace this when
 * needed.
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Returns up to 100 events that have not yet been delivered to a broker,
     * oldest first.
     */
    List<OutboxEvent> findTop100ByPublishedFalseOrderByCreatedAtAsc();

    /** Count of events pending delivery — useful for monitoring and alerting. */
    long countByPublishedFalse();
}
