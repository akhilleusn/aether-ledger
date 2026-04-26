package com.aetherledger.api;

import com.aetherledger.domain.entity.OutboxEvent;
import com.aetherledger.domain.enums.OutboxEventType;
import com.aetherledger.repository.AccountRepository;
import com.aetherledger.repository.HoldRepository;
import com.aetherledger.repository.LedgerEntryRepository;
import com.aetherledger.repository.LedgerTransactionRepository;
import com.aetherledger.repository.OutboxEventRepository;
import com.aetherledger.repository.ReconciliationRunItemRepository;
import com.aetherledger.repository.ReconciliationRunRepository;
import com.aetherledger.domain.entity.Account;
import com.aetherledger.domain.enums.AccountType;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests verifying that business operations write outbox events
 * atomically in the same transaction.
 *
 * <p>Tests also confirm that a failed operation does not leave a phantom
 * outbox event behind — if the business transaction rolls back, the outbox
 * row rolls back with it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Outbox event persistence")
class OutboxEventTest extends AbstractIntegrationTest {

    private static final String TX_ENDPOINT    = "/api/v1/ledger-transactions";
    private static final String RECON_ENDPOINT = "/api/v1/reconciliation";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired LedgerTransactionRepository ledgerTransactionRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired ReconciliationRunItemRepository reconciliationRunItemRepository;
    @Autowired ReconciliationRunRepository reconciliationRunRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired HoldRepository holdRepository;

    private Account alice;
    private Account bob;

    @BeforeEach
    void setUp() {
        // Outbox has no FKs, so it can be wiped first in any order.
        outboxEventRepository.deleteAllInBatch();
        reconciliationRunItemRepository.deleteAllInBatch();
        reconciliationRunRepository.deleteAllInBatch();
        ledgerEntryRepository.deleteAllInBatch();
        holdRepository.deleteAllInBatch();
        ledgerTransactionRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();

        alice = accountRepository.save(Account.of("Outbox – Alice", AccountType.USER));
        bob   = accountRepository.save(Account.of("Outbox – Bob",   AccountType.USER));
    }

    // =========================================================================
    // TRANSACTION_POSTED
    // =========================================================================

    @Nested
    @DisplayName("TRANSACTION_POSTED")
    class TransactionPosted {

        @Test
        @DisplayName("posting a transaction writes exactly one TRANSACTION_POSTED outbox event")
        void post_writesOutboxEvent() throws Exception {
            UUID txId = postSuccessTransaction("OUT-POST-001");

            List<OutboxEvent> events = outboxEventRepository.findAll();
            assertThat(events).hasSize(1);

            OutboxEvent event = events.get(0);
            assertThat(event.getEventType()).isEqualTo(OutboxEventType.TRANSACTION_POSTED.name());
            assertThat(event.getAggregateId()).isEqualTo(txId);
            assertThat(event.getAggregateType()).isEqualTo("LEDGER_TRANSACTION");
            assertThat(event.isPublished()).isFalse();
            assertThat(event.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("TRANSACTION_POSTED payload contains expected fields")
        void post_payloadContainsExpectedFields() throws Exception {
            postSuccessTransaction("OUT-POST-PAYLOAD-001");

            OutboxEvent event = outboxEventRepository.findAll().get(0);
            JsonNode payload = objectMapper.readTree(event.getPayload());

            assertThat(payload.has("transactionId")).isTrue();
            assertThat(payload.has("referenceId")).isTrue();
            assertThat(payload.get("referenceId").asText()).isEqualTo("OUT-POST-PAYLOAD-001");
            assertThat(payload.get("status").asText()).isEqualTo("SUCCESS");
            assertThat(payload.has("amount")).isTrue();
            assertThat(payload.has("debitAccountId")).isTrue();
            assertThat(payload.has("creditAccountId")).isTrue();
            assertThat(payload.has("createdAt")).isTrue();
        }

        @Test
        @DisplayName("unpublished outbox events have published = false")
        void post_newEvent_publishedFalse() throws Exception {
            postSuccessTransaction("OUT-UNPUBLISHED-001");

            long unpublishedCount = outboxEventRepository.countByPublishedFalse();
            assertThat(unpublishedCount).isEqualTo(1);

            List<OutboxEvent> unpublished =
                outboxEventRepository.findTop100ByPublishedFalseOrderByCreatedAtAsc();
            assertThat(unpublished).hasSize(1);
            assertThat(unpublished.get(0).isPublished()).isFalse();
        }
    }

    // =========================================================================
    // TRANSACTION_COMPLETED
    // =========================================================================

    @Nested
    @DisplayName("TRANSACTION_COMPLETED")
    class TransactionCompleted {

        @Test
        @DisplayName("completing a pending transaction writes exactly one TRANSACTION_COMPLETED event")
        void complete_writesOutboxEvent() throws Exception {
            UUID txId = postPendingTransaction("OUT-PEND-001");

            // No outbox event yet — pending transaction does not write one.
            assertThat(outboxEventRepository.findAll()).isEmpty();

            completePendingTransaction(txId);

            List<OutboxEvent> events = outboxEventRepository.findAll();
            assertThat(events).hasSize(1);

            OutboxEvent event = events.get(0);
            assertThat(event.getEventType()).isEqualTo(OutboxEventType.TRANSACTION_COMPLETED.name());
            assertThat(event.getAggregateId()).isEqualTo(txId);
            assertThat(event.isPublished()).isFalse();
        }

        @Test
        @DisplayName("TRANSACTION_COMPLETED payload contains amount and account IDs")
        void complete_payloadContainsIntentFields() throws Exception {
            UUID txId = postPendingTransaction("OUT-PEND-PAYLOAD-001");
            completePendingTransaction(txId);

            OutboxEvent event = outboxEventRepository.findAll().get(0);
            JsonNode payload = objectMapper.readTree(event.getPayload());

            assertThat(payload.get("status").asText()).isEqualTo("SUCCESS");
            assertThat(payload.has("amount")).isTrue();
            assertThat(payload.has("debitAccountId")).isTrue();
            assertThat(payload.has("creditAccountId")).isTrue();
        }
    }

    // =========================================================================
    // TRANSACTION_REVERSED
    // =========================================================================

    @Nested
    @DisplayName("TRANSACTION_REVERSED")
    class TransactionReversed {

        @Test
        @DisplayName("reversing a transaction writes a TRANSACTION_REVERSED outbox event")
        void reverse_writesOutboxEvent() throws Exception {
            UUID txId = postSuccessTransaction("OUT-REV-ORIG-001");
            outboxEventRepository.deleteAllInBatch(); // reset after the post event

            UUID reversalId = reverseTransaction(txId, "OUT-REV-001");

            List<OutboxEvent> events = outboxEventRepository.findAll();
            assertThat(events).hasSize(1);

            OutboxEvent event = events.get(0);
            assertThat(event.getEventType()).isEqualTo(OutboxEventType.TRANSACTION_REVERSED.name());
            assertThat(event.getAggregateId()).isEqualTo(reversalId);
            assertThat(event.isPublished()).isFalse();
        }

        @Test
        @DisplayName("TRANSACTION_REVERSED payload contains reversalOfTransactionId")
        void reverse_payloadContainsOriginalLink() throws Exception {
            UUID originalId = postSuccessTransaction("OUT-REV-ORIG-002");
            outboxEventRepository.deleteAllInBatch();

            reverseTransaction(originalId, "OUT-REV-002");

            OutboxEvent event = outboxEventRepository.findAll().get(0);
            JsonNode payload = objectMapper.readTree(event.getPayload());

            assertThat(payload.get("reversalOfTransactionId").asText())
                .isEqualTo(originalId.toString());
        }
    }

    // =========================================================================
    // TRANSACTION_RECONCILED
    // =========================================================================

    @Nested
    @DisplayName("TRANSACTION_RECONCILED")
    class TransactionReconciled {

        @Test
        @DisplayName("reconciling a transaction writes a TRANSACTION_RECONCILED outbox event")
        void reconcile_writesOutboxEvent() throws Exception {
            UUID txId = postSuccessTransaction("OUT-RECON-001");
            outboxEventRepository.deleteAllInBatch();

            reconcileTransaction(txId, "EXT-OUT-RECON-001", "SUCCESS");

            List<OutboxEvent> events = outboxEventRepository.findAll();
            assertThat(events).hasSize(1);

            OutboxEvent event = events.get(0);
            assertThat(event.getEventType()).isEqualTo(OutboxEventType.TRANSACTION_RECONCILED.name());
            assertThat(event.getAggregateId()).isEqualTo(txId);
            assertThat(event.isPublished()).isFalse();
        }

        @Test
        @DisplayName("TRANSACTION_RECONCILED payload contains reconciliation fields")
        void reconcile_payloadContainsReconciliationFields() throws Exception {
            UUID txId = postSuccessTransaction("OUT-RECON-PAYLOAD-001");
            outboxEventRepository.deleteAllInBatch();

            reconcileTransaction(txId, "EXT-OUT-RECON-PAYLOAD-001", "SUCCESS");

            OutboxEvent event = outboxEventRepository.findAll().get(0);
            JsonNode payload = objectMapper.readTree(event.getPayload());

            assertThat(payload.get("externalReferenceId").asText()).isEqualTo("EXT-OUT-RECON-PAYLOAD-001");
            assertThat(payload.get("externalStatus").asText()).isEqualTo("SUCCESS");
            assertThat(payload.get("reconciliationResult").asText()).isEqualTo("MATCHED");
            assertThat(payload.has("reconciledAt")).isTrue();
        }
    }

    // =========================================================================
    // BATCH_RECONCILIATION_COMPLETED
    // =========================================================================

    @Nested
    @DisplayName("BATCH_RECONCILIATION_COMPLETED")
    class BatchReconciliationCompleted {

        @Test
        @DisplayName("batch reconciliation writes a BATCH_RECONCILIATION_COMPLETED outbox event")
        void batchReconcile_writesOutboxEvent() throws Exception {
            postSuccessTransaction("OUT-BATCH-001");
            outboxEventRepository.deleteAllInBatch();

            runBatchReconciliation(List.of(
                Map.of("referenceId", "OUT-BATCH-001",
                       "externalReferenceId", "EXT-OUT-BATCH-001",
                       "externalStatus", "SUCCESS")
            ));

            // Batch reconcile writes one TRANSACTION_RECONCILED + one BATCH_RECONCILIATION_COMPLETED.
            List<OutboxEvent> events = outboxEventRepository.findAll();
            assertThat(events).hasSize(2);

            OutboxEvent batchEvent = events.stream()
                .filter(e -> OutboxEventType.BATCH_RECONCILIATION_COMPLETED.name().equals(e.getEventType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("BATCH_RECONCILIATION_COMPLETED event not found"));

            assertThat(batchEvent.getAggregateType()).isEqualTo("RECONCILIATION_RUN");
            assertThat(batchEvent.isPublished()).isFalse();

            JsonNode payload = objectMapper.readTree(batchEvent.getPayload());
            assertThat(payload.get("totalItems").asInt()).isEqualTo(1);
            assertThat(payload.get("matchedCount").asInt()).isEqualTo(1);
            assertThat(payload.has("runId")).isTrue();
        }
    }

    // =========================================================================
    // Atomicity — no event on failed business operation
    // =========================================================================

    @Nested
    @DisplayName("atomicity — no event on failure")
    class Atomicity {

        @Test
        @DisplayName("failed transaction (duplicate referenceId) does not persist an outbox event")
        void duplicateReferenceId_noOutboxEvent() throws Exception {
            postSuccessTransaction("OUT-DUPE-001");
            long eventsAfterFirstPost = outboxEventRepository.count();

            // Second post with same referenceId must fail with 409.
            mockMvc.perform(post(TX_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "debitAccountId",  alice.getId().toString(),
                        "creditAccountId", bob.getId().toString(),
                        "amount",          "50.00",
                        "referenceId",     "OUT-DUPE-001"
                    ))))
                .andExpect(status().isConflict());

            // Event count must not increase — the failed transaction rolled back.
            assertThat(outboxEventRepository.count()).isEqualTo(eventsAfterFirstPost);
        }

        @Test
        @DisplayName("invalid transaction request (negative amount) does not persist an outbox event")
        void invalidRequest_noOutboxEvent() throws Exception {
            mockMvc.perform(post(TX_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "debitAccountId",  alice.getId().toString(),
                        "creditAccountId", bob.getId().toString(),
                        "amount",          "-10.00",
                        "referenceId",     "OUT-INVALID-001"
                    ))))
                .andExpect(status().isBadRequest());

            assertThat(outboxEventRepository.count()).isZero();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private UUID postSuccessTransaction(String referenceId) throws Exception {
        MvcResult result = mockMvc.perform(post(TX_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "debitAccountId",  alice.getId().toString(),
                    "creditAccountId", bob.getId().toString(),
                    "amount",          "100.00",
                    "referenceId",     referenceId
                ))))
            .andExpect(status().isCreated())
            .andReturn();
        return UUID.fromString(
            objectMapper.readTree(result.getResponse().getContentAsString()).get("transactionId").asText());
    }

    private UUID postPendingTransaction(String referenceId) throws Exception {
        MvcResult result = mockMvc.perform(post(TX_ENDPOINT + "/pending")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "debitAccountId",  alice.getId().toString(),
                    "creditAccountId", bob.getId().toString(),
                    "amount",          "75.00",
                    "referenceId",     referenceId
                ))))
            .andExpect(status().isCreated())
            .andReturn();
        return UUID.fromString(
            objectMapper.readTree(result.getResponse().getContentAsString()).get("transactionId").asText());
    }

    private void completePendingTransaction(UUID txId) throws Exception {
        mockMvc.perform(post(TX_ENDPOINT + "/" + txId + "/complete"))
            .andExpect(status().isOk());
    }

    private UUID reverseTransaction(UUID originalId, String reversalReferenceId) throws Exception {
        MvcResult result = mockMvc.perform(post(TX_ENDPOINT + "/" + originalId + "/reversal")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("referenceId", reversalReferenceId))))
            .andExpect(status().isCreated())
            .andReturn();
        return UUID.fromString(
            objectMapper.readTree(result.getResponse().getContentAsString()).get("transactionId").asText());
    }

    private void reconcileTransaction(UUID txId, String extRefId, String extStatus) throws Exception {
        mockMvc.perform(post(TX_ENDPOINT + "/" + txId + "/reconcile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "externalReferenceId", extRefId,
                    "externalStatus",      extStatus
                ))))
            .andExpect(status().isOk());
    }

    private void runBatchReconciliation(List<Map<String, Object>> items) throws Exception {
        mockMvc.perform(post(RECON_ENDPOINT + "/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("items", items))))
            .andExpect(status().isOk());
    }
}
