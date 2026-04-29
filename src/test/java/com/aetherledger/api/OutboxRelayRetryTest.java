package com.aetherledger.api;

import com.aetherledger.domain.entity.OutboxEvent;
import com.aetherledger.domain.enums.OutboxEventType;
import com.aetherledger.repository.OutboxEventRepository;
import com.aetherledger.repository.WebhookDeliveryRepository;
import com.aetherledger.repository.WebhookSubscriptionRepository;
import com.aetherledger.service.LoggingOutboxEventPublisher;
import com.aetherledger.service.OutboxRelayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * Integration tests verifying that a transiently failing event is retried and
 * published successfully on a subsequent relay pass.
 *
 * <p>{@code @SpyBean} replaces the real {@link LoggingOutboxEventPublisher}
 * with a Mockito spy so the test can control whether a specific call succeeds
 * or throws.  Because {@code @SpyBean} modifies the application context,
 * {@code @DirtiesContext} is required so the altered context is not reused by
 * other test classes.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext
@DisplayName("Outbox relay — transient failure and retry")
class OutboxRelayRetryTest extends AbstractIntegrationTest {

    @Autowired OutboxRelayService            relayService;
    @Autowired OutboxEventRepository         outboxEventRepository;
    @Autowired WebhookDeliveryRepository     webhookDeliveryRepository;
    @Autowired WebhookSubscriptionRepository webhookSubscriptionRepository;

    @SpyBean LoggingOutboxEventPublisher publisher;

    @BeforeEach
    void setUp() {
        resetDatabase();
        reset(publisher);
    }

    @Test
    @DisplayName("event that fails on the first relay pass is published on the second pass")
    void retryPublishesEventOnSecondRun() {
        OutboxEvent event = outboxEventRepository.save(
            OutboxEvent.of(OutboxEventType.TRANSACTION_POSTED,
                UUID.randomUUID(),
                "TEST_AGGREGATE",
                "{\"test\":\"retry payload\"}"));

        // First relay pass: publisher throws a transient error.
        doThrow(new RuntimeException("transient broker failure"))
            .when(publisher).publish(any());

        OutboxRelayService.RelayResult firstResult = relayService.relay();

        assertThat(firstResult.failed()).isEqualTo(1);
        assertThat(firstResult.published()).isZero();
        assertThat(outboxEventRepository.findById(event.getId()).orElseThrow().isPublished())
            .isFalse();

        // Second relay pass: publisher recovers (spy reset to real behaviour).
        reset(publisher);
        doNothing().when(publisher).publish(any());

        OutboxRelayService.RelayResult secondResult = relayService.relay();

        assertThat(secondResult.published()).isEqualTo(1);
        assertThat(secondResult.failed()).isZero();

        OutboxEvent published = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(published.isPublished()).isTrue();
        assertThat(published.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("event remains in unpublished queue after persistent failure across multiple passes")
    void persistentFailureKeepsEventUnpublished() {
        OutboxEvent event = outboxEventRepository.save(
            OutboxEvent.of(OutboxEventType.TRANSACTION_POSTED,
                UUID.randomUUID(),
                "TEST_AGGREGATE",
                "{\"test\":\"retry payload\"}"));

        doThrow(new RuntimeException("persistent failure"))
            .when(publisher).publish(any());

        relayService.relay();
        relayService.relay();
        relayService.relay();

        assertThat(outboxEventRepository.findById(event.getId()).orElseThrow().isPublished())
            .isFalse();
        assertThat(outboxEventRepository.countByPublishedFalse()).isEqualTo(1);
    }
}
