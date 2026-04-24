package com.aetherledger.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers the outbox relay at a configurable fixed rate.
 *
 * <p>Enabled only when {@code outbox.relay.enabled=true} (the default).
 * Set this property to {@code false} in the test profile to prevent the job
 * from interfering with test assertions.
 *
 * <p>The interval is controlled by {@code outbox.relay.interval}
 * (default: 30 000 ms).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "outbox.relay.enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class OutboxRelayJob {

    private final OutboxRelayService relayService;

    @Scheduled(fixedRateString = "${outbox.relay.interval:30000}")
    public void run() {
        log.info("Outbox relay job started");
        OutboxRelayService.RelayResult result = relayService.relay();
        log.info("Outbox relay job complete: total={} published={} failed={}",
            result.total(), result.published(), result.failed());
    }
}
