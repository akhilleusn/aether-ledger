package com.aetherledger.service;

import com.aetherledger.domain.entity.WebhookDelivery;
import com.aetherledger.domain.enums.WebhookDeliveryStatus;
import com.aetherledger.exception.WebhookDeliveryNotFoundException;
import com.aetherledger.exception.WebhookDeliveryNotRetryableException;
import com.aetherledger.repository.WebhookDeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Retries failed webhook deliveries and manages the dead-letter lifecycle.
 *
 * <h3>Scheduled retry</h3>
 * {@link #retryDue()} is called by {@link WebhookRetryJob} at a configurable
 * fixed rate.  It processes every {@code FAILED} delivery whose
 * {@code nextAttemptAt} has elapsed, applying the same backoff / dead-letter
 * logic as the original delivery attempt.
 *
 * <h3>Manual retry</h3>
 * {@link #retryOne(UUID)} allows an operator to force a single retry of any
 * {@code FAILED} or {@code DEAD} delivery.  Retrying a {@code SUCCESS}
 * delivery returns {@code 422}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookRetryService {

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookClient             webhookClient;
    private final LedgerMetrics             ledgerMetrics;

    // -------------------------------------------------------------------------
    // Scheduled retry
    // -------------------------------------------------------------------------

    /**
     * Processes all FAILED deliveries that are due for a retry.
     *
     * @return summary of this retry pass
     */
    @Transactional(rollbackFor = Exception.class)
    public RetryResult retryDue() {
        List<WebhookDelivery> due =
            deliveryRepository.findDueForRetry(WebhookDeliveryStatus.FAILED, Instant.now());

        int attempted = 0, succeeded = 0, failed = 0;

        for (WebhookDelivery delivery : due) {
            attempted++;
            boolean wentDead = attempt(delivery);
            if (delivery.getStatus() == WebhookDeliveryStatus.SUCCESS) {
                succeeded++;
            } else {
                failed++;
                if (wentDead) {
                    ledgerMetrics.webhookDeadLettered(delivery.getEventType());
                }
            }
            deliveryRepository.save(delivery);
        }

        return new RetryResult(attempted, succeeded, failed);
    }

    // -------------------------------------------------------------------------
    // Manual retry
    // -------------------------------------------------------------------------

    /**
     * Immediately retries a single delivery.
     *
     * @throws WebhookDeliveryNotFoundException     if {@code id} does not exist
     * @throws WebhookDeliveryNotRetryableException if the delivery is already SUCCESS
     */
    @Transactional(rollbackFor = Exception.class)
    public WebhookDelivery retryOne(UUID id) {
        WebhookDelivery delivery = deliveryRepository.findById(id)
            .orElseThrow(() -> new WebhookDeliveryNotFoundException(id));

        if (delivery.getStatus() == WebhookDeliveryStatus.SUCCESS) {
            throw new WebhookDeliveryNotRetryableException(id, delivery.getStatus());
        }

        boolean prevDead = delivery.getStatus() == WebhookDeliveryStatus.DEAD;
        boolean wentDead = attempt(delivery);

        if (wentDead && !prevDead) {
            ledgerMetrics.webhookDeadLettered(delivery.getEventType());
        }

        return deliveryRepository.save(delivery);
    }

    // -------------------------------------------------------------------------
    // Core attempt logic
    // -------------------------------------------------------------------------

    /**
     * Sends the delivery payload and updates the entity in place.
     *
     * @return {@code true} if the delivery transitioned to DEAD on this attempt
     */
    private boolean attempt(WebhookDelivery delivery) {
        Map<String, String> headers = buildHeaders(delivery);
        WebhookClient.SendResult result =
            webhookClient.send(delivery.getTargetUrl(), delivery.getPayload(), headers);

        ledgerMetrics.webhookRetryAttempted(delivery.getEventType());

        if (result.succeeded()) {
            delivery.markSuccess();
            ledgerMetrics.webhookRetrySucceeded(delivery.getEventType());
            log.info("Webhook retry succeeded: deliveryId={} targetUrl={}",
                delivery.getId(), delivery.getTargetUrl());
            return false;
        } else {
            WebhookDeliveryStatus prevStatus = delivery.getStatus();
            delivery.markFailed(result.errorMessage());
            ledgerMetrics.webhookRetryFailed(delivery.getEventType());
            boolean wentDead = delivery.getStatus() == WebhookDeliveryStatus.DEAD
                && prevStatus != WebhookDeliveryStatus.DEAD;
            log.warn("Webhook retry failed: deliveryId={} attemptCount={} status={} error={}",
                delivery.getId(), delivery.getAttemptCount(),
                delivery.getStatus(), result.errorMessage());
            return wentDead;
        }
    }

    private static Map<String, String> buildHeaders(WebhookDelivery delivery) {
        return Map.of(
            "X-AetherLedger-Event-Type", delivery.getEventType(),
            "X-AetherLedger-Event-Id",   delivery.getOutboxEventId().toString(),
            "X-AetherLedger-Signature",  buildSignature(delivery.getOutboxEventId(), delivery.getSubscriptionId())
        );
    }

    private static String buildSignature(UUID eventId, UUID subscriptionId) {
        int hash = Objects.hash(eventId, subscriptionId);
        return "placeholder=" + Integer.toHexString(Math.abs(hash));
    }

    // -------------------------------------------------------------------------
    // Result type
    // -------------------------------------------------------------------------

    public record RetryResult(int attempted, int succeeded, int failed) {}
}
