package com.aetherledger.api;

import com.aetherledger.domain.entity.Account;
import com.aetherledger.domain.entity.LedgerEntry;
import com.aetherledger.domain.entity.LedgerTransaction;
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

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("OpsController")
class OpsControllerTest extends AbstractIntegrationTest {

    private static final String LEDGER_REPORT_ENDPOINT  = "/api/v1/ops/integrity/ledger";
    private static final String LEDGER_ISSUES_ENDPOINT  = "/api/v1/ops/integrity/ledger/issues";
    private static final String RECON_HEALTH_ENDPOINT   = "/api/v1/ops/integrity/reconciliation";
    private static final String TX_ENDPOINT             = "/api/v1/ledger-transactions";

    @Autowired MockMvc                      mockMvc;
    @Autowired ObjectMapper                 objectMapper;
    @Autowired AccountRepository            accountRepository;
    @Autowired HoldRepository               holdRepository;
    @Autowired LedgerTransactionRepository  ledgerTransactionRepository;
    @Autowired LedgerEntryRepository        ledgerEntryRepository;
    @Autowired ReconciliationRunItemRepository reconciliationRunItemRepository;
    @Autowired ReconciliationRunRepository  reconciliationRunRepository;

    private Account accountA;
    private Account accountB;

    @BeforeEach
    void setUp() {
        reconciliationRunItemRepository.deleteAllInBatch();
        reconciliationRunRepository.deleteAllInBatch();
        ledgerEntryRepository.deleteAllInBatch();
        holdRepository.deleteAllInBatch();
        ledgerTransactionRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();

        accountA = accountRepository.save(Account.of("Ops – Alpha",  AccountType.SYSTEM));
        accountB = accountRepository.save(Account.of("Ops – Beta",   AccountType.SYSTEM));
    }

    // =========================================================================
    // GET /api/v1/ops/integrity/ledger
    // =========================================================================

    @Nested
    @DisplayName("GET /api/v1/ops/integrity/ledger")
    class LedgerIntegrityReport {

        @Test
        @DisplayName("empty system returns healthy report with all zeros")
        void emptySystem_returnsHealthy() throws Exception {
            mockMvc.perform(get(LEDGER_REPORT_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTransactions").value(0))
                .andExpect(jsonPath("$.successfulTransactions").value(0))
                .andExpect(jsonPath("$.pendingTransactions").value(0))
                .andExpect(jsonPath("$.failedTransactions").value(0))
                .andExpect(jsonPath("$.reversedTransactionsCount").value(0))
                .andExpect(jsonPath("$.zeroSumViolationsCount").value(0))
                .andExpect(jsonPath("$.transactionsWithUnexpectedEntryCount").value(0))
                .andExpect(jsonPath("$.healthy").value(true));
        }

        @Test
        @DisplayName("normal successful transaction keeps report healthy")
        void normalTransaction_remainsHealthy() throws Exception {
            postTransaction("REF-INTEGRITY-OK");

            mockMvc.perform(get(LEDGER_REPORT_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTransactions").value(1))
                .andExpect(jsonPath("$.successfulTransactions").value(1))
                .andExpect(jsonPath("$.zeroSumViolationsCount").value(0))
                .andExpect(jsonPath("$.transactionsWithUnexpectedEntryCount").value(0))
                .andExpect(jsonPath("$.healthy").value(true));
        }

        @Test
        @DisplayName("status counts are correct across mixed transaction states")
        void countsStatusesCorrectly() throws Exception {
            // 1 immediate SUCCESS via service
            postTransaction("REF-COUNT-A");

            // 1 PENDING via service
            String pendingId = createPendingTransaction("REF-COUNT-B");

            // 1 FAILED via service
            String pendingToFail = createPendingTransaction("REF-COUNT-C");
            mockMvc.perform(post(TX_ENDPOINT + "/{id}/fail", pendingToFail))
                .andExpect(status().isOk());

            mockMvc.perform(get(LEDGER_REPORT_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTransactions").value(3))
                .andExpect(jsonPath("$.successfulTransactions").value(1))
                .andExpect(jsonPath("$.pendingTransactions").value(1))
                .andExpect(jsonPath("$.failedTransactions").value(1))
                .andExpect(jsonPath("$.healthy").value(true));
        }

        @Test
        @DisplayName("SUCCESS transaction with no entries → unhealthy, unexpectedEntryCount incremented")
        void successWithNoEntries_unhealthy() throws Exception {
            seedSuccessTransactionWithNoEntries("REF-BAD-NO-ENTRIES");

            mockMvc.perform(get(LEDGER_REPORT_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionsWithUnexpectedEntryCount").value(1))
                .andExpect(jsonPath("$.healthy").value(false));
        }

        @Test
        @DisplayName("SUCCESS transaction with non-zero entry sum → unhealthy, zeroSumViolations incremented")
        void successWithNonZeroSum_unhealthy() throws Exception {
            seedZeroSumViolation("REF-BAD-SUM");

            mockMvc.perform(get(LEDGER_REPORT_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zeroSumViolationsCount").value(1))
                .andExpect(jsonPath("$.transactionsWithUnexpectedEntryCount").value(0))
                .andExpect(jsonPath("$.healthy").value(false));
        }
    }

    // =========================================================================
    // GET /api/v1/ops/integrity/ledger/issues
    // =========================================================================

    @Nested
    @DisplayName("GET /api/v1/ops/integrity/ledger/issues")
    class LedgerIntegrityIssues {

        @Test
        @DisplayName("empty system returns empty issues list")
        void emptySystem_returnsEmptyList() throws Exception {
            mockMvc.perform(get(LEDGER_ISSUES_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("normal successful transaction does not appear in issues")
        void normalTransaction_notInIssues() throws Exception {
            postTransaction("REF-CLEAN-TX");

            mockMvc.perform(get(LEDGER_ISSUES_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("SUCCESS transaction with no entries appears with UNEXPECTED_ENTRY_COUNT")
        void successWithNoEntries_appearsWithUnexpectedEntryCount() throws Exception {
            seedSuccessTransactionWithNoEntries("REF-ISSUE-NO-ENTRIES");

            mockMvc.perform(get(LEDGER_ISSUES_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].referenceId").value("REF-ISSUE-NO-ENTRIES"))
                .andExpect(jsonPath("$[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$[0].entryCount").value(0))
                .andExpect(jsonPath("$[0].issueTypes", hasItem("UNEXPECTED_ENTRY_COUNT")))
                .andExpect(jsonPath("$[0].issueTypes", not(hasItem("ZERO_SUM_VIOLATION"))));
        }

        @Test
        @DisplayName("SUCCESS transaction with non-zero entry sum appears with ZERO_SUM_VIOLATION")
        void zeroSumViolation_appearsWithCorrectIssueType() throws Exception {
            seedZeroSumViolation("REF-ISSUE-SUM");

            mockMvc.perform(get(LEDGER_ISSUES_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].referenceId").value("REF-ISSUE-SUM"))
                .andExpect(jsonPath("$[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$[0].entryCount").value(2))
                .andExpect(jsonPath("$[0].issueTypes", hasItem("ZERO_SUM_VIOLATION")))
                .andExpect(jsonPath("$[0].issueTypes", not(hasItem("UNEXPECTED_ENTRY_COUNT"))));
        }

        @Test
        @DisplayName("healthy and unhealthy transactions together — only unhealthy appears in issues")
        void mixOfHealthyAndBad_onlyBadAppears() throws Exception {
            postTransaction("REF-MIX-GOOD");
            seedSuccessTransactionWithNoEntries("REF-MIX-BAD");

            mockMvc.perform(get(LEDGER_ISSUES_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].referenceId").value("REF-MIX-BAD"));
        }
    }

    // =========================================================================
    // GET /api/v1/ops/integrity/reconciliation
    // =========================================================================

    @Nested
    @DisplayName("GET /api/v1/ops/integrity/reconciliation")
    class ReconciliationHealth {

        @Test
        @DisplayName("empty system is healthy with all zeros")
        void emptySystem_returnsHealthy() throws Exception {
            mockMvc.perform(get(RECON_HEALTH_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTransactions").value(0))
                .andExpect(jsonPath("$.matchedCount").value(0))
                .andExpect(jsonPath("$.mismatchCount").value(0))
                .andExpect(jsonPath("$.notReconciledCount").value(0))
                .andExpect(jsonPath("$.missingExternalReferenceCount").value(0))
                .andExpect(jsonPath("$.healthy").value(true));
        }

        @Test
        @DisplayName("unreconciled transaction makes healthy false only when mismatch or missing ref present")
        void onlyUnreconciledTransactions_stillHealthy() throws Exception {
            // Unreconciled transactions alone do not make the system unhealthy
            postTransaction("REF-RECON-UNRECONCILED");

            mockMvc.perform(get(RECON_HEALTH_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notReconciledCount").value(1))
                .andExpect(jsonPath("$.mismatchCount").value(0))
                .andExpect(jsonPath("$.missingExternalReferenceCount").value(0))
                .andExpect(jsonPath("$.healthy").value(true));
        }

        @Test
        @DisplayName("mismatch count makes healthy false")
        void mismatch_makesUnhealthy() throws Exception {
            String txId = postTransaction("REF-RECON-MISMATCH");

            // Reconcile as FAILED but internal status is SUCCESS → mismatch
            mockMvc.perform(post(TX_ENDPOINT + "/{id}/reconcile", txId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of("externalReferenceId", "EXT-MM", "externalStatus", "FAILED"))))
                .andExpect(status().isOk());

            mockMvc.perform(get(RECON_HEALTH_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mismatchCount").value(1))
                .andExpect(jsonPath("$.healthy").value(false));
        }

        @Test
        @DisplayName("missing external reference makes healthy false")
        void missingExternalReference_makesUnhealthy() throws Exception {
            String txId = postTransaction("REF-RECON-MISSING-REF");

            // Reconcile without an externalReferenceId → MISSING_EXTERNAL_REFERENCE
            mockMvc.perform(post(TX_ENDPOINT + "/{id}/reconcile", txId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of("externalStatus", "SUCCESS"))))
                .andExpect(status().isOk());

            mockMvc.perform(get(RECON_HEALTH_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.missingExternalReferenceCount").value(1))
                .andExpect(jsonPath("$.healthy").value(false));
        }

        @Test
        @DisplayName("fully matched transaction makes healthy true")
        void matchedTransaction_isHealthy() throws Exception {
            String txId = postTransaction("REF-RECON-MATCHED");

            mockMvc.perform(post(TX_ENDPOINT + "/{id}/reconcile", txId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of("externalReferenceId", "EXT-OK", "externalStatus", "SUCCESS"))))
                .andExpect(status().isOk());

            mockMvc.perform(get(RECON_HEALTH_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedCount").value(1))
                .andExpect(jsonPath("$.mismatchCount").value(0))
                .andExpect(jsonPath("$.missingExternalReferenceCount").value(0))
                .andExpect(jsonPath("$.healthy").value(true));
        }

        @Test
        @DisplayName("reconciliation health reflects correct counts across mixed states")
        void mixedReconStates_correctCounts() throws Exception {
            String tx1 = postTransaction("REF-MIX-RECON-1");
            String tx2 = postTransaction("REF-MIX-RECON-2");
            postTransaction("REF-MIX-RECON-3"); // stays unreconciled

            // tx1 → matched
            mockMvc.perform(post(TX_ENDPOINT + "/{id}/reconcile", tx1)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of("externalReferenceId", "EXT-1", "externalStatus", "SUCCESS"))))
                .andExpect(status().isOk());

            // tx2 → mismatch
            mockMvc.perform(post(TX_ENDPOINT + "/{id}/reconcile", tx2)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of("externalReferenceId", "EXT-2", "externalStatus", "FAILED"))))
                .andExpect(status().isOk());

            mockMvc.perform(get(RECON_HEALTH_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTransactions").value(3))
                .andExpect(jsonPath("$.matchedCount").value(1))
                .andExpect(jsonPath("$.mismatchCount").value(1))
                .andExpect(jsonPath("$.notReconciledCount").value(1))
                .andExpect(jsonPath("$.healthy").value(false));
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Posts an immediate SUCCESS transaction via the API and returns its transactionId. */
    private String postTransaction(String referenceId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("debitAccountId",  accountA.getId());
        body.put("creditAccountId", accountB.getId());
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

    /** Creates a PENDING transaction via the API and returns its transactionId. */
    private String createPendingTransaction(String referenceId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("debitAccountId",  accountA.getId());
        body.put("creditAccountId", accountB.getId());
        body.put("amount",          new BigDecimal("100.00"));
        body.put("referenceId",     referenceId);

        var result = mockMvc.perform(post(TX_ENDPOINT + "/pending")
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(body)))
            .andExpect(status().isCreated())
            .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
            .get("transactionId").asText();
    }

    /**
     * Directly seeds a SUCCESS transaction with zero entries, bypassing service
     * validation.  Used to simulate a structurally corrupt ledger record.
     */
    private void seedSuccessTransactionWithNoEntries(String referenceId) {
        LedgerTransaction tx = LedgerTransaction.create(referenceId);
        tx.markAsSuccess();
        ledgerTransactionRepository.save(tx);
    }

    /**
     * Directly seeds a SUCCESS transaction with two credit entries (both positive),
     * so the entries sum to a non-zero value.  Bypasses service validation to
     * simulate a zero-sum violation.
     */
    private void seedZeroSumViolation(String referenceId) {
        LedgerTransaction tx = LedgerTransaction.create(referenceId);
        LedgerEntry.credit(accountA, tx, new BigDecimal("100.00"));
        LedgerEntry.credit(accountB, tx, new BigDecimal("50.00")); // sum = +150, not zero
        tx.markAsSuccess();
        ledgerTransactionRepository.save(tx); // CASCADE PERSIST writes the entries
    }

    private String toJson(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
