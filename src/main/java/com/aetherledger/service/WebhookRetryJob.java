package com.aetherledger.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers the webhook retry pass at a configurable fixed rate.
 *
 * <p>Enabled only when {@code webhooks.retry.scheduled.enabled=true} (the
 * default).  Set this property to {@code false} in the test profile to prevent
 * background retries from interfering with integration-test assertions.
 *
 * <p>The interval is controlled by {@code webhooks.retry.interval}
 * (default: 60 000 ms = 1 minute).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name         = "webhooks.retry.scheduled.enabled",
    havingValue  = "true",
    matchIfMissing = true
)
public class WebhookRetryJob {

    private final WebhookRetryService retryService;

    @Scheduled(fixedRateString = "${webhooks.retry.interval:60000}")
    public void run() {
        log.debug("Webhook retry job triggered");
        WebhookRetryService.RetryResult result = retryService.retryDue();
        if (result.attempted() > 0) {
            log.info("Webhook retry job complete: attempted={} succeeded={} failed={}",
                result.attempted(), result.succeeded(), result.failed());
        }
    }
}
