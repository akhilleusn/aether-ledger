package com.aetherledger.repository;

import com.aetherledger.domain.entity.OutboxEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
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

    /**
     * Returns all outbox events for the given aggregate (e.g. a transaction UUID),
     * ordered oldest-first.  Used by the audit timeline to surface outbox activity
     * for a specific transaction.
     */
    List<OutboxEvent> findByAggregateIdOrderByCreatedAtAsc(UUID aggregateId);

    /**
     * Fetches a single event with a pessimistic write lock.
     * Used by the relay processor to prevent two concurrent relay instances
     * from publishing the same event twice.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM OutboxEvent e WHERE e.id = :id")
    Optional<OutboxEvent> findByIdWithLock(@Param("id") UUID id);
}
