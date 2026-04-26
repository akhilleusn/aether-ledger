package com.aetherledger.api;

import com.aetherledger.domain.entity.Account;
import com.aetherledger.domain.entity.OutboxEvent;
import com.aetherledger.domain.entity.WebhookSubscription;
import com.aetherledger.domain.enums.AccountType;
import com.aetherledger.domain.enums.OutboxEventType;
import com.aetherledger.repository.AccountRepository;
import com.aetherledger.repository.HoldRepository;
import com.aetherledger.repository.LedgerEntryRepository;
import com.aetherledger.repository.LedgerTransactionRepository;
import com.aetherledger.repository.OutboxEventRepository;
import com.aetherledger.repository.ReconciliationRunItemRepository;
import com.aetherledger.repository.ReconciliationRunRepository;
import com.aetherledger.repository.WebhookDeliveryRepository;
import com.aetherledger.repository.WebhookSubscriptionRepository;
import com.aetherledger.service.OutboxRelayService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests verifying that business operations increment the expected
 * Micrometer counters.
 *
 * <p>Because the Spring context is shared across all test classes, counters
 * accumulate across runs.  Each test therefore reads the counter value <em>before</em>
 * and <em>after</em> the operation and asserts the delta — making tests
 * independent of execution order.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Metrics")
class MetricsIntegrationTest extends AbstractIntegrationTest {

    private static final String TX_ENDPOINT = "/api/v1/ledger-transactions";

    @Autowired MeterRegistry                  registry;
    @Autowired MockMvc                        mockMvc;
    @Autowired ObjectMapper                   objectMapper;
    @Autowired OutboxRelayService             relayService;
    @Autowired AccountRepository              accountRepository;
    @Autowired HoldRepository                 holdRepository;
    @Autowired LedgerEntryRepository          ledgerEntryRepository;
    @Autowired LedgerTransactionRepository    ledgerTransactionRepository;
    @Autowired OutboxEventRepository          outboxEventRepository;
    @Autowired ReconciliationRunItemRepository reconciliationRunItemRepository;
    @Autowired ReconciliationRunRepository    reconciliationRunRepository;
    @Autowired WebhookDeliveryRepository      webhookDeliveryRepository;
    @Autowired WebhookSubscriptionRepository  webhookSubscriptionRepository;

    private Account alice;
    private Account bob;

    @BeforeEach
    void setUp() {
        webhookDeliveryRepository.deleteAllInBatch();
        webhookSubscriptionRepository.deleteAllInBatch();
        reconciliationRunItemRepository.deleteAllInBatch();
        reconciliationRunRepository.deleteAllInBatch();
        outboxEventRepository.deleteAllInBatch();
        ledgerEntryRepository.deleteAllInBatch();
        holdRepository.deleteAllInBatch();
        ledgerTransactionRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();

        alice = accountRepository.save(Account.of("Alice Wallet", AccountType.USER));
        bob   = accountRepository.save(Account.of("Bob Wallet",   AccountType.USER));
    }

    // =========================================================================
    // Transaction counters
    // =========================================================================

    @Test
    @DisplayName("posting a transaction increments aetherledger.transactions.posted")
    void postTransaction_incrementsPostedCounter() {
        double before = count("aetherledger.transactions.posted");

        postTransaction("METRIC-POST-001");

        assertThat(count("aetherledger.transactions.posted")).isEqualTo(before + 1.0);
    }

    @Test
    @DisplayName("completing a pending transaction increments aetherledger.transactions.completed")
    void completePending_incrementsCompletedCounter() throws Exception {
        double before = count("aetherledger.transactions.completed");

        String txId = createPendingTransaction("METRIC-COMP-001");
        mockMvc.perform(post(TX_ENDPOINT + "/" + txId + "/complete"))
            .andExpect(status().isOk());

        assertThat(count("aetherledger.transactions.completed")).isEqualTo(before + 1.0);
    }

    @Test
    @DisplayName("failing a pending transaction increments aetherledger.transactions.failed")
    void failPending_incrementsFailedCounter() throws Exception {
        double before = count("aetherledger.transactions.failed");

        String txId = createPendingTransaction("METRIC-FAIL-001");
        mockMvc.perform(post(TX_ENDPOINT + "/" + txId + "/fail"))
            .andExpect(status().isOk());

        assertThat(count("aetherledger.transactions.failed")).isEqualTo(before + 1.0);
    }

    @Test
    @DisplayName("reversing a transaction increments aetherledger.transactions.reversed")
    void reverseTransaction_incrementsReversedCounter() throws Exception {
        double before = count("aetherledger.transactions.reversed");

        String txId = postTransaction("METRIC-REV-001");
        var body = Map.of("referenceId", "METRIC-REV-REV-001");
        mockMvc.perform(post(TX_ENDPOINT + "/" + txId + "/reversal")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated());

        assertThat(count("aetherledger.transactions.reversed")).isEqualTo(before + 1.0);
    }

    // =========================================================================
    // Reconciliation counters
    // =========================================================================

    @Test
    @DisplayName("matched reconciliation increments aetherledger.reconciliation.matched")
    void reconcileMatched_incrementsMatchedCounter() throws Exception {
        double before = count("aetherledger.reconciliation.matched");

        String txId = postTransaction("METRIC-RECON-MATCH-001");
        reconcile(txId, "EXT-MATCH-001", "SUCCESS");

        assertThat(count("aetherledger.reconciliation.matched")).isEqualTo(before + 1.0);
    }

    @Test
    @DisplayName("mismatched reconciliation increments aetherledger.reconciliation.mismatched")
    void reconcileMismatched_incrementsMismatchedCounter() throws Exception {
        double before = count("aetherledger.reconciliation.mismatched");

        String txId = postTransaction("METRIC-RECON-MISMATCH-001");
        reconcile(txId, "EXT-MISMATCH-001", "FAILED");

        assertThat(count("aetherledger.reconciliation.mismatched")).isEqualTo(before + 1.0);
    }

    // =========================================================================
    // Outbox relay counters
    // =========================================================================

    @Test
    @DisplayName("successful outbox relay increments aetherledger.outbox.published")
    void relay_publishSuccess_incrementsPublishedCounter() {
        postTransaction("METRIC-OUTBOX-PUB-001");
        double before = countTagged("aetherledger.outbox.published", "eventType", "TRANSACTION_POSTED");

        relayService.relay();

        assertThat(countTagged("aetherledger.outbox.published", "eventType", "TRANSACTION_POSTED"))
            .isGreaterThan(before);
    }

    @Test
    @DisplayName("outbox publish failure increments aetherledger.outbox.publish.failures")
    void relay_publishFailure_incrementsFailureCounter() {
        // Insert an event whose payload contains the FAIL_TRIGGER string used by LoggingOutboxEventPublisher
        outboxEventRepository.save(OutboxEvent.of(
            OutboxEventType.TRANSACTION_POSTED,
            java.util.UUID.randomUUID(),
            "LEDGER_TRANSACTION",
            "{\"FAIL_PUBLISH\":true}"
        ));

        double before = count("aetherledger.outbox.publish.failures");

        relayService.relay();

        assertThat(count("aetherledger.outbox.publish.failures")).isEqualTo(before + 1.0);
    }

    // =========================================================================
    // Webhook delivery counters
    // =========================================================================

    @Test
    @DisplayName("failed webhook delivery increments aetherledger.webhooks.failed")
    void relay_webhookFailure_incrementsWebhookFailedCounter() {
        webhookSubscriptionRepository.save(
            WebhookSubscription.create("https://fail.example.com/hook",
                OutboxEventType.TRANSACTION_POSTED.name()));

        postTransaction("METRIC-WH-FAIL-001");

        double before = countTagged("aetherledger.webhooks.failed", "eventType", "TRANSACTION_POSTED");

        relayService.relay();

        assertThat(countTagged("aetherledger.webhooks.failed", "eventType", "TRANSACTION_POSTED"))
            .isEqualTo(before + 1.0);
    }

    @Test
    @DisplayName("successful webhook delivery increments aetherledger.webhooks.delivered")
    void relay_webhookSuccess_incrementsWebhookDeliveredCounter() {
        webhookSubscriptionRepository.save(
            WebhookSubscription.create("https://ok.example.com/hook",
                OutboxEventType.TRANSACTION_POSTED.name()));

        postTransaction("METRIC-WH-OK-001");

        double before = countTagged("aetherledger.webhooks.delivered", "eventType", "TRANSACTION_POSTED");

        relayService.relay();

        assertThat(countTagged("aetherledger.webhooks.delivered", "eventType", "TRANSACTION_POSTED"))
            .isEqualTo(before + 1.0);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String postTransaction(String referenceId) {
        var body = Map.of(
            "debitAccountId",  alice.getId().toString(),
            "creditAccountId", bob.getId().toString(),
            "amount",          "50.00",
            "referenceId",     referenceId
        );
        try {
            String json = mockMvc.perform(post(TX_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
            @SuppressWarnings("unchecked")
            String id = (String) objectMapper.readValue(json, Map.class).get("transactionId");
            return id;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String createPendingTransaction(String referenceId) throws Exception {
        var body = Map.of(
            "debitAccountId",  alice.getId().toString(),
            "creditAccountId", bob.getId().toString(),
            "amount",          "50.00",
            "referenceId",     referenceId
        );
        String json = mockMvc.perform(post(TX_ENDPOINT + "/pending")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        String id = (String) objectMapper.readValue(json, Map.class).get("transactionId");
        return id;
    }

    private void reconcile(String txId, String externalRef, String externalStatus) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("externalReferenceId", externalRef);
        body.put("externalStatus", externalStatus);
        mockMvc.perform(post(TX_ENDPOINT + "/" + txId + "/reconcile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk());
    }

    /** Returns the current count of a simple (tag-free) counter, or 0 if not yet registered. */
    private double count(String name) {
        try {
            return registry.get(name).counter().count();
        } catch (Exception e) {
            return 0.0;
        }
    }

    /** Returns the current count of a tagged counter, or 0 if not yet registered. */
    private double countTagged(String name, String tagKey, String tagValue) {
        try {
            return registry.get(name).tag(tagKey, tagValue).counter().count();
        } catch (Exception e) {
            return 0.0;
        }
    }
}
