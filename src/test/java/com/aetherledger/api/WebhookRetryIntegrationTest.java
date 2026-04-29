package com.aetherledger.api;

import com.aetherledger.domain.entity.Account;
import com.aetherledger.domain.entity.OutboxEvent;
import com.aetherledger.domain.entity.WebhookDelivery;
import com.aetherledger.domain.entity.WebhookSubscription;
import com.aetherledger.domain.enums.AccountType;
import com.aetherledger.domain.enums.OutboxEventType;
import com.aetherledger.domain.enums.WebhookDeliveryStatus;
import com.aetherledger.repository.AccountRepository;
import com.aetherledger.repository.HoldRepository;
import com.aetherledger.repository.LedgerEntryRepository;
import com.aetherledger.repository.LedgerTransactionRepository;
import com.aetherledger.repository.OutboxEventRepository;
import com.aetherledger.repository.WebhookDeliveryRepository;
import com.aetherledger.repository.WebhookSubscriptionRepository;
import com.aetherledger.service.OutboxRelayService;
import com.aetherledger.service.WebhookRetryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for webhook retry and dead-letter behaviour.
 *
 * <p>The scheduled retry job is disabled in the test profile; tests drive
 * {@link WebhookRetryService} and {@link OutboxRelayService} directly so
 * timing is deterministic.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Webhook retry and dead-letter")
class WebhookRetryIntegrationTest extends AbstractIntegrationTest {

    private static final String TX_ENDPOINT       = "/api/v1/ledger-transactions";
    private static final String DELIVERIES_FAILED = "/api/v1/webhook-deliveries/failed";
    private static final String DELIVERIES_RETRY  = "/api/v1/webhook-deliveries/{id}/retry";

    // URLs that control LoggingWebhookClient behaviour
    private static final String GOOD_URL = "https://example.com/hook";
    private static final String FAIL_URL = "https://fail.example.com/hook";

    @Autowired MockMvc                        mockMvc;
    @Autowired ObjectMapper                   objectMapper;
    @Autowired OutboxRelayService             relayService;
    @Autowired WebhookRetryService            retryService;
    @Autowired AccountRepository              accountRepository;
    @Autowired HoldRepository                 holdRepository;
    @Autowired LedgerEntryRepository          ledgerEntryRepository;
    @Autowired LedgerTransactionRepository    ledgerTransactionRepository;
    @Autowired OutboxEventRepository          outboxEventRepository;
    @Autowired WebhookSubscriptionRepository  subscriptionRepository;
    @Autowired WebhookDeliveryRepository      deliveryRepository;

    private Account alice;
    private Account bob;

    @BeforeEach
    void setUp() {
        resetDatabase();

        alice = accountRepository.save(Account.of("Retry – Alice", AccountType.USER));
        bob   = accountRepository.save(Account.of("Retry – Bob",   AccountType.USER));
    }

    // =========================================================================
    // First attempt → FAILED with nextAttemptAt
    // =========================================================================

    @Nested
    @DisplayName("Initial failure sets nextAttemptAt")
    class InitialFailure {

        @Test
        @DisplayName("failed delivery has nextAttemptAt set and attemptCount=1")
        void failedDelivery_hasNextAttemptAt() {
            subscriptionRepository.save(
                WebhookSubscription.create(FAIL_URL, "TRANSACTION_POSTED"));

            postTransaction("RETRY-FIRST-001");
            relayService.relay();

            var deliveries = deliveryRepository.findAll();
            assertThat(deliveries).hasSize(1);
            var d = deliveries.get(0);
            assertThat(d.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
            assertThat(d.getAttemptCount()).isEqualTo(1);
            assertThat(d.getNextAttemptAt()).isNotNull();
            assertThat(d.getNextAttemptAt()).isAfter(Instant.now());
        }

        @Test
        @DisplayName("webhook failure does not affect the outbox relay result")
        void webhookFailure_doesNotBreakRelay() {
            subscriptionRepository.save(
                WebhookSubscription.create(FAIL_URL, "TRANSACTION_POSTED"));

            postTransaction("RETRY-RELAY-001");
            var result = relayService.relay();

            assertThat(result.failed()).isZero();
            assertThat(result.published()).isEqualTo(1);
        }
    }

    // =========================================================================
    // Scheduled retry — retryDue()
    // =========================================================================

    @Nested
    @DisplayName("Scheduled retry — retryDue()")
    class ScheduledRetry {

        @Test
        @DisplayName("FAILED delivery with past nextAttemptAt is retried and succeeds")
        void failedDelivery_dueForRetry_succeedsOnGoodUrl() {
            // Build FAILED delivery directly with a good URL (relay would succeed on it)
            WebhookDelivery delivery = deliveryAtAttempt(GOOD_URL, "RETRY-DUE-OK", 1);
            deliveryRepository.setNextAttemptAt(delivery.getId(), Instant.now().minusSeconds(10));

            var result = retryService.retryDue();

            assertThat(result.attempted()).isEqualTo(1);
            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.failed()).isZero();

            var updated = deliveryRepository.findById(delivery.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(WebhookDeliveryStatus.SUCCESS);
            assertThat(updated.getDeliveredAt()).isNotNull();
            assertThat(updated.getNextAttemptAt()).isNull();
        }

        @Test
        @DisplayName("FAILED delivery with past nextAttemptAt fails again and updates backoff")
        void failedDelivery_dueForRetry_failsAgain() {
            WebhookDelivery delivery = failedDelivery(FAIL_URL, "RETRY-DUE-FAIL");
            deliveryRepository.setNextAttemptAt(delivery.getId(), Instant.now().minusSeconds(10));

            retryService.retryDue();

            var updated = deliveryRepository.findById(delivery.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
            assertThat(updated.getAttemptCount()).isEqualTo(2);
            assertThat(updated.getNextAttemptAt()).isAfter(Instant.now());
        }

        @Test
        @DisplayName("delivery not yet due is not processed")
        void failedDelivery_notYetDue_isIgnored() {
            WebhookDelivery delivery = failedDelivery(FAIL_URL, "RETRY-NOT-DUE");
            // nextAttemptAt is already set to the future by markFailed() — leave it

            var result = retryService.retryDue();
            assertThat(result.attempted()).isZero();

            var unchanged = deliveryRepository.findById(delivery.getId()).orElseThrow();
            assertThat(unchanged.getAttemptCount()).isEqualTo(1);
        }
    }

    // =========================================================================
    // Dead-letter — exhausting max attempts
    // =========================================================================

    @Nested
    @DisplayName("Dead-letter — exhausting maxAttempts")
    class DeadLetter {

        @Test
        @DisplayName("delivery that fails on attempt 5 transitions to DEAD")
        void repeatedFailures_transitionToDead() {
            // Build a delivery at attemptCount=4 (one retry away from DEAD)
            WebhookDelivery delivery = deliveryAtAttempt(FAIL_URL, "RETRY-DEAD-001", 4);
            deliveryRepository.setNextAttemptAt(delivery.getId(), Instant.now().minusSeconds(10));

            retryService.retryDue();

            var updated = deliveryRepository.findById(delivery.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(WebhookDeliveryStatus.DEAD);
            assertThat(updated.getAttemptCount()).isEqualTo(5);
            assertThat(updated.getNextAttemptAt()).isNull();
        }

        @Test
        @DisplayName("DEAD delivery appears in the failed deliveries endpoint")
        void deadDelivery_appearsInFailedEndpoint() throws Exception {
            // Build at 4 attempts (FAILED), retry once more → becomes DEAD at attempt 5
            WebhookDelivery delivery = deliveryAtAttempt(FAIL_URL, "RETRY-DLQ-001", 4);
            deliveryRepository.setNextAttemptAt(delivery.getId(), Instant.now().minusSeconds(10));
            retryService.retryDue();

            mockMvc.perform(get(DELIVERIES_FAILED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("DEAD"))
                .andExpect(jsonPath("$[0].attemptCount").value(5))
                .andExpect(jsonPath("$[0].nextAttemptAt").doesNotExist());
        }

        @Test
        @DisplayName("FAILED delivery also appears in failed deliveries endpoint")
        void failedDelivery_appearsInFailedEndpoint() {
            failedDelivery(FAIL_URL, "RETRY-FAILED-LIST");

            var list = deliveryRepository.findByStatusInOrderByCreatedAtDesc(
                java.util.List.of(WebhookDeliveryStatus.FAILED, WebhookDeliveryStatus.DEAD));

            assertThat(list).hasSize(1);
            assertThat(list.get(0).getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        }
    }

    // =========================================================================
    // Manual retry — POST /api/v1/webhook-deliveries/{id}/retry
    // =========================================================================

    @Nested
    @DisplayName("Manual retry endpoint")
    class ManualRetry {

        @Test
        @DisplayName("manual retry of FAILED delivery with good URL returns SUCCESS")
        void manualRetry_failedDelivery_succeeds() throws Exception {
            // Build FAILED delivery directly with a good URL (relay would have succeeded)
            WebhookDelivery delivery = deliveryAtAttempt(GOOD_URL, "MANUAL-OK", 1);

            mockMvc.perform(post(DELIVERIES_RETRY, delivery.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.deliveredAt").isNotEmpty())
                .andExpect(jsonPath("$.attemptCount").value(2));
        }

        @Test
        @DisplayName("manual retry of DEAD delivery with good URL returns SUCCESS")
        void manualRetry_deadDelivery_succeeds() throws Exception {
            WebhookDelivery delivery = deliveryAtAttempt(GOOD_URL, "MANUAL-DEAD-OK", 5);

            mockMvc.perform(post(DELIVERIES_RETRY, delivery.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        }

        @Test
        @DisplayName("manual retry of SUCCESS delivery returns 422")
        void manualRetry_successDelivery_returns422() throws Exception {
            WebhookDelivery delivery = successDelivery(GOOD_URL, "MANUAL-SUCCESS");

            mockMvc.perform(post(DELIVERIES_RETRY, delivery.getId()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("WEBHOOK_DELIVERY_NOT_RETRYABLE"));
        }

        @Test
        @DisplayName("manual retry of unknown delivery returns 404")
        void manualRetry_unknownDelivery_returns404() throws Exception {
            mockMvc.perform(post(DELIVERIES_RETRY, UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("WEBHOOK_DELIVERY_NOT_FOUND"));
        }

        @Test
        @DisplayName("invalid UUID path variable returns 400")
        void manualRetry_invalidUuid_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/webhook-deliveries/not-a-uuid/retry"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PATH_VARIABLE"));
        }
    }

    // =========================================================================
    // GET /api/v1/webhook-deliveries/failed
    // =========================================================================

    @Nested
    @DisplayName("GET /api/v1/webhook-deliveries/failed")
    class FailedEndpoint {

        @Test
        @DisplayName("returns empty list when no failed deliveries exist")
        void noFailedDeliveries_returnsEmpty() throws Exception {
            mockMvc.perform(get(DELIVERIES_FAILED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        @DisplayName("returned records carry all expected fields")
        void failedDelivery_responseHasExpectedFields() throws Exception {
            failedDelivery(FAIL_URL, "FIELDS-001");

            mockMvc.perform(get(DELIVERIES_FAILED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").isNotEmpty())
                .andExpect(jsonPath("$[0].subscriptionId").isNotEmpty())
                .andExpect(jsonPath("$[0].outboxEventId").isNotEmpty())
                .andExpect(jsonPath("$[0].targetUrl").value(FAIL_URL))
                .andExpect(jsonPath("$[0].eventType").value("TRANSACTION_POSTED"))
                .andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[0].attemptCount").value(1))
                .andExpect(jsonPath("$[0].maxAttempts").value(5))
                .andExpect(jsonPath("$[0].lastError").isNotEmpty())
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$[0].nextAttemptAt").isNotEmpty());
        }

        @Test
        @DisplayName("SUCCESS deliveries do not appear in the list")
        void successDelivery_doesNotAppear() throws Exception {
            successDelivery(GOOD_URL, "SUCCESS-HIDDEN");

            mockMvc.perform(get(DELIVERIES_FAILED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        }
    }

    // =========================================================================
    // Test helpers
    // =========================================================================

    /**
     * Creates a FAILED delivery via the relay pipeline (post transaction →
     * relay with a fail URL subscription).
     */
    private WebhookDelivery failedDelivery(String targetUrl, String referenceId) {
        WebhookSubscription sub = subscriptionRepository.save(
            WebhookSubscription.create(targetUrl, "TRANSACTION_POSTED"));
        postTransaction(referenceId);
        relayService.relay();
        var deliveries = deliveryRepository.findBySubscriptionId(sub.getId());
        assertThat(deliveries).hasSize(1);
        return deliveries.get(0);
    }

    /**
     * Creates a SUCCESS delivery via the relay pipeline.
     */
    private WebhookDelivery successDelivery(String targetUrl, String referenceId) {
        WebhookSubscription sub = subscriptionRepository.save(
            WebhookSubscription.create(targetUrl, "TRANSACTION_POSTED"));
        postTransaction(referenceId);
        relayService.relay();
        var deliveries = deliveryRepository.findBySubscriptionId(sub.getId());
        assertThat(deliveries).hasSize(1);
        return deliveries.get(0);
    }

    /**
     * Builds a delivery already at {@code attemptCount} failed attempts by
     * calling {@code markFailed()} repeatedly and saving to the repository.
     * Useful for setting up near-dead preconditions without many relay passes.
     */
    private WebhookDelivery deliveryAtAttempt(String targetUrl, String referenceId, int failCount) {
        OutboxEvent event = outboxEventRepository.save(
            OutboxEvent.of(OutboxEventType.TRANSACTION_POSTED, UUID.randomUUID(),
                "LEDGER_TRANSACTION", "{\"referenceId\":\"" + referenceId + "\"}"));

        WebhookSubscription sub = subscriptionRepository.save(
            WebhookSubscription.create(targetUrl, "TRANSACTION_POSTED"));

        WebhookDelivery delivery = WebhookDelivery.create(sub, event);
        for (int i = 0; i < failCount; i++) {
            delivery.markFailed("simulated error #" + (i + 1));
        }
        return deliveryRepository.save(delivery);
    }

    private void postTransaction(String referenceId) {
        try {
            mockMvc.perform(post(TX_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "debitAccountId",  alice.getId().toString(),
                        "creditAccountId", bob.getId().toString(),
                        "amount",          "10.00",
                        "referenceId",     referenceId
                    ))))
                .andExpect(status().isCreated());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
