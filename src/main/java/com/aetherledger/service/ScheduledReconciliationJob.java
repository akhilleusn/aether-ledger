package com.aetherledger.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers the scheduled reconciliation job at a configurable fixed rate.
 *
 * <p>Enabled only when {@code aetherledger.reconciliation.scheduled.enabled=true}
 * (the default).  Set this property to {@code false} in the test profile to
 * prevent background job execution during integration tests.
 *
 * <p>The interval is controlled by
 * {@code aetherledger.reconciliation.interval-ms} (default: 30 000 ms).
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "aetherledger.reconciliation.scheduled.enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class ScheduledReconciliationJob {

    private final ScheduledReconciliationService reconciliationService;

    @Scheduled(fixedRateString = "${aetherledger.reconciliation.interval-ms:30000}")
    public void run() {
        log.debug("Scheduled reconciliation job triggered");
        reconciliationService.processUnreconciled();
    }
}
