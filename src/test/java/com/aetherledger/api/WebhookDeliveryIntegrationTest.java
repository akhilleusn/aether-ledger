package com.aetherledger.api;

import com.aetherledger.domain.entity.Account;
import com.aetherledger.domain.entity.WebhookSubscription;
import com.aetherledger.domain.enums.AccountType;
import com.aetherledger.domain.enums.WebhookDeliveryStatus;
import com.aetherledger.repository.AccountRepository;
import com.aetherledger.repository.LedgerEntryRepository;
import com.aetherledger.repository.LedgerTransactionRepository;
import com.aetherledger.repository.OutboxEventRepository;
import com.aetherledger.repository.ReconciliationRunItemRepository;
import com.aetherledger.repository.ReconciliationRunRepository;
import com.aetherledger.repository.WebhookDeliveryRepository;
import com.aetherledger.repository.WebhookSubscriptionRepository;
import com.aetherledger.service.OutboxRelayService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for webhook delivery via the outbox relay pipeline.
 *
 * <p>Tests post ledger transactions (which create outbox events), run the relay
 * manually, then assert the expected {@link com.aetherledger.domain.entity.WebhookDelivery}
 * records were created with the correct status.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Webhook delivery integration")
class WebhookDeliveryIntegrationTest extends AbstractIntegrationTest {

    private static final String TX_ENDPOINT = "/api/v1/ledger-transactions";

    @Autowired MockMvc                        mockMvc;
    @Autowired ObjectMapper                   objectMapper;
    @Autowired OutboxRelayService             relayService;
    @Autowired WebhookSubscriptionRepository  subscriptionRepository;
    @Autowired WebhookDeliveryRepository      deliveryRepository;
    @Autowired OutboxEventRepository          outboxEventRepository;
    @Autowired LedgerEntryRepository          ledgerEntryRepository;
    @Autowired LedgerTransactionRepository    ledgerTransactionRepository;
    @Autowired AccountRepository              accountRepository;
    @Autowired ReconciliationRunItemRepository reconciliationRunItemRepository;
    @Autowired ReconciliationRunRepository    reconciliationRunRepository;

    private Account alice;
    private Account bob;

    @BeforeEach
    void setUp() {
        deliveryRepository.deleteAllInBatch();
        subscriptionRepository.deleteAllInBatch();
        reconciliationRunItemRepository.deleteAllInBatch();
        reconciliationRunRepository.deleteAllInBatch();
        outboxEventRepository.deleteAllInBatch();
        ledgerEntryRepository.deleteAllInBatch();
        ledgerTransactionRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();

        alice = accountRepository.save(Account.of("Alice Wallet", AccountType.USER));
        bob   = accountRepository.save(Account.of("Bob Wallet",   AccountType.USER));
    }

    @Test
    @DisplayName("published outbox event creates SUCCESS delivery for matching subscription")
    void relay_withMatchingSubscription_createsSuccessDelivery() {
        subscriptionRepository.save(
            WebhookSubscription.create("https://example.com/hook", "TRANSACTION_POSTED"));

        postTransaction("WH-TX-001");
        relayService.relay();

        var deliveries = deliveryRepository.findAll();
        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.get(0).getStatus()).isEqualTo(WebhookDeliveryStatus.SUCCESS);
        assertThat(deliveries.get(0).getEventType()).isEqualTo("TRANSACTION_POSTED");
        assertThat(deliveries.get(0).getDeliveredAt()).isNotNull();
    }

    @Test
    @DisplayName("no delivery is created for an inactive subscription")
    void relay_inactiveSubscription_createsNoDelivery() {
        WebhookSubscription sub =
            subscriptionRepository.save(
                WebhookSubscription.create("https://example.com/hook", "TRANSACTION_POSTED"));
        sub.deactivate();
        subscriptionRepository.save(sub);

        postTransaction("WH-TX-INACTIVE-001");
        relayService.relay();

        assertThat(deliveryRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("fail-trigger URL produces FAILED delivery with lastError recorded")
    void relay_failTriggerUrl_createsFailedDelivery() {
        subscriptionRepository.save(
            WebhookSubscription.create("https://fail.example.com/hook", "TRANSACTION_POSTED"));

        postTransaction("WH-TX-FAIL-001");
        relayService.relay();

        var deliveries = deliveryRepository.findAll();
        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.get(0).getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(deliveries.get(0).getLastError()).isNotBlank();
        assertThat(deliveries.get(0).getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("multiple active subscriptions each receive a separate delivery")
    void relay_multipleSubscriptions_eachReceivesDelivery() {
        subscriptionRepository.save(
            WebhookSubscription.create("https://a.example.com/hook", "TRANSACTION_POSTED"));
        subscriptionRepository.save(
            WebhookSubscription.create("https://b.example.com/hook", "TRANSACTION_POSTED"));

        postTransaction("WH-TX-MULTI-001");
        relayService.relay();

        assertThat(deliveryRepository.findAll()).hasSize(2);
        assertThat(deliveryRepository.findAll())
            .allMatch(d -> d.getStatus() == WebhookDeliveryStatus.SUCCESS);
    }

    @Test
    @DisplayName("webhook failure does not affect the outbox relay's published count")
    void relay_webhookFailure_doesNotMarkRelayFailed() {
        subscriptionRepository.save(
            WebhookSubscription.create("https://fail.example.com/hook", "TRANSACTION_POSTED"));

        postTransaction("WH-TX-RELAY-OK-001");
        var result = relayService.relay();

        // Relay itself reports success — the failed webhook is separate
        assertThat(result.failed()).isZero();
        assertThat(result.published()).isEqualTo(1);

        // The outbox event IS marked published despite the webhook failure
        assertThat(outboxEventRepository.findAll())
            .filteredOn(e -> e.getEventType().equals("TRANSACTION_POSTED"))
            .allMatch(e -> e.isPublished());
    }

    @Test
    @DisplayName("subscription for a different eventType does not receive delivery")
    void relay_subscriptionForDifferentEventType_receivesNoDelivery() {
        subscriptionRepository.save(
            WebhookSubscription.create("https://example.com/hook", "TRANSACTION_REVERSED"));

        postTransaction("WH-TX-TYPE-001");
        relayService.relay();

        // TRANSACTION_POSTED event fired but subscription is for TRANSACTION_REVERSED
        assertThat(deliveryRepository.findAll()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private void postTransaction(String referenceId) {
        var body = Map.of(
            "debitAccountId",  alice.getId().toString(),
            "creditAccountId", bob.getId().toString(),
            "amount",          "50.00",
            "referenceId",     referenceId
        );
        try {
            mockMvc.perform(post(TX_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
