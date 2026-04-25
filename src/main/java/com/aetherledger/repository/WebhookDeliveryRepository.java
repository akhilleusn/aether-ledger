package com.aetherledger.repository;

import com.aetherledger.domain.entity.WebhookDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    List<WebhookDelivery> findBySubscriptionId(UUID subscriptionId);

    List<WebhookDelivery> findByOutboxEventId(UUID outboxEventId);
}
