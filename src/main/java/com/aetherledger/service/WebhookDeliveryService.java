package com.aetherledger.service;

import com.aetherledger.domain.entity.OutboxEvent;
import com.aetherledger.domain.entity.WebhookDelivery;
import com.aetherledger.domain.entity.WebhookSubscription;
import com.aetherledger.repository.WebhookDeliveryRepository;
import com.aetherledger.repository.WebhookSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Dispatches a published outbox event to all active webhook subscriptions
 * that match its event type.
 *
 * <p>Each call creates one {@link WebhookDelivery} record per matching
 * subscription and invokes {@link WebhookClient#send} to deliver the payload.
 * Delivery outcomes ({@code SUCCESS} or {@code FAILED}) are persisted regardless
 * of the send result — the outbox relay is never affected by webhook failures.
 *
 * <h3>Security headers</h3>
 * Every delivery carries three headers:
 * <ul>
 *   <li>{@code X-AetherLedger-Event-Type} — the domain event type</li>
 *   <li>{@code X-AetherLedger-Event-Id}   — the outbox event UUID</li>
 *   <li>{@code X-AetherLedger-Signature}  — deterministic placeholder;
 *       replace with HMAC-SHA256 keyed on a per-subscription secret</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookDeliveryService {

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository     deliveryRepository;
    private final WebhookClient                 webhookClient;

    /**
     * Finds all active subscriptions matching the event's type and delivers
     * the payload to each one, recording the outcome as a {@link WebhookDelivery}.
     *
     * <p>Called by the outbox relay after successfully marking an event as
     * published.  This method must never throw — failures are swallowed by the
     * relay's outer try-catch.
     *
     * @param event the outbox event that was just published (fields are immutable
     *              so the stale pre-publish entity is safe to pass here)
     */
    @Transactional
    public void dispatchForEvent(OutboxEvent event) {
        List<WebhookSubscription> subscriptions =
            subscriptionRepository.findByEventTypeAndActiveTrue(event.getEventType());

        if (subscriptions.isEmpty()) {
            log.debug("No active webhook subscriptions for eventType={}", event.getEventType());
            return;
        }

        log.debug("Dispatching eventType={} to {} subscription(s)",
            event.getEventType(), subscriptions.size());

        for (WebhookSubscription sub : subscriptions) {
            WebhookDelivery delivery = WebhookDelivery.create(sub, event);
            Map<String, String> headers = buildHeaders(event, sub);

            WebhookClient.SendResult result =
                webhookClient.send(sub.getTargetUrl(), event.getPayload(), headers);

            if (result.succeeded()) {
                delivery.markSuccess();
                log.debug("Webhook delivery succeeded: subscriptionId={} eventId={}",
                    sub.getId(), event.getId());
            } else {
                delivery.markFailed(result.errorMessage());
                log.warn("Webhook delivery failed: subscriptionId={} eventId={} error={}",
                    sub.getId(), event.getId(), result.errorMessage());
            }

            deliveryRepository.save(delivery);
        }
    }

    private static Map<String, String> buildHeaders(OutboxEvent event, WebhookSubscription sub) {
        return Map.of(
            "X-AetherLedger-Event-Type", event.getEventType(),
            "X-AetherLedger-Event-Id",   event.getId().toString(),
            "X-AetherLedger-Signature",  buildSignature(event.getId(), sub.getId())
        );
    }

    /**
     * Deterministic placeholder signature.
     * Replace with HMAC-SHA256 keyed on a per-subscription secret when
     * secret management is in place — no secrets are involved here.
     */
    private static String buildSignature(UUID eventId, UUID subscriptionId) {
        int hash = Objects.hash(eventId, subscriptionId);
        return "placeholder=" + Integer.toHexString(Math.abs(hash));
    }
}
