package com.aetherledger.service;

import com.aetherledger.domain.entity.OutboxEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Simulated {@link OutboxEventPublisher} that logs each event instead of
 * forwarding to a real broker (Kafka, SNS, etc.).
 *
 * <p><strong>Failure simulation</strong><br>
 * If the event's payload contains the literal string {@value #FAIL_TRIGGER},
 * this implementation throws a {@link RuntimeException} to simulate a
 * transient broker failure.  The relay will catch the exception, leave the
 * event unpublished, and retry it on the next iteration.
 *
 * <p>Replace this bean with a real broker adapter (e.g., a Kafka producer)
 * when the project is ready to integrate.
 */
@Slf4j
@Component
public class LoggingOutboxEventPublisher implements OutboxEventPublisher {

    static final String FAIL_TRIGGER = "FAIL_PUBLISH";

    @Override
    public void publish(OutboxEvent event) {
        if (event.getPayload().contains(FAIL_TRIGGER)) {
            throw new RuntimeException(
                "Simulated publish failure for event id=" + event.getId()
                    + " type=" + event.getEventType());
        }

        log.info("Publishing event: type={} aggregateId={} payload={}",
            event.getEventType(), event.getAggregateId(), event.getPayload());
    }
}
