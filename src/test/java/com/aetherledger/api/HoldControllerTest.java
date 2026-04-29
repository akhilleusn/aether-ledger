package com.aetherledger.api;

import com.aetherledger.domain.entity.Account;
import com.aetherledger.domain.enums.AccountType;
import com.aetherledger.repository.AccountRepository;
import com.aetherledger.repository.HoldRepository;
import com.aetherledger.repository.LedgerEntryRepository;
import com.aetherledger.repository.LedgerTransactionRepository;
import com.aetherledger.repository.OutboxEventRepository;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("HoldController")
class HoldControllerTest extends AbstractIntegrationTest {

    private static final String HOLDS_ENDPOINT    = "/api/v1/holds";
    private static final String ACCOUNTS_ENDPOINT = "/api/v1/accounts";

    @Autowired MockMvc                   mockMvc;
    @Autowired ObjectMapper              objectMapper;
    @Autowired AccountRepository         accountRepository;
    @Autowired HoldRepository            holdRepository;
    @Autowired LedgerEntryRepository     ledgerEntryRepository;
    @Autowired LedgerTransactionRepository ledgerTransactionRepository;
    @Autowired OutboxEventRepository     outboxEventRepository;

    private Account alice;

    @BeforeEach
    void setUp() {
        resetDatabase();

        alice = accountRepository.save(Account.of("Alice Wallet", AccountType.USER));
    }

    // =========================================================================
    // POST /api/v1/holds  — create
    // =========================================================================

    @Nested
    @DisplayName("POST /api/v1/holds")
    class CreateHold {

        @Test
        @DisplayName("valid request returns 201 with hold fields")
        void create_validRequest_returns201() throws Exception {
            mockMvc.perform(post(HOLDS_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdRequest(alice.getId(), "25.00", "HOLD-CREATE-001")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.accountId").value(alice.getId().toString()))
                .andExpect(jsonPath("$.amount").value(25.00))
                .andExpect(jsonPath("$.referenceId").value("HOLD-CREATE-001"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.capturedAt").doesNotExist())
                .andExpect(jsonPath("$.releasedAt").doesNotExist());
        }

        @Test
        @DisplayName("unknown accountId returns 404 ACCOUNT_NOT_FOUND")
        void create_unknownAccount_returns404() throws Exception {
            mockMvc.perform(post(HOLDS_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdRequest(UUID.randomUUID(), "10.00", "HOLD-UNKNOWN-ACC")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_FOUND"));
        }

        @Test
        @DisplayName("duplicate referenceId returns 409 DUPLICATE_HOLD_REFERENCE_ID")
        void create_duplicateReferenceId_returns409() throws Exception {
            mockMvc.perform(post(HOLDS_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdRequest(alice.getId(), "10.00", "HOLD-DUP-001")))
                .andExpect(status().isCreated());

            mockMvc.perform(post(HOLDS_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdRequest(alice.getId(), "20.00", "HOLD-DUP-001")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_HOLD_REFERENCE_ID"));
        }

        @Test
        @DisplayName("zero amount returns 400 VALIDATION_FAILED")
        void create_zeroAmount_returns400() throws Exception {
            mockMvc.perform(post(HOLDS_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdRequest(alice.getId(), "0.00", "HOLD-ZERO")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("negative amount returns 400 VALIDATION_FAILED")
        void create_negativeAmount_returns400() throws Exception {
            mockMvc.perform(post(HOLDS_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdRequest(alice.getId(), "-5.00", "HOLD-NEG")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("missing referenceId returns 400 VALIDATION_FAILED")
        void create_missingReferenceId_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                "accountId", alice.getId().toString(),
                "amount",    "10.00"
            ));
            mockMvc.perform(post(HOLDS_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("invalid UUID path variable returns 400 INVALID_PATH_VARIABLE")
        void get_invalidUuid_returns400() throws Exception {
            mockMvc.perform(get(HOLDS_ENDPOINT + "/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PATH_VARIABLE"));
        }
    }

    // =========================================================================
    // GET /api/v1/holds/{id}
    // =========================================================================

    @Nested
    @DisplayName("GET /api/v1/holds/{id}")
    class GetHold {

        @Test
        @DisplayName("returns 200 with hold for known id")
        void getById_knownId_returns200() throws Exception {
            MvcResult result = mockMvc.perform(post(HOLDS_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdRequest(alice.getId(), "30.00", "HOLD-GET-001")))
                .andExpect(status().isCreated())
                .andReturn();

            String id = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();

            mockMvc.perform(get(HOLDS_ENDPOINT + "/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        @Test
        @DisplayName("unknown id returns 404 HOLD_NOT_FOUND")
        void getById_unknownId_returns404() throws Exception {
            mockMvc.perform(get(HOLDS_ENDPOINT + "/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("HOLD_NOT_FOUND"));
        }
    }

    // =========================================================================
    // Balance endpoint — held balance
    // =========================================================================

    @Nested
    @DisplayName("GET /api/v1/accounts/{id}/balance")
    class AccountBalance {

        @Test
        @DisplayName("active hold reduces availableBalance but not currentBalance")
        void balance_activeHold_reducesAvailableBalance() throws Exception {
            // Fund alice via a ledger transaction
            Account system = accountRepository.save(Account.of("System-BAL", AccountType.SYSTEM));
            postTransaction(system.getId(), alice.getId(), new BigDecimal("100.00"), "REF-FUND-BAL");

            // Place hold
            mockMvc.perform(post(HOLDS_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdRequest(alice.getId(), "30.00", "HOLD-BAL-001")))
                .andExpect(status().isCreated());

            mockMvc.perform(get(ACCOUNTS_ENDPOINT + "/{id}/balance", alice.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(alice.getId().toString()))
                .andExpect(jsonPath("$.currentBalance").value(100.00))
                .andExpect(jsonPath("$.heldBalance").value(30.00))
                .andExpect(jsonPath("$.availableBalance").value(70.00));
        }

        @Test
        @DisplayName("no holds — heldBalance is 0 and availableBalance equals currentBalance")
        void balance_noHolds_heldBalanceIsZero() throws Exception {
            mockMvc.perform(get(ACCOUNTS_ENDPOINT + "/{id}/balance", alice.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBalance").value(0))
                .andExpect(jsonPath("$.heldBalance").value(0))
                .andExpect(jsonPath("$.availableBalance").value(0));
        }

        @Test
        @DisplayName("released hold does not reduce availableBalance")
        void balance_releasedHold_doesNotReduceAvailableBalance() throws Exception {
            MvcResult r = mockMvc.perform(post(HOLDS_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdRequest(alice.getId(), "40.00", "HOLD-REL-BAL")))
                .andExpect(status().isCreated())
                .andReturn();
            String holdId = objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();

            mockMvc.perform(post(HOLDS_ENDPOINT + "/{id}/release", holdId))
                .andExpect(status().isOk());

            mockMvc.perform(get(ACCOUNTS_ENDPOINT + "/{id}/balance", alice.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heldBalance").value(0))
                .andExpect(jsonPath("$.availableBalance").value(0));
        }

        @Test
        @DisplayName("unknown accountId returns 404")
        void balance_unknownAccount_returns404() throws Exception {
            mockMvc.perform(get(ACCOUNTS_ENDPOINT + "/{id}/balance", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_FOUND"));
        }
    }

    // =========================================================================
    // POST /api/v1/holds/{id}/release
    // =========================================================================

    @Nested
    @DisplayName("POST /api/v1/holds/{id}/release")
    class ReleaseHold {

        @Test
        @DisplayName("releases ACTIVE hold — status becomes RELEASED and releasedAt is set")
        void release_activeHold_returnsReleased() throws Exception {
            MvcResult r = mockMvc.perform(post(HOLDS_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdRequest(alice.getId(), "50.00", "HOLD-RELEASE-001")))
                .andExpect(status().isCreated())
                .andReturn();
            String holdId = objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();

            mockMvc.perform(post(HOLDS_ENDPOINT + "/{id}/release", holdId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.releasedAt").isNotEmpty());
        }

        @Test
        @DisplayName("releasing an already-released hold returns 422 HOLD_NOT_RELEASABLE")
        void release_alreadyReleased_returns422() throws Exception {
            MvcResult r = mockMvc.perform(post(HOLDS_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdRequest(alice.getId(), "50.00", "HOLD-DOUBLE-REL")))
                .andExpect(status().isCreated())
                .andReturn();
            String holdId = objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();

            mockMvc.perform(post(HOLDS_ENDPOINT + "/{id}/release", holdId))
                .andExpect(status().isOk());

            mockMvc.perform(post(HOLDS_ENDPOINT + "/{id}/release", holdId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("HOLD_NOT_RELEASABLE"));
        }

        @Test
        @DisplayName("releasing a CAPTURED hold returns 422 HOLD_NOT_RELEASABLE")
        void release_capturedHold_returns422() throws Exception {
            Account system = accountRepository.save(Account.of("System-REL", AccountType.SYSTEM));
            postTransaction(system.getId(), alice.getId(), new BigDecimal("100.00"), "REF-FUND-REL");

            MvcResult r = mockMvc.perform(post(HOLDS_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdRequest(alice.getId(), "50.00", "HOLD-CAP-THEN-REL")))
                .andExpect(status().isCreated())
                .andReturn();
            String holdId = objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();

            mockMvc.perform(post(HOLDS_ENDPOINT + "/{id}/capture", holdId))
                .andExpect(status().isOk());

            mockMvc.perform(post(HOLDS_ENDPOINT + "/{id}/release", holdId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("HOLD_NOT_RELEASABLE"));
        }

        @Test
        @DisplayName("unknown holdId returns 404 HOLD_NOT_FOUND")
        void release_unknownHold_returns404() throws Exception {
            mockMvc.perform(post(HOLDS_ENDPOINT + "/{id}/release", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("HOLD_NOT_FOUND"));
        }
    }

    // =========================================================================
    // POST /api/v1/holds/{id}/capture
    // =========================================================================

    @Nested
    @DisplayName("POST /api/v1/holds/{id}/capture")
    class CaptureHold {

        @Test
        @DisplayName("captures ACTIVE hold — status becomes CAPTURED, ledger tx posted")
        void capture_activeHold_returnsCaptured() throws Exception {
            Account system = accountRepository.save(Account.of("System-CAP", AccountType.SYSTEM));
            postTransaction(system.getId(), alice.getId(), new BigDecimal("100.00"), "REF-FUND-CAP");

            MvcResult r = mockMvc.perform(post(HOLDS_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdRequest(alice.getId(), "60.00", "HOLD-CAPTURE-001")))
                .andExpect(status().isCreated())
                .andReturn();
            String holdId = objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();

            mockMvc.perform(post(HOLDS_ENDPOINT + "/{id}/capture", holdId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.capturedAt").isNotEmpty());

            // Verify ledger transaction was created
            assertThat(ledgerTransactionRepository.count()).isEqualTo(2L); // fund tx + capture tx
        }

        @Test
        @DisplayName("capture reduces alice currentBalance by hold amount")
        void capture_moveFunds_currentBalanceDecreases() throws Exception {
            Account system = accountRepository.save(Account.of("System-MOV", AccountType.SYSTEM));
            postTransaction(system.getId(), alice.getId(), new BigDecimal("100.00"), "REF-FUND-MOV");

            MvcResult r = mockMvc.perform(post(HOLDS_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdRequest(alice.getId(), "40.00", "HOLD-MOV-001")))
                .andExpect(status().isCreated())
                .andReturn();
            String holdId = objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();

            mockMvc.perform(post(HOLDS_ENDPOINT + "/{id}/capture", holdId))
                .andExpect(status().isOk());

            // currentBalance should be 60 (100 funded − 40 moved to clearing)
            mockMvc.perform(get(ACCOUNTS_ENDPOINT + "/{id}/balance", alice.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBalance").value(60.00))
                .andExpect(jsonPath("$.heldBalance").value(0.00))
                .andExpect(jsonPath("$.availableBalance").value(60.00));
        }

        @Test
        @DisplayName("capturing an already-captured hold returns 422 HOLD_NOT_CAPTURABLE")
        void capture_alreadyCaptured_returns422() throws Exception {
            Account system = accountRepository.save(Account.of("System-CAP2", AccountType.SYSTEM));
            postTransaction(system.getId(), alice.getId(), new BigDecimal("100.00"), "REF-FUND-CAP2");

            MvcResult r = mockMvc.perform(post(HOLDS_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdRequest(alice.getId(), "50.00", "HOLD-DOUBLE-CAP")))
                .andExpect(status().isCreated())
                .andReturn();
            String holdId = objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();

            mockMvc.perform(post(HOLDS_ENDPOINT + "/{id}/capture", holdId))
                .andExpect(status().isOk());

            mockMvc.perform(post(HOLDS_ENDPOINT + "/{id}/capture", holdId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("HOLD_NOT_CAPTURABLE"));
        }

        @Test
        @DisplayName("capturing a RELEASED hold returns 422 HOLD_NOT_CAPTURABLE")
        void capture_releasedHold_returns422() throws Exception {
            MvcResult r = mockMvc.perform(post(HOLDS_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdRequest(alice.getId(), "50.00", "HOLD-REL-THEN-CAP")))
                .andExpect(status().isCreated())
                .andReturn();
            String holdId = objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();

            mockMvc.perform(post(HOLDS_ENDPOINT + "/{id}/release", holdId))
                .andExpect(status().isOk());

            mockMvc.perform(post(HOLDS_ENDPOINT + "/{id}/capture", holdId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("HOLD_NOT_CAPTURABLE"));
        }

        @Test
        @DisplayName("unknown holdId returns 404 HOLD_NOT_FOUND")
        void capture_unknownHold_returns404() throws Exception {
            mockMvc.perform(post(HOLDS_ENDPOINT + "/{id}/capture", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("HOLD_NOT_FOUND"));
        }
    }

    // =========================================================================
    // Concurrent capture — only one thread should succeed
    // =========================================================================

    @Nested
    @DisplayName("Concurrent capture")
    class ConcurrentCapture {

        @Test
        @DisplayName("only one concurrent capture succeeds; the other gets 409 or 422")
        void concurrentCapture_onlyOneSucceeds() throws Exception {
            Account system = accountRepository.save(Account.of("System-CONC", AccountType.SYSTEM));
            postTransaction(system.getId(), alice.getId(), new BigDecimal("200.00"), "REF-FUND-CONC");

            MvcResult r = mockMvc.perform(post(HOLDS_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdRequest(alice.getId(), "100.00", "HOLD-CONC-001")))
                .andExpect(status().isCreated())
                .andReturn();
            String holdId = objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();

            int threads = 4;
            CountDownLatch ready  = new CountDownLatch(threads);
            CountDownLatch start  = new CountDownLatch(1);
            AtomicInteger successes = new AtomicInteger(0);
            AtomicInteger failures  = new AtomicInteger(0);
            List<Integer> statuses  = new ArrayList<>();

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        ready.countDown();
                        start.await(5, TimeUnit.SECONDS);
                        MvcResult res = mockMvc.perform(
                            post(HOLDS_ENDPOINT + "/{id}/capture", holdId)).andReturn();
                        int status = res.getResponse().getStatus();
                        synchronized (statuses) { statuses.add(status); }
                        if (status == 200) successes.incrementAndGet();
                        else               failures.incrementAndGet();
                    } catch (Exception ex) {
                        failures.incrementAndGet();
                    }
                });
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);

            assertThat(successes.get()).isEqualTo(1);
            assertThat(failures.get()).isEqualTo(threads - 1);
        }
    }

    // =========================================================================
    // Outbox events
    // =========================================================================

    @Nested
    @DisplayName("Outbox events")
    class OutboxEvents {

        @Test
        @DisplayName("creating a hold writes exactly one HOLD_CREATED outbox event")
        void create_writesHoldCreatedOutboxEvent() throws Exception {
            long before = outboxEventRepository.count();

            mockMvc.perform(post(HOLDS_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdRequest(alice.getId(), "10.00", "HOLD-OBX-CREATE")))
                .andExpect(status().isCreated());

            long after = outboxEventRepository.count();
            assertThat(after - before).isEqualTo(1);

            var events = outboxEventRepository.findAll();
            boolean hasHoldCreated = events.stream()
                .anyMatch(e -> "HOLD_CREATED".equals(e.getEventType()));
            assertThat(hasHoldCreated).isTrue();
        }

        @Test
        @DisplayName("releasing a hold writes one HOLD_RELEASED outbox event")
        void release_writesHoldReleasedOutboxEvent() throws Exception {
            MvcResult r = mockMvc.perform(post(HOLDS_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdRequest(alice.getId(), "15.00", "HOLD-OBX-REL")))
                .andExpect(status().isCreated())
                .andReturn();
            String holdId = objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();

            outboxEventRepository.deleteAllInBatch();

            mockMvc.perform(post(HOLDS_ENDPOINT + "/{id}/release", holdId))
                .andExpect(status().isOk());

            var events = outboxEventRepository.findAll();
            assertThat(events).hasSize(1);
            assertThat(events.get(0).getEventType()).isEqualTo("HOLD_RELEASED");
        }

        @Test
        @DisplayName("capturing a hold writes HOLD_CAPTURED and TRANSACTION_POSTED outbox events")
        void capture_writesHoldCapturedAndTransactionPostedEvents() throws Exception {
            Account system = accountRepository.save(Account.of("System-OBX", AccountType.SYSTEM));
            postTransaction(system.getId(), alice.getId(), new BigDecimal("100.00"), "REF-FUND-OBX");

            MvcResult r = mockMvc.perform(post(HOLDS_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdRequest(alice.getId(), "20.00", "HOLD-OBX-CAP")))
                .andExpect(status().isCreated())
                .andReturn();
            String holdId = objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();

            outboxEventRepository.deleteAllInBatch();

            mockMvc.perform(post(HOLDS_ENDPOINT + "/{id}/capture", holdId))
                .andExpect(status().isOk());

            var events = outboxEventRepository.findAll();
            assertThat(events).hasSize(2);

            var types = events.stream().map(e -> e.getEventType()).toList();
            assertThat(types).containsExactlyInAnyOrder("HOLD_CAPTURED", "TRANSACTION_POSTED");
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String holdRequest(UUID accountId, String amount, String referenceId) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
            "accountId",   accountId.toString(),
            "amount",      amount,
            "referenceId", referenceId
        ));
    }

    private void postTransaction(UUID debitId, UUID creditId, BigDecimal amount, String referenceId)
            throws Exception {
        mockMvc.perform(post("/api/v1/ledger-transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "debitAccountId",  debitId.toString(),
                    "creditAccountId", creditId.toString(),
                    "amount",          amount.toPlainString(),
                    "referenceId",     referenceId
                ))))
            .andExpect(status().isCreated());
    }
}
