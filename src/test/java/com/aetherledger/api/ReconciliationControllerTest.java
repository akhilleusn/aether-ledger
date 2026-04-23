package com.aetherledger.api;

import com.aetherledger.domain.entity.Account;
import com.aetherledger.domain.enums.AccountType;
import com.aetherledger.repository.AccountRepository;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("ReconciliationController")
class ReconciliationControllerTest extends AbstractIntegrationTest {

    private static final String BATCH_ENDPOINT   = "/api/v1/reconciliation/batch";
    private static final String SUMMARY_ENDPOINT = "/api/v1/reconciliation/summary";
    private static final String TX_ENDPOINT      = "/api/v1/ledger-transactions";

    @Autowired MockMvc          mockMvc;
    @Autowired ObjectMapper     objectMapper;
    @Autowired AccountRepository accountRepository;
    @Autowired LedgerTransactionRepository ledgerTransactionRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired ReconciliationRunItemRepository reconciliationRunItemRepository;
    @Autowired ReconciliationRunRepository reconciliationRunRepository;

    private Account debitAccount;
    private Account creditAccount;

    @BeforeEach
    void setUp() {
        reconciliationRunItemRepository.deleteAllInBatch();
        reconciliationRunRepository.deleteAllInBatch();
        ledgerEntryRepository.deleteAllInBatch();
        ledgerTransactionRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();

        debitAccount  = accountRepository.save(Account.of("Reconcile – Debit",  AccountType.SYSTEM));
        creditAccount = accountRepository.save(Account.of("Reconcile – Credit", AccountType.SYSTEM));
    }

    // =========================================================================
    // POST /api/v1/reconciliation/batch
    // =========================================================================

    @Nested
    @DisplayName("POST /api/v1/reconciliation/batch")
    class BatchReconcile {

        @Test
        @DisplayName("all matching records → all updated, matched count correct")
        void batch_allMatching_updatesAllTransactions() throws Exception {
            postTransaction("REF-BATCH-A");
            postTransaction("REF-BATCH-B");
            postTransaction("REF-BATCH-C");

            var batchBody = batchRequest(List.of(
                batchItem("REF-BATCH-A", "EXT-A", "SUCCESS"),
                batchItem("REF-BATCH-B", "EXT-B", "SUCCESS"),
                batchItem("REF-BATCH-C", "EXT-C", "SUCCESS")
            ));

            mockMvc.perform(post(BATCH_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(batchBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.updatedCount").value(3))
                .andExpect(jsonPath("$.matchedCount").value(3))
                .andExpect(jsonPath("$.mismatchCount").value(0))
                .andExpect(jsonPath("$.missingTransactionCount").value(0))
                .andExpect(jsonPath("$.results", hasSize(3)))
                .andExpect(jsonPath("$.results[0].status").value("UPDATED"))
                .andExpect(jsonPath("$.results[0].reconciliationResult").value("MATCHED"));
        }

        @Test
        @DisplayName("mix of matching and missing referenceIds → correct totals")
        void batch_mixedMatchingAndMissing_correctTotals() throws Exception {
            postTransaction("REF-BATCH-D");
            postTransaction("REF-BATCH-E");

            var batchBody = batchRequest(List.of(
                batchItem("REF-BATCH-D",         "EXT-D", "SUCCESS"),
                batchItem("REF-BATCH-E",         "EXT-E", "SUCCESS"),
                batchItem("REF-BATCH-NONEXISTENT","EXT-X", "SUCCESS")
            ));

            mockMvc.perform(post(BATCH_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(batchBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.updatedCount").value(2))
                .andExpect(jsonPath("$.matchedCount").value(2))
                .andExpect(jsonPath("$.missingTransactionCount").value(1))
                .andExpect(jsonPath("$.results[2].status").value("NOT_FOUND"))
                .andExpect(jsonPath("$.results[2].transactionId").doesNotExist())
                .andExpect(jsonPath("$.results[2].reconciliationResult").doesNotExist());
        }

        @Test
        @DisplayName("mismatched statuses → mismatch count incremented correctly")
        void batch_mismatchedStatuses_incrementsMismatchCount() throws Exception {
            postTransaction("REF-BATCH-F"); // SUCCESS internally
            postTransaction("REF-BATCH-G"); // SUCCESS internally

            var batchBody = batchRequest(List.of(
                batchItem("REF-BATCH-F", "EXT-F", "FAILED"),  // mismatch
                batchItem("REF-BATCH-G", "EXT-G", "SUCCESS")  // matched
            ));

            mockMvc.perform(post(BATCH_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(batchBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.updatedCount").value(2))
                .andExpect(jsonPath("$.matchedCount").value(1))
                .andExpect(jsonPath("$.mismatchCount").value(1))
                .andExpect(jsonPath("$.results[0].reconciliationResult").value("STATUS_MISMATCH"))
                .andExpect(jsonPath("$.results[1].reconciliationResult").value("MATCHED"));
        }

        @Test
        @DisplayName("missing externalReferenceId in item → MISSING_EXTERNAL_REFERENCE result")
        void batch_missingExternalReference_countedCorrectly() throws Exception {
            postTransaction("REF-BATCH-H");

            var item = new LinkedHashMap<String, Object>();
            item.put("referenceId",   "REF-BATCH-H");
            item.put("externalStatus", "SUCCESS");
            // externalReferenceId deliberately omitted

            mockMvc.perform(post(BATCH_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(batchRequest(List.of(item)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(1))
                .andExpect(jsonPath("$.matchedCount").value(0))
                .andExpect(jsonPath("$.missingExternalReferenceCount").value(1))
                .andExpect(jsonPath("$.results[0].reconciliationResult").value("MISSING_EXTERNAL_REFERENCE"));
        }

        @Test
        @DisplayName("batch updates reconciledAt and externalReferenceId on the transaction")
        void batch_updatesExternalFieldsOnTransaction() throws Exception {
            String txId = postTransaction("REF-BATCH-I");

            var batchBody = batchRequest(List.of(
                batchItem("REF-BATCH-I", "EXT-I", "SUCCESS")
            ));

            mockMvc.perform(post(BATCH_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(batchBody)))
                .andExpect(status().isOk());

            // Verify that the transaction's reconciliation fields are persisted
            mockMvc.perform(get(TX_ENDPOINT + "/{id}", txId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalReferenceId").value("EXT-I"))
                .andExpect(jsonPath("$.externalStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.reconciledAt").isNotEmpty())
                .andExpect(jsonPath("$.reconciliationResult").value("MATCHED"));
        }

        @Test
        @DisplayName("blank referenceId in batch item returns 400 VALIDATION_FAILED")
        void batch_blankReferenceId_returns400() throws Exception {
            var batchBody = batchRequest(List.of(
                batchItem("   ", "EXT-INVALID", "SUCCESS")
            ));

            mockMvc.perform(post(BATCH_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(batchBody)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("empty items list returns 400 VALIDATION_FAILED")
        void batch_emptyItemsList_returns400() throws Exception {
            mockMvc.perform(post(BATCH_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(batchRequest(List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("missing items field returns 400 VALIDATION_FAILED")
        void batch_missingItemsField_returns400() throws Exception {
            mockMvc.perform(post(BATCH_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }
    }

    // =========================================================================
    // GET /api/v1/reconciliation/summary
    // =========================================================================

    @Nested
    @DisplayName("GET /api/v1/reconciliation/summary")
    class ReconciliationSummary {

        @Test
        @DisplayName("summary returns correct aggregate counts across all reconciliation states")
        void summary_returnsCorrectCounts() throws Exception {
            String txId1 = postTransaction("REF-SUMMARY-1"); // will be MATCHED
            String txId2 = postTransaction("REF-SUMMARY-2"); // will be STATUS_MISMATCH
            String txId3 = postTransaction("REF-SUMMARY-3"); // stays NOT_RECONCILED

            // Reconcile tx1 as MATCHED
            mockMvc.perform(post(TX_ENDPOINT + "/{id}/reconcile", txId1)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of("externalReferenceId", "EXT-S1", "externalStatus", "SUCCESS"))))
                .andExpect(status().isOk());

            // Reconcile tx2 as STATUS_MISMATCH (internal SUCCESS, external FAILED)
            mockMvc.perform(post(TX_ENDPOINT + "/{id}/reconcile", txId2)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of("externalReferenceId", "EXT-S2", "externalStatus", "FAILED"))))
                .andExpect(status().isOk());

            // tx3 is left unreconciled

            mockMvc.perform(get(SUMMARY_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTransactions").value(3))
                .andExpect(jsonPath("$.matchedCount").value(1))
                .andExpect(jsonPath("$.mismatchCount").value(1))
                .andExpect(jsonPath("$.notReconciledCount").value(1))
                .andExpect(jsonPath("$.missingExternalReferenceCount").value(0));
        }

        @Test
        @DisplayName("summary returns zero counts when no transactions exist")
        void summary_noTransactions_returnsAllZeroes() throws Exception {
            mockMvc.perform(get(SUMMARY_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTransactions").value(0))
                .andExpect(jsonPath("$.matchedCount").value(0))
                .andExpect(jsonPath("$.mismatchCount").value(0))
                .andExpect(jsonPath("$.notReconciledCount").value(0))
                .andExpect(jsonPath("$.missingExternalReferenceCount").value(0));
        }
    }

    // =========================================================================
    // GET /api/v1/reconciliation/runs/{id}
    // =========================================================================

    @Nested
    @DisplayName("GET /api/v1/reconciliation/runs/{id}")
    class BatchRunAudit {

        @Test
        @DisplayName("batch response includes runId that is a valid UUID")
        void batchResponse_includesRunId() throws Exception {
            postTransaction("REF-RUN-A");

            var result = mockMvc.perform(post(BATCH_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(batchRequest(List.of(
                        batchItem("REF-RUN-A", "EXT-RA", "SUCCESS")
                    )))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").isNotEmpty())
                .andReturn();

            String runId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("runId").asText();

            // runId should be parseable as UUID without throwing
            java.util.UUID.fromString(runId);
        }

        @Test
        @DisplayName("get run by id returns full audit details with all item outcomes")
        void getRunById_returnsAuditDetails() throws Exception {
            postTransaction("REF-RUN-B");
            postTransaction("REF-RUN-C");

            var batchResult = mockMvc.perform(post(BATCH_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(batchRequest(List.of(
                        batchItem("REF-RUN-B",     "EXT-RB", "SUCCESS"),
                        batchItem("REF-RUN-C",     "EXT-RC", "FAILED"),   // mismatch
                        batchItem("REF-RUN-GHOST", "EXT-RX", "SUCCESS")   // not found
                    )))))
                .andExpect(status().isOk())
                .andReturn();

            String runId = objectMapper.readTree(batchResult.getResponse().getContentAsString())
                .get("runId").asText();

            mockMvc.perform(get("/api/v1/reconciliation/runs/{id}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.updatedCount").value(2))
                .andExpect(jsonPath("$.matchedCount").value(1))
                .andExpect(jsonPath("$.mismatchCount").value(1))
                .andExpect(jsonPath("$.missingTransactionCount").value(1))
                .andExpect(jsonPath("$.results", hasSize(3)));
        }

        @Test
        @DisplayName("missing run id returns 404 RECONCILIATION_RUN_NOT_FOUND")
        void getRunById_unknownId_returns404() throws Exception {
            mockMvc.perform(get("/api/v1/reconciliation/runs/{id}",
                        "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RECONCILIATION_RUN_NOT_FOUND"));
        }

        @Test
        @DisplayName("non-UUID run id returns 400 with INVALID_PATH_VARIABLE")
        void getRunById_invalidUuid_returns400() throws Exception {
            mockMvc.perform(get("/api/v1/reconciliation/runs/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PATH_VARIABLE"))
                .andExpect(jsonPath("$.message").value("Request path contains an invalid value."));
        }

        @Test
        @DisplayName("run record totals match actual item breakdown after mixed batch")
        void getRunById_mixedBatch_totalsAreConsistent() throws Exception {
            postTransaction("REF-RUN-D");
            postTransaction("REF-RUN-E");
            postTransaction("REF-RUN-F");

            var batchResult = mockMvc.perform(post(BATCH_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(batchRequest(List.of(
                        batchItem("REF-RUN-D",     "EXT-RD", "SUCCESS"),  // matched
                        batchItem("REF-RUN-E",     "EXT-RE", "FAILED"),   // mismatch
                        batchItem("REF-RUN-F",     null,     "SUCCESS"),  // missing external ref
                        batchItem("REF-RUN-GHOST2","EXT-RG", "SUCCESS")   // not found
                    )))))
                .andExpect(status().isOk())
                .andReturn();

            String runId = objectMapper.readTree(batchResult.getResponse().getContentAsString())
                .get("runId").asText();

            mockMvc.perform(get("/api/v1/reconciliation/runs/{id}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(4))
                .andExpect(jsonPath("$.updatedCount").value(3))
                .andExpect(jsonPath("$.matchedCount").value(1))
                .andExpect(jsonPath("$.mismatchCount").value(1))
                .andExpect(jsonPath("$.missingExternalReferenceCount").value(1))
                .andExpect(jsonPath("$.missingTransactionCount").value(1))
                .andExpect(jsonPath("$.results", hasSize(4)));
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Posts an immediate SUCCESS transaction and returns its transactionId. */
    private String postTransaction(String referenceId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("debitAccountId",  debitAccount.getId());
        body.put("creditAccountId", creditAccount.getId());
        body.put("amount",          new BigDecimal("100.00"));
        body.put("referenceId",     referenceId);

        var result = mockMvc.perform(post(TX_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(body)))
            .andExpect(status().isCreated())
            .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
            .get("transactionId").asText();
    }

    private Map<String, Object> batchItem(String referenceId, String externalRef, String externalStatus) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("referenceId",         referenceId);
        item.put("externalReferenceId", externalRef);
        item.put("externalStatus",      externalStatus);
        return item;
    }

    private Map<String, Object> batchRequest(List<?> items) {
        return Map.of("items", items);
    }

    private String toJson(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
