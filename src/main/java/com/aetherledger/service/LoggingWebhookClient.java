package com.aetherledger.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Simulated {@link WebhookClient} that logs each delivery attempt without
 * making a real HTTP call.
 *
 * <p><strong>Failure simulation</strong><br>
 * If {@code targetUrl} contains the literal string {@value #FAIL_TRIGGER},
 * this implementation returns a failure result to simulate a delivery error.
 * The relay records the delivery as {@code FAILED} and stores the error
 * message, but does not rethrow — the outbox relay continues normally.
 *
 * <p>Replace this bean with a real HTTP adapter when live delivery is needed.
 */
@Slf4j
@Component
public class LoggingWebhookClient implements WebhookClient {

    static final String FAIL_TRIGGER = "fail";

    @Override
    public SendResult send(String targetUrl, String payload, Map<String, String> headers) {
        if (targetUrl.contains(FAIL_TRIGGER)) {
            log.warn("Webhook delivery failed (simulated): eventType={} targetUrl={}",
                headers.get("X-AetherLedger-Event-Type"), targetUrl);
            return SendResult.failure("Simulated delivery failure for target: " + targetUrl);
        }

        log.info("Webhook delivered: eventType={} eventId={} targetUrl={}",
            headers.get("X-AetherLedger-Event-Type"),
            headers.get("X-AetherLedger-Event-Id"),
            targetUrl);
        return SendResult.success();
    }
}
