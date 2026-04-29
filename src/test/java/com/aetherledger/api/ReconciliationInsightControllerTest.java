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
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@code GET /api/v1/ledger-transactions/{id}/reconciliation-insight}.
 *
 * <p>Covers all four reconciliation result states, the 404 path for an unknown
 * transaction, and the 400 path for a malformed UUID.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("ReconciliationInsight endpoint")
class ReconciliationInsightControllerTest extends AbstractIntegrationTest {

    private static final String TX_BASE     = "/api/v1/ledger-transactions";
    private static final String INSIGHT_SUF = "/reconciliation-insight";

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

    private void reconcile(String txId, String externalRef, String externalStatus) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        if (externalRef != null) body.put("externalReferenceId", externalRef);
        body.put("externalStatus", externalStatus);

        mockMvc.perform(post(TX_BASE + "/" + txId + "/reconcile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // 404 — unknown transaction
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("404 Not Found")
    class NotFoundCases {

        @Test
        @DisplayName("unknown transaction UUID returns 404 TRANSACTION_NOT_FOUND")
        void insight_unknownId_returns404() throws Exception {
            mockMvc.perform(get(TX_BASE + "/" + UUID.randomUUID() + INSIGHT_SUF))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TRANSACTION_NOT_FOUND"));
        }
    }

    // -------------------------------------------------------------------------
    // 400 — invalid UUID path variable
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("400 Bad Request")
    class BadRequestCases {

        @Test
        @DisplayName("non-UUID path variable returns 400 INVALID_PATH_VARIABLE")
        void insight_invalidUuid_returns400() throws Exception {
            mockMvc.perform(get(TX_BASE + "/not-a-uuid" + INSIGHT_SUF))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PATH_VARIABLE"));
        }
    }

    // -------------------------------------------------------------------------
    // 200 — four reconciliation result states
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("reconciliation insight cases")
    class InsightCases {

        @Test
        @DisplayName("NOT_RECONCILED — unreconciled transaction returns explanation with recommended checks")
        void insight_notReconciled_returnsExplanation() throws Exception {
            String txId = postTransaction("INSIGHT-NOT-RECONCILED-001");

            mockMvc.perform(get(TX_BASE + "/" + txId + INSIGHT_SUF))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(txId))
                .andExpect(jsonPath("$.referenceId").value("INSIGHT-NOT-RECONCILED-001"))
                .andExpect(jsonPath("$.internalStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.externalStatus").doesNotExist())
                .andExpect(jsonPath("$.reconciliationResult").value("NOT_RECONCILED"))
                .andExpect(jsonPath("$.explanation").isNotEmpty())
                .andExpect(jsonPath("$.recommendedChecks", not(empty())));
        }

        @Test
        @DisplayName("MATCHED — matched transaction returns no-anomaly explanation")
        void insight_matched_returnsNoAnomalyExplanation() throws Exception {
            String txId = postTransaction("INSIGHT-MATCHED-001");
            reconcile(txId, "EXT-MATCH-001", "SUCCESS");

            mockMvc.perform(get(TX_BASE + "/" + txId + INSIGHT_SUF))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reconciliationResult").value("MATCHED"))
                .andExpect(jsonPath("$.internalStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.externalStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.explanation").value(containsString("No anomaly detected")))
                .andExpect(jsonPath("$.recommendedChecks[0]").value("No action required."));
        }

        @Test
        @DisplayName("STATUS_MISMATCH (SUCCESS/FAILED) — returns detailed possible-causes explanation")
        void insight_statusMismatch_successInternal_failedExternal_returnsDetailedExplanation() throws Exception {
            String txId = postTransaction("INSIGHT-MISMATCH-SF-001");
            reconcile(txId, "EXT-MISMATCH-001", "FAILED");

            mockMvc.perform(get(TX_BASE + "/" + txId + INSIGHT_SUF))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reconciliationResult").value("STATUS_MISMATCH"))
                .andExpect(jsonPath("$.internalStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.externalStatus").value("FAILED"))
                .andExpect(jsonPath("$.explanation").value(containsString("Possible causes")))
                .andExpect(jsonPath("$.recommendedChecks.length()").value(greaterThan(1)));
        }

        @Test
        @DisplayName("MISSING_EXTERNAL_REFERENCE — reconciled without reference returns explanation")
        void insight_missingExternalReference_returnsExplanation() throws Exception {
            String txId = postTransaction("INSIGHT-MISSING-REF-001");
            // Omit externalReferenceId — entity reconciliation records externalStatus only
            reconcile(txId, null, "SUCCESS");

            mockMvc.perform(get(TX_BASE + "/" + txId + INSIGHT_SUF))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reconciliationResult").value("MISSING_EXTERNAL_REFERENCE"))
                .andExpect(jsonPath("$.explanation").isNotEmpty())
                .andExpect(jsonPath("$.recommendedChecks", not(empty())));
        }
    }
}
