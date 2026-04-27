package com.aetherledger.repository;

import com.aetherledger.domain.entity.WebhookDelivery;
import com.aetherledger.domain.enums.WebhookDeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    List<WebhookDelivery> findBySubscriptionId(UUID subscriptionId);

    List<WebhookDelivery> findByOutboxEventId(UUID outboxEventId);

    /**
     * Returns FAILED and DEAD deliveries ordered newest first — used by the
     * admin failed-deliveries endpoint.
     */
    List<WebhookDelivery> findByStatusInOrderByCreatedAtDesc(List<WebhookDeliveryStatus> statuses);

    /**
     * Returns FAILED deliveries whose next retry window has arrived,
     * ordered oldest-due first so long-waiting deliveries are prioritised.
     */
    @Query("""
        SELECT d FROM WebhookDelivery d
        WHERE d.status     = :status
          AND d.nextAttemptAt <= :now
        ORDER BY d.nextAttemptAt ASC
        """)
    List<WebhookDelivery> findDueForRetry(
        @Param("status") WebhookDeliveryStatus status,
        @Param("now")    Instant now);

    /**
     * Backdates {@code nextAttemptAt} on a single delivery — used exclusively
     * in integration tests to make a delivery immediately retryable without
     * waiting for the real backoff delay.
     */
    @Modifying
    @Transactional
    @Query("UPDATE WebhookDelivery d SET d.nextAttemptAt = :nextAttemptAt WHERE d.id = :id")
    void setNextAttemptAt(@Param("id") UUID id, @Param("nextAttemptAt") Instant nextAttemptAt);
}
