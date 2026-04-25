package com.aetherledger.repository;

import com.aetherledger.domain.entity.WebhookSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, UUID> {

    /** Returns all active subscriptions, ordered by creation time. */
    List<WebhookSubscription> findByActiveTrueOrderByCreatedAtAsc();

    /** Returns active subscriptions that match the given event type — used by the delivery dispatcher. */
    List<WebhookSubscription> findByEventTypeAndActiveTrue(String eventType);
}
