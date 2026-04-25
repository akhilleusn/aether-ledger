package com.aetherledger.api;

import com.aetherledger.domain.entity.OutboxEvent;
import com.aetherledger.domain.enums.OutboxEventType;
import com.aetherledger.repository.OutboxEventRepository;
import com.aetherledger.repository.WebhookDeliveryRepository;
import com.aetherledger.repository.WebhookSubscriptionRepository;
import com.aetherledger.service.OutboxRelayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the outbox relay.
 *
 * <p>Events are inserted directly via the repository so tests remain
 * independent of business-layer logic.  The relay is invoked explicitly;
 * the scheduled job is disabled in the test profile.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Outbox relay")
class OutboxRelayTest extends AbstractIntegrationTest {

    @Autowired OutboxRelayService            relayService;
    @Autowired OutboxEventRepository         outboxEventRepository;
    @Autowired WebhookDeliveryRepository     webhookDeliveryRepository;
    @Autowired WebhookSubscriptionRepository webhookSubscriptionRepository;

    @BeforeEach
    void setUp() {
        webhookDeliveryRepository.deleteAllInBatch();
        webhookSubscriptionRepository.deleteAllInBatch();
        outboxEventRepository.deleteAllInBatch();
    }

    // =========================================================================
    // Basic publish
    // =========================================================================

    @Nested
    @DisplayName("successful publish")
    class SuccessfulPublish {

        @Test
        @DisplayName("unpublished events are published after relay runs")
        void unpublishedEventsArePublished() {
            saveNormalEvent();
            saveNormalEvent();
            saveNormalEvent();

            relayService.relay();

            assertThat(outboxEventRepository.countByPublishedFalse()).isZero();
            assertThat(outboxEventRepository.findAll())
                .allMatch(OutboxEvent::isPublished);
        }

        @Test
        @DisplayName("published flag becomes true after relay")
        void publishedFlagBecomesTrue() {
            OutboxEvent saved = saveNormalEvent();
            assertThat(saved.isPublished()).isFalse();

            relayService.relay();

            OutboxEvent after = outboxEventRepository.findById(saved.getId()).orElseThrow();
            assertThat(after.isPublished()).isTrue();
        }

        @Test
        @DisplayName("publishedAt is set to a non-null timestamp after relay")
        void publishedAtIsSet() {
            OutboxEvent saved = saveNormalEvent();
            assertThat(saved.getPublishedAt()).isNull();

            Instant before = Instant.now();
            relayService.relay();
            Instant after = Instant.now();

            OutboxEvent refreshed = outboxEventRepository.findById(saved.getId()).orElseThrow();
            assertThat(refreshed.getPublishedAt())
                .isNotNull()
                .isBetween(before, after);
        }

        @Test
        @DisplayName("relay result counts match actual publish outcomes")
        void relayResultCountsAreCorrect() {
            saveNormalEvent();
            saveNormalEvent();

            OutboxRelayService.RelayResult result = relayService.relay();

            assertThat(result.total()).isEqualTo(2);
            assertThat(result.published()).isEqualTo(2);
            assertThat(result.failed()).isZero();
        }

        @Test
        @DisplayName("already-published events are not reprocessed on a second relay pass")
        void alreadyPublishedEventsAreSkipped() {
            saveNormalEvent();

            OutboxRelayService.RelayResult first = relayService.relay();
            assertThat(first.published()).isEqualTo(1);

            OutboxRelayService.RelayResult second = relayService.relay();
            assertThat(second.total()).isZero();
            assertThat(second.published()).isZero();
        }
    }

    // =========================================================================
    // Failure handling
    // =========================================================================

    @Nested
    @DisplayName("failure handling")
    class FailureHandling {

        @Test
        @DisplayName("failed publish leaves the event unpublished")
        void failedPublishRemainsUnpublished() {
            OutboxEvent failEvent = saveFailEvent();

            relayService.relay();

            OutboxEvent refreshed = outboxEventRepository.findById(failEvent.getId()).orElseThrow();
            assertThat(refreshed.isPublished()).isFalse();
            assertThat(refreshed.getPublishedAt()).isNull();
        }

        @Test
        @DisplayName("failure in one event does not prevent others from being published")
        void failureInOneEventDoesNotStopBatch() {
            OutboxEvent good1 = saveNormalEvent();
            OutboxEvent fail  = saveFailEvent();
            OutboxEvent good2 = saveNormalEvent();

            OutboxRelayService.RelayResult result = relayService.relay();

            assertThat(result.published()).isEqualTo(2);
            assertThat(result.failed()).isEqualTo(1);

            assertThat(outboxEventRepository.findById(good1.getId()).orElseThrow().isPublished()).isTrue();
            assertThat(outboxEventRepository.findById(fail.getId()).orElseThrow().isPublished()).isFalse();
            assertThat(outboxEventRepository.findById(good2.getId()).orElseThrow().isPublished()).isTrue();
        }

        @Test
        @DisplayName("failed event remains in the unpublished queue for the next relay pass")
        void failedEventIsRetainedForRetry() {
            saveFailEvent();

            relayService.relay();

            // The event is still unpublished — the next relay pass will attempt it again.
            assertThat(outboxEventRepository.countByPublishedFalse()).isEqualTo(1);
            assertThat(outboxEventRepository.findTop100ByPublishedFalseOrderByCreatedAtAsc())
                .hasSize(1);
        }

        @Test
        @DisplayName("relay result reports zero failures when all events succeed")
        void noFailuresWhenAllSucceed() {
            saveNormalEvent();

            OutboxRelayService.RelayResult result = relayService.relay();

            assertThat(result.failed()).isZero();
        }
    }

    // =========================================================================
    // Multiple events
    // =========================================================================

    @Nested
    @DisplayName("batch processing")
    class BatchProcessing {

        @Test
        @DisplayName("mixed batch: successful and failing events processed correctly")
        void mixedBatchProcessedCorrectly() {
            OutboxEvent e1 = saveNormalEvent();
            OutboxEvent e2 = saveNormalEvent();
            OutboxEvent e3 = saveFailEvent();
            OutboxEvent e4 = saveNormalEvent();

            OutboxRelayService.RelayResult result = relayService.relay();

            assertThat(result.total()).isEqualTo(4);
            assertThat(result.published()).isEqualTo(3);
            assertThat(result.failed()).isEqualTo(1);

            assertThat(outboxEventRepository.findById(e1.getId()).orElseThrow().isPublished()).isTrue();
            assertThat(outboxEventRepository.findById(e2.getId()).orElseThrow().isPublished()).isTrue();
            assertThat(outboxEventRepository.findById(e3.getId()).orElseThrow().isPublished()).isFalse();
            assertThat(outboxEventRepository.findById(e4.getId()).orElseThrow().isPublished()).isTrue();
        }

        @Test
        @DisplayName("relay with no unpublished events returns zero totals")
        void emptyRelayReturnsZeros() {
            OutboxRelayService.RelayResult result = relayService.relay();

            assertThat(result.total()).isZero();
            assertThat(result.published()).isZero();
            assertThat(result.failed()).isZero();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private OutboxEvent saveNormalEvent() {
        return outboxEventRepository.save(
            OutboxEvent.of(OutboxEventType.TRANSACTION_POSTED,
                UUID.randomUUID(),
                "TEST_AGGREGATE",
                "{\"test\":\"normal payload\"}"));
    }

    private OutboxEvent saveFailEvent() {
        return outboxEventRepository.save(
            OutboxEvent.of(OutboxEventType.TRANSACTION_POSTED,
                UUID.randomUUID(),
                "TEST_AGGREGATE",
                "{\"test\":\"FAIL_PUBLISH\"}"));
    }
}
