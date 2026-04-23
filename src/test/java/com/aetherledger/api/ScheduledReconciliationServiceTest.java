package com.aetherledger.api;

import com.aetherledger.domain.entity.Account;
import com.aetherledger.domain.entity.LedgerTransaction;
import com.aetherledger.domain.enums.AccountType;
import com.aetherledger.domain.enums.ExternalStatus;
import com.aetherledger.domain.enums.ReconciliationResult;
import com.aetherledger.repository.AccountRepository;
import com.aetherledger.repository.LedgerEntryRepository;
import com.aetherledger.repository.LedgerTransactionRepository;
import com.aetherledger.repository.ReconciliationRunItemRepository;
import com.aetherledger.repository.ReconciliationRunRepository;
import com.aetherledger.service.ScheduledReconciliationService;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Integration tests for {@link ScheduledReconciliationService}.
 *
 * <p>The scheduled job is disabled in the test profile
 * ({@code aetherledger.reconciliation.scheduled.enabled=false}) so that the
 * job never fires automatically.  Tests drive the service directly via
 * {@link ScheduledReconciliationService#processUnreconciled()} to verify
 * business logic without timing dependencies.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("ScheduledReconciliationService")
class ScheduledReconciliationServiceTest extends AbstractIntegrationTest {

    private static final String TX_ENDPOINT = "/api/v1/ledger-transactions";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ScheduledReconciliationService scheduledReconciliationService;
    @Autowired LedgerTransactionRepository ledgerTransactionRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired ReconciliationRunItemRepository reconciliationRunItemRepository;
    @Autowired ReconciliationRunRepository reconciliationRunRepository;
    @Autowired AccountRepository accountRepository;

    private Account debitAccount;
    private Account creditAccount;

    @BeforeEach
    void setUp() {
        // FK-safe deletion order: run items → runs → entries → transactions → accounts
        reconciliationRunItemRepository.deleteAllInBatch();
        reconciliationRunRepository.deleteAllInBatch();
        ledgerEntryRepository.deleteAllInBatch();
        ledgerTransactionRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();

        debitAccount  = accountRepository.save(Account.of("Sched – Alice", AccountType.USER));
        creditAccount = accountRepository.save(Account.of("Sched – Bob",   AccountType.USER));
    }

    // =========================================================================
    // Matched case
    // =========================================================================

    @Nested
    @DisplayName("matched case")
    class MatchedCase {

        @Test
        @DisplayName("SUCCESS transaction with non-FAIL referenceId is reconciled as MATCHED")
        void processUnreconciled_matchedCase_reconcilesToMatched() throws Exception {
            UUID txId = postSuccessTransaction("SCHED-MATCH-001");

            scheduledReconciliationService.processUnreconciled();

            LedgerTransaction tx = ledgerTransactionRepository.findById(txId).orElseThrow();
            assertThat(tx.getReconciledAt()).isNotNull();
            assertThat(tx.getExternalStatus()).isEqualTo(ExternalStatus.SUCCESS);
            assertThat(tx.getExternalReferenceId()).isEqualTo("EXT-SCHED-MATCH-001");
            assertThat(tx.computeReconciliationResult()).isEqualTo(ReconciliationResult.MATCHED);
            assertThat(tx.isProcessing()).isFalse();
        }

        @Test
        @DisplayName("multiple unreconciled transactions are all processed in one run")
        void processUnreconciled_multipleTransactions_allReconciled() throws Exception {
            UUID tx1 = postSuccessTransaction("SCHED-MULTI-001");
            UUID tx2 = postSuccessTransaction("SCHED-MULTI-002");
            UUID tx3 = postSuccessTransaction("SCHED-MULTI-003");

            scheduledReconciliationService.processUnreconciled();

            for (UUID id : List.of(tx1, tx2, tx3)) {
                LedgerTransaction tx = ledgerTransactionRepository.findById(id).orElseThrow();
                assertThat(tx.getReconciledAt()).isNotNull();
                assertThat(tx.computeReconciliationResult()).isEqualTo(ReconciliationResult.MATCHED);
            }
        }
    }

    // =========================================================================
    // Mismatch case
    // =========================================================================

    @Nested
    @DisplayName("mismatch case")
    class MismatchCase {

        @Test
        @DisplayName("SUCCESS transaction with FAIL in referenceId is reconciled as STATUS_MISMATCH")
        void processUnreconciled_mismatchCase_reconcilesToStatusMismatch() throws Exception {
            // FakeExternalReconciliationClient returns FAILED when referenceId contains "FAIL".
            // Internal status is SUCCESS → STATUS_MISMATCH.
            UUID txId = postSuccessTransaction("SCHED-FAIL-001");

            scheduledReconciliationService.processUnreconciled();

            LedgerTransaction tx = ledgerTransactionRepository.findById(txId).orElseThrow();
            assertThat(tx.getReconciledAt()).isNotNull();
            assertThat(tx.getExternalStatus()).isEqualTo(ExternalStatus.FAILED);
            assertThat(tx.computeReconciliationResult()).isEqualTo(ReconciliationResult.STATUS_MISMATCH);
            assertThat(tx.isProcessing()).isFalse();
        }
    }

    // =========================================================================
    // Skip already-reconciled transactions
    // =========================================================================

    @Nested
    @DisplayName("already reconciled")
    class AlreadyReconciled {

        @Test
        @DisplayName("transaction reconciled before job runs is not processed again")
        void processUnreconciled_alreadyReconciled_isSkipped() throws Exception {
            UUID txId = postSuccessTransaction("SCHED-ALREADY-RECONCILED-001");

            // Reconcile manually before the job runs.
            mockMvc.perform(post(TX_ENDPOINT + "/" + txId + "/reconcile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "externalReferenceId", "EXT-MANUAL",
                    "externalStatus", "SUCCESS"
                ))));

            LedgerTransaction before = ledgerTransactionRepository.findById(txId).orElseThrow();
            long versionBeforeJob = before.getVersion();

            scheduledReconciliationService.processUnreconciled();

            LedgerTransaction after = ledgerTransactionRepository.findById(txId).orElseThrow();
            assertThat(after.getVersion())
                .as("version must not change — job must not re-process an already-reconciled transaction")
                .isEqualTo(versionBeforeJob);
        }

        @Test
        @DisplayName("PENDING transaction is not picked up by the job")
        void processUnreconciled_pendingTransaction_isSkipped() throws Exception {
            postPendingTransaction("SCHED-PENDING-001");

            scheduledReconciliationService.processUnreconciled();

            // Pending transactions have no entries and should stay unreconciled.
            List<LedgerTransaction> unreconciled = ledgerTransactionRepository.findUnreconciled();
            assertThat(unreconciled).isEmpty(); // PENDING doesn't match WHERE status=SUCCESS
        }
    }

    // =========================================================================
    // Concurrency safety
    // =========================================================================

    @Nested
    @DisplayName("concurrency safety")
    class ConcurrencySafety {

        @Test
        @DisplayName("two concurrent job runs process each transaction exactly once")
        void processUnreconciled_concurrentRuns_noDoubleProcessing() throws Exception {
            UUID txId = postSuccessTransaction("SCHED-CONCURRENT-001");

            LedgerTransaction before = ledgerTransactionRepository.findById(txId).orElseThrow();
            long initialVersion = before.getVersion();

            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(2);
            List<Exception> errors = Collections.synchronizedList(new ArrayList<>());

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                for (int i = 0; i < 2; i++) {
                    executor.submit(() -> {
                        try {
                            startGate.await();
                            scheduledReconciliationService.processUnreconciled();
                        } catch (Exception e) {
                            errors.add(e);
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                startGate.countDown();
                boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
                assertThat(completed).as("both threads should finish within 10 s").isTrue();
            } finally {
                executor.shutdown();
            }

            assertThat(errors).as("no unexpected exceptions from either thread").isEmpty();

            LedgerTransaction after = ledgerTransactionRepository.findById(txId).orElseThrow();

            // The transaction must be reconciled exactly once.
            // One successful processSingle() commit increments the version by 1.
            // A second successful commit would increment it again — that's the violation.
            assertThat(after.getReconciledAt())
                .as("transaction must have been reconciled")
                .isNotNull();
            assertThat(after.getVersion())
                .as("version must increment by exactly 1 — processed exactly once")
                .isEqualTo(initialVersion + 1);
            assertThat(after.isProcessing())
                .as("processing flag must be cleared after successful reconciliation")
                .isFalse();
        }

        @Test
        @DisplayName("processing flag is false after successful reconciliation")
        void processUnreconciled_afterSuccess_processingFlagCleared() throws Exception {
            UUID txId = postSuccessTransaction("SCHED-FLAG-CLEAR-001");

            scheduledReconciliationService.processUnreconciled();

            LedgerTransaction tx = ledgerTransactionRepository.findById(txId).orElseThrow();
            assertThat(tx.isProcessing()).isFalse();
            assertThat(tx.getReconciledAt()).isNotNull();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private UUID postSuccessTransaction(String referenceId) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "debitAccountId",  debitAccount.getId().toString(),
            "creditAccountId", creditAccount.getId().toString(),
            "amount",          "100.00",
            "referenceId",     referenceId
        ));

        MvcResult result = mockMvc.perform(post(TX_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        var json = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(json.get("transactionId").asText());
    }

    private UUID postPendingTransaction(String referenceId) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "debitAccountId",  debitAccount.getId().toString(),
            "creditAccountId", creditAccount.getId().toString(),
            "amount",          "50.00",
            "referenceId",     referenceId
        ));

        MvcResult result = mockMvc.perform(post(TX_ENDPOINT + "/pending")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        var json = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(json.get("transactionId").asText());
    }
}
