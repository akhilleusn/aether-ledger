package com.aetherledger.api;

import com.aetherledger.domain.entity.Account;
import com.aetherledger.domain.enums.AccountType;
import com.aetherledger.repository.AccountRepository;
import com.aetherledger.repository.HoldRepository;
import com.aetherledger.repository.LedgerEntryRepository;
import com.aetherledger.repository.LedgerTransactionRepository;
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
 * Verifies that concurrent writes on the same resource are handled safely.
 *
 * <p>LedgerTransaction carries a {@code @Version} column for optimistic locking.
 * When two concurrent requests both read the same row version and the second
 * writer's UPDATE matches zero rows, Hibernate throws {@code OptimisticLockException}.
 * The GlobalExceptionHandler translates this to HTTP 409 CONCURRENT_MODIFICATION.
 *
 * <p>The {@code reconcile()} service method uses a plain (non-locking) {@code findById},
 * which makes it the natural place to exercise optimistic locking: two threads that
 * both read version N before either commits will race at the save, and the loser
 * gets a 409 rather than silently overwriting the winner.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Concurrent modification handling")
class ConcurrentModificationTest extends AbstractIntegrationTest {

    private static final String TX_ENDPOINT = "/api/v1/ledger-transactions";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AccountRepository accountRepository;
    @Autowired HoldRepository holdRepository;
    @Autowired LedgerTransactionRepository ledgerTransactionRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;

    private Account debitAccount;
    private Account creditAccount;

    @BeforeEach
    void setUp() {
        ledgerEntryRepository.deleteAllInBatch();
        holdRepository.deleteAllInBatch();
        ledgerTransactionRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();

        debitAccount  = accountRepository.save(Account.of("Concurrent – Alice", AccountType.USER));
        creditAccount = accountRepository.save(Account.of("Concurrent – Bob",   AccountType.USER));
    }

    // =========================================================================
    // Concurrent reconcile — optimistic locking path
    // =========================================================================

    @Test
    @DisplayName("concurrent reconcile on same transaction: one succeeds, one returns 409 CONCURRENT_MODIFICATION")
    void concurrentReconcile_oneSucceeds_oneGetsConcurrentModification() throws Exception {
        UUID txId = postSuccessTransaction("CONCURRENT-RECONCILE-001");

        String reconcileBody = objectMapper.writeValueAsString(Map.of(
            "externalReferenceId", "EXT-CONCURRENT-001",
            "externalStatus",      "SUCCESS"
        ));

        List<Integer> statusCodes = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                executor.submit(() -> {
                    try {
                        startGate.await();
                        MvcResult result = mockMvc.perform(
                                post(TX_ENDPOINT + "/" + txId + "/reconcile")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(reconcileBody))
                            .andReturn();
                        statusCodes.add(result.getResponse().getStatus());
                    } catch (Exception e) {
                        statusCodes.add(500);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // Release both threads simultaneously so they race at the DB read.
            startGate.countDown();
            boolean completed = doneLatch.await(10, TimeUnit.SECONDS);

            assertThat(completed).as("both threads should complete within 10 s").isTrue();
        } finally {
            executor.shutdown();
        }

        assertThat(statusCodes).hasSize(2);
        assertThat(statusCodes).as("exactly one request must succeed").contains(200);
        assertThat(statusCodes).as("exactly one request must conflict").contains(409);
    }

    // =========================================================================
    // Concurrent lifecycle transitions — pessimistic-lock path
    // =========================================================================

    @Test
    @DisplayName("concurrent complete + fail on same pending transaction: one succeeds, the other is rejected")
    void concurrentCompleteAndFail_oneSucceeds_otherIsRejected() throws Exception {
        UUID txId = postPendingTransaction("CONCURRENT-LIFECYCLE-001");

        List<Integer> statusCodes = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // Thread 1: complete
            executor.submit(() -> {
                try {
                    startGate.await();
                    MvcResult result = mockMvc.perform(
                            post(TX_ENDPOINT + "/" + txId + "/complete"))
                        .andReturn();
                    statusCodes.add(result.getResponse().getStatus());
                } catch (Exception e) {
                    statusCodes.add(500);
                } finally {
                    doneLatch.countDown();
                }
            });

            // Thread 2: fail
            executor.submit(() -> {
                try {
                    startGate.await();
                    MvcResult result = mockMvc.perform(
                            post(TX_ENDPOINT + "/" + txId + "/fail"))
                        .andReturn();
                    statusCodes.add(result.getResponse().getStatus());
                } catch (Exception e) {
                    statusCodes.add(500);
                } finally {
                    doneLatch.countDown();
                }
            });

            startGate.countDown();
            boolean completed = doneLatch.await(10, TimeUnit.SECONDS);

            assertThat(completed).as("both threads should complete within 10 s").isTrue();
        } finally {
            executor.shutdown();
        }

        assertThat(statusCodes).hasSize(2);

        // The DB-level pessimistic write lock serialises the two requests: the
        // winner transitions to SUCCESS (200) or FAILED (200); the loser reads
        // the already-transitioned row and is rejected with 422 (wrong state).
        // Either thread can win the lock race — we only assert that no request
        // silently corrupts state (no 500, no double-success).
        assertThat(statusCodes).as("one request must succeed with 200").contains(200);
        assertThat(statusCodes).as("the other must be rejected (409 or 422, never 500)")
            .anySatisfy(code -> assertThat(code).isIn(409, 422));
        assertThat(statusCodes).as("no request should produce a server error")
            .doesNotContain(500);
    }

    @Test
    @DisplayName("double complete on same pending transaction: one succeeds with 200, one rejected")
    void doubleComplete_firstSucceeds_secondIsRejected() throws Exception {
        UUID txId = postPendingTransaction("CONCURRENT-DOUBLE-COMPLETE-001");

        List<Integer> statusCodes = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                executor.submit(() -> {
                    try {
                        startGate.await();
                        MvcResult result = mockMvc.perform(
                                post(TX_ENDPOINT + "/" + txId + "/complete"))
                            .andReturn();
                        statusCodes.add(result.getResponse().getStatus());
                    } catch (Exception e) {
                        statusCodes.add(500);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startGate.countDown();
            boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
            assertThat(completed).as("both threads should complete within 10 s").isTrue();
        } finally {
            executor.shutdown();
        }

        assertThat(statusCodes).hasSize(2);
        assertThat(statusCodes).as("exactly one complete must succeed").contains(200);
        assertThat(statusCodes).as("the duplicate must be rejected, never a server error")
            .doesNotContain(500);
        assertThat(statusCodes).as("the rejected request must be 409 or 422")
            .anySatisfy(code -> assertThat(code).isIn(409, 422));
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
