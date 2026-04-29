package com.aetherledger.api;

import com.aetherledger.domain.entity.Account;
import com.aetherledger.domain.enums.AccountType;
import com.aetherledger.repository.AccountRepository;
import com.aetherledger.repository.HoldRepository;
import com.aetherledger.repository.LedgerEntryRepository;
import com.aetherledger.repository.LedgerTransactionRepository;
import com.aetherledger.repository.ReconciliationRunItemRepository;
import com.aetherledger.repository.ReconciliationRunRepository;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@code GET /api/v1/ledger-transactions/{id}/audit-timeline}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("AuditTimeline endpoint")
class AuditTimelineControllerTest extends AbstractIntegrationTest {

    private static final String TX_BASE      = "/api/v1/ledger-transactions";
    private static final String TIMELINE_SUF = "/audit-timeline";

    @Autowired MockMvc                        mockMvc;
    @Autowired ObjectMapper                   objectMapper;
    @Autowired AccountRepository              accountRepository;
    @Autowired HoldRepository                 holdRepository;
    @Autowired LedgerTransactionRepository    ledgerTransactionRepository;
    @Autowired LedgerEntryRepository          ledgerEntryRepository;
    @Autowired ReconciliationRunItemRepository reconciliationRunItemRepository;
    @Autowired ReconciliationRunRepository    reconciliationRunRepository;

    private Account alice;
    private Account bob;

    @BeforeEach
    void setUp() {
        resetDatabase();

        alice = accountRepository.save(Account.of("Alice Wallet", AccountType.USER));
        bob   = accountRepository.save(Account.of("Bob Wallet",   AccountType.USER));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String postTransaction(String referenceId) throws Exception {
        var body = Map.of(
            "debitAccountId",  alice.getId().toString(),
            "creditAccountId", bob.getId().toString(),
            "amount",          "100.00",
            "referenceId",     referenceId
        );
        String json = mockMvc.perform(post(TX_BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
        return (String) parsed.get("transactionId");
    }

    private String postPendingTransaction(String referenceId) throws Exception {
        var body = Map.of(
            "debitAccountId",  alice.getId().toString(),
            "creditAccountId", bob.getId().toString(),
            "amount",          "75.00",
            "referenceId",     referenceId
        );
        String json = mockMvc.perform(post(TX_BASE + "/pending")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
        return (String) parsed.get("transactionId");
    }

    private void reconcile(String txId, String externalRef, String externalStatus) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        if (externalRef != null) body.put("externalReferenceId", externalRef);
        body.put("externalStatus", externalStatus);
        mockMvc.perform(post(TX_BASE + "/" + txId + "/reconcile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getTimeline(String txId) throws Exception {
        String json = mockMvc.perform(get(TX_BASE + "/" + txId + TIMELINE_SUF))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(json, List.class);
    }

    private String eventType(Map<String, Object> item) {
        return (String) item.get("eventType");
    }

    // -------------------------------------------------------------------------
    // 404 and 400
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("error cases")
    class ErrorCases {

        @Test
        @DisplayName("unknown UUID returns 404")
        void timeline_unknownId_returns404() throws Exception {
            mockMvc.perform(get(TX_BASE + "/" + UUID.randomUUID() + TIMELINE_SUF))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TRANSACTION_NOT_FOUND"));
        }

        @Test
        @DisplayName("invalid UUID returns 400")
        void timeline_invalidUuid_returns400() throws Exception {
            mockMvc.perform(get(TX_BASE + "/not-a-uuid" + TIMELINE_SUF))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PATH_VARIABLE"));
        }
    }

    // -------------------------------------------------------------------------
    // Immediate SUCCESS transaction
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("immediate SUCCESS transaction")
    class ImmediateSuccess {

        @Test
        @DisplayName("contains TRANSACTION_CREATED and LEDGER_ENTRIES_WRITTEN events")
        void timeline_immediateSuccess_containsCreatedAndEntries() throws Exception {
            String txId = postTransaction("AUDIT-IMM-001");

            mockMvc.perform(get(TX_BASE + "/" + txId + TIMELINE_SUF))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("TRANSACTION_CREATED"))
                .andExpect(jsonPath("$[0].source").value("ledger"))
                .andExpect(jsonPath("$[0].metadata.referenceId").value("AUDIT-IMM-001"))
                .andExpect(jsonPath("$[1].eventType").value("LEDGER_ENTRIES_WRITTEN"))
                .andExpect(jsonPath("$[1].source").value("ledger"))
                .andExpect(jsonPath("$[1].metadata.amount").value("100.0000"));
        }

        @Test
        @DisplayName("events are sorted by occurredAt ascending")
        void timeline_immediateSuccess_sortedAscending() throws Exception {
            String txId = postTransaction("AUDIT-SORT-001");

            List<Map<String, Object>> items = getTimeline(txId);

            String prev = null;
            for (Map<String, Object> item : items) {
                String occurredAt = (String) item.get("occurredAt");
                if (prev != null) {
                    // String comparison of ISO-8601 is lexicographically monotone
                    assert occurredAt.compareTo(prev) >= 0 : "Timeline is not sorted ascending";
                }
                prev = occurredAt;
            }
        }

        @Test
        @DisplayName("TRANSACTION_CREATED metadata includes referenceId and status")
        void timeline_immediateSuccess_createdMetadataIsComplete() throws Exception {
            String txId = postTransaction("AUDIT-META-001");

            mockMvc.perform(get(TX_BASE + "/" + txId + TIMELINE_SUF))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].metadata.referenceId").value("AUDIT-META-001"))
                .andExpect(jsonPath("$[0].metadata.status").value("SUCCESS"));
        }
    }

    // -------------------------------------------------------------------------
    // PENDING transaction
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("PENDING transaction")
    class PendingTransaction {

        @Test
        @DisplayName("uncompeted pending transaction has TRANSACTION_CREATED but no LEDGER_ENTRIES_WRITTEN")
        void timeline_pending_noEntriesEvent() throws Exception {
            String txId = postPendingTransaction("AUDIT-PEND-001");

            List<Map<String, Object>> items = getTimeline(txId);

            long createdCount = items.stream()
                .filter(i -> "TRANSACTION_CREATED".equals(eventType(i))).count();
            long entriesCount = items.stream()
                .filter(i -> "LEDGER_ENTRIES_WRITTEN".equals(eventType(i))).count();

            assert createdCount == 1 : "Expected exactly one TRANSACTION_CREATED event";
            assert entriesCount == 0 : "Expected no LEDGER_ENTRIES_WRITTEN for uncompleted PENDING";
        }

        @Test
        @DisplayName("completed pending transaction gains LEDGER_ENTRIES_WRITTEN after completion")
        void timeline_pendingCompleted_hasEntriesEvent() throws Exception {
            String txId = postPendingTransaction("AUDIT-PEND-COMP-001");
            mockMvc.perform(post(TX_BASE + "/" + txId + "/complete"))
                .andExpect(status().isOk());

            List<Map<String, Object>> items = getTimeline(txId);
            long entriesCount = items.stream()
                .filter(i -> "LEDGER_ENTRIES_WRITTEN".equals(eventType(i))).count();

            assert entriesCount == 1 : "Expected LEDGER_ENTRIES_WRITTEN after completion";
        }
    }

    // -------------------------------------------------------------------------
    // Reconciled transaction
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("reconciled transaction")
    class ReconciledTransaction {

        @Test
        @DisplayName("reconciled transaction timeline includes RECONCILIATION_RECORDED event")
        void timeline_reconciled_containsReconciliationEvent() throws Exception {
            String txId = postTransaction("AUDIT-RECON-001");
            reconcile(txId, "EXT-AUDIT-001", "SUCCESS");

            mockMvc.perform(get(TX_BASE + "/" + txId + TIMELINE_SUF))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.eventType == 'RECONCILIATION_RECORDED')]").isArray())
                .andExpect(jsonPath("$[?(@.eventType == 'RECONCILIATION_RECORDED')].source",
                    hasItem("reconciliation")))
                .andExpect(jsonPath("$[?(@.eventType == 'RECONCILIATION_RECORDED')].metadata.externalStatus",
                    hasItem("SUCCESS")))
                .andExpect(jsonPath("$[?(@.eventType == 'RECONCILIATION_RECORDED')].metadata.reconciliationResult",
                    hasItem("MATCHED")));
        }
    }

    // -------------------------------------------------------------------------
    // Reversal
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("reversal transactions")
    class ReversalTransactions {

        @Test
        @DisplayName("reversal transaction timeline contains REVERSAL_OF event")
        void timeline_reversalTransaction_containsReversalOfEvent() throws Exception {
            String originalId = postTransaction("AUDIT-ORIG-001");

            var reversalBody = Map.of("referenceId", "AUDIT-REV-001");
            String reversalJson = mockMvc.perform(post(TX_BASE + "/" + originalId + "/reversal")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(reversalBody)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

            @SuppressWarnings("unchecked")
            String reversalId = (String) objectMapper.readValue(reversalJson, Map.class).get("transactionId");

            mockMvc.perform(get(TX_BASE + "/" + reversalId + TIMELINE_SUF))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.eventType == 'REVERSAL_OF')]").isArray())
                .andExpect(jsonPath("$[?(@.eventType == 'REVERSAL_OF')].metadata.originalReferenceId",
                    hasItem("AUDIT-ORIG-001")));
        }

        @Test
        @DisplayName("original transaction timeline contains REVERSED_BY event")
        void timeline_originalTransaction_containsReversedByEvent() throws Exception {
            String originalId = postTransaction("AUDIT-ORIG-002");

            var reversalBody = Map.of("referenceId", "AUDIT-REV-002");
            mockMvc.perform(post(TX_BASE + "/" + originalId + "/reversal")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(reversalBody)))
                .andExpect(status().isCreated());

            mockMvc.perform(get(TX_BASE + "/" + originalId + TIMELINE_SUF))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.eventType == 'REVERSED_BY')]").isArray())
                .andExpect(jsonPath("$[?(@.eventType == 'REVERSED_BY')].metadata.reversalReferenceId",
                    hasItem("AUDIT-REV-002")));
        }
    }

    // -------------------------------------------------------------------------
    // Outbox events
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("outbox events in timeline")
    class OutboxEvents {

        @Test
        @DisplayName("timeline contains at least one OUTBOX_EVENT entry for a posted transaction")
        void timeline_postedTransaction_hasOutboxEntry() throws Exception {
            String txId = postTransaction("AUDIT-OUTBOX-001");

            mockMvc.perform(get(TX_BASE + "/" + txId + TIMELINE_SUF))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                    "$[?(@.eventType == 'OUTBOX_EVENT_QUEUED' || @.eventType == 'OUTBOX_EVENT_PUBLISHED')]")
                    .isArray())
                .andExpect(jsonPath(
                    "$[?(@.eventType == 'OUTBOX_EVENT_QUEUED' || @.eventType == 'OUTBOX_EVENT_PUBLISHED')]",
                    hasSize(greaterThanOrEqualTo(1))));
        }

        @Test
        @DisplayName("outbox events include the domain event type in metadata")
        void timeline_outboxEvents_includeEventTypeInMetadata() throws Exception {
            String txId = postTransaction("AUDIT-OUTBOX-META-001");

            // Filter all outbox items; every one must carry a non-null domainEventType in metadata
            mockMvc.perform(get(TX_BASE + "/" + txId + TIMELINE_SUF))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.source == 'outbox')].metadata.domainEventType",
                    everyItem(not(nullValue()))));
        }
    }
}
