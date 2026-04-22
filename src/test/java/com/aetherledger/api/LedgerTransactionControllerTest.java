package com.aetherledger.api;

import com.aetherledger.domain.entity.Account;
import com.aetherledger.domain.enums.AccountType;
import com.aetherledger.repository.AccountRepository;
import com.aetherledger.repository.LedgerEntryRepository;
import com.aetherledger.repository.LedgerTransactionRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full-stack integration tests for POST /api/v1/ledger-transactions.
 *
 * <p>Runs against an H2 in-memory database (profile "test") with the real
 * service, repository, and JPA layers.  Each test starts with a clean slate
 * by wiping all tables in foreign-key-safe order inside {@code setUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("LedgerTransactionController")
class LedgerTransactionControllerTest {

    private static final String ENDPOINT = "/api/v1/ledger-transactions";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AccountRepository accountRepository;
    @Autowired LedgerTransactionRepository ledgerTransactionRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;

    private Account debitAccount;
    private Account creditAccount;

    @BeforeEach
    void setUp() {
        // Delete in FK-safe order: entries → transactions → accounts
        ledgerEntryRepository.deleteAllInBatch();
        ledgerTransactionRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();

        debitAccount  = accountRepository.save(Account.of("Wallet – Alice", AccountType.USER));
        creditAccount = accountRepository.save(Account.of("Wallet – Bob",   AccountType.USER));
    }

    // =========================================================================
    // Success scenarios
    // =========================================================================

    @Nested
    @DisplayName("success scenarios")
    class SuccessScenarios {

        @Test
        @DisplayName("valid request returns 201 with correct response body")
        void post_validRequest_returns201WithBody() throws Exception {
            var body = validRequest("REF-HAPPY-PATH");

            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(body)))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transactionId").isNotEmpty())
                .andExpect(jsonPath("$.referenceId").value("REF-HAPPY-PATH"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
        }

        @Test
        @DisplayName("valid request persists exactly two ledger entries")
        void post_validRequest_persistsExactlyTwoEntries() throws Exception {
            var body = validRequest("REF-TWO-ENTRIES");

            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(body)))
                .andExpect(status().isCreated());

            assertThat(ledgerEntryRepository.count()).isEqualTo(2);
        }

        @Test
        @DisplayName("debit entry is negative and credit entry is positive")
        void post_validRequest_entrySignsAreCorrect() throws Exception {
            var body = validRequest("REF-ENTRY-SIGNS");

            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(body)))
                .andExpect(status().isCreated());

            var entries = ledgerEntryRepository.findAll();
            assertThat(entries).hasSize(2);

            long negativeCount = entries.stream()
                .filter(e -> e.getAmount().compareTo(BigDecimal.ZERO) < 0)
                .count();
            long positiveCount = entries.stream()
                .filter(e -> e.getAmount().compareTo(BigDecimal.ZERO) > 0)
                .count();

            assertThat(negativeCount).as("exactly one debit entry").isEqualTo(1);
            assertThat(positiveCount).as("exactly one credit entry").isEqualTo(1);
        }

        @Test
        @DisplayName("ledger entries sum to zero (double-entry invariant)")
        void post_validRequest_entriesSumToZero() throws Exception {
            var body = validRequest("REF-ZERO-SUM");

            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(body)))
                .andExpect(status().isCreated());

            var tx = ledgerTransactionRepository.findAll().get(0);
            BigDecimal sum = ledgerEntryRepository.sumAmountByLedgerTransactionId(tx.getId());

            assertThat(sum.compareTo(BigDecimal.ZERO))
                .as("entry amounts must sum to zero")
                .isZero();
        }

        @Test
        @DisplayName("transaction is persisted with status SUCCESS")
        void post_validRequest_transactionStatusIsSuccess() throws Exception {
            var body = validRequest("REF-STATUS-CHECK");

            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

            var transactions = ledgerTransactionRepository.findAll();
            assertThat(transactions).hasSize(1);
            assertThat(transactions.get(0).getStatus().name()).isEqualTo("SUCCESS");
        }
    }

    // =========================================================================
    // 400 Bad Request — validation and business rule failures
    // =========================================================================

    @Nested
    @DisplayName("400 Bad Request")
    class BadRequest {

        @Test
        @DisplayName("zero amount returns 400 with INVALID_REQUEST error code")
        void post_zeroAmount_returns400() throws Exception {
            var body = requestWith(
                debitAccount.getId(), creditAccount.getId(),
                BigDecimal.ZERO, "REF-ZERO-AMOUNT"
            );

            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("negative amount returns 400")
        void post_negativeAmount_returns400() throws Exception {
            var body = requestWith(
                debitAccount.getId(), creditAccount.getId(),
                new BigDecimal("-50.00"), "REF-NEG-AMOUNT"
            );

            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("missing amount field returns 400")
        void post_missingAmount_returns400() throws Exception {
            // Build JSON manually to omit 'amount' entirely
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("debitAccountId",  debitAccount.getId());
            body.put("creditAccountId", creditAccount.getId());
            body.put("referenceId",     "REF-NO-AMOUNT");

            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("blank referenceId returns 400")
        void post_blankReferenceId_returns400() throws Exception {
            var body = requestWith(
                debitAccount.getId(), creditAccount.getId(),
                new BigDecimal("100.00"), "   "
            );

            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("same debit and credit account returns 400 with INVALID_REQUEST error code")
        void post_sameDebitAndCreditAccount_returns400() throws Exception {
            var body = requestWith(
                debitAccount.getId(), debitAccount.getId(), // same account
                new BigDecimal("100.00"), "REF-SAME-ACCOUNT"
            );

            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        }

        @Test
        @DisplayName("missing debitAccountId returns 400")
        void post_missingDebitAccountId_returns400() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("creditAccountId", creditAccount.getId());
            body.put("amount",          new BigDecimal("100.00"));
            body.put("referenceId",     "REF-NO-DEBIT");

            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }
    }

    // =========================================================================
    // 404 Not Found — missing accounts
    // =========================================================================

    @Nested
    @DisplayName("404 Not Found")
    class NotFound {

        @Test
        @DisplayName("non-existent debit account returns 404 with ACCOUNT_NOT_FOUND error code")
        void post_nonExistentDebitAccount_returns404() throws Exception {
            var body = requestWith(
                UUID.randomUUID(),       // does not exist
                creditAccount.getId(),
                new BigDecimal("100.00"), "REF-NO-DEBIT-ACCT"
            );

            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(body)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_FOUND"));
        }

        @Test
        @DisplayName("non-existent credit account returns 404 with ACCOUNT_NOT_FOUND error code")
        void post_nonExistentCreditAccount_returns404() throws Exception {
            var body = requestWith(
                debitAccount.getId(),
                UUID.randomUUID(),       // does not exist
                new BigDecimal("100.00"), "REF-NO-CREDIT-ACCT"
            );

            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(body)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_FOUND"));
        }
    }

    // =========================================================================
    // 409 Conflict — idempotency
    // =========================================================================

    @Nested
    @DisplayName("409 Conflict")
    class Conflict {

        @Test
        @DisplayName("duplicate referenceId returns 409 with DUPLICATE_REFERENCE_ID error code")
        void post_duplicateReferenceId_returns409() throws Exception {
            var body = validRequest("REF-DUPLICATE");

            // First request — must succeed
            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(body)))
                .andExpect(status().isCreated());

            // Second request with same referenceId — must be rejected
            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(body)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_REFERENCE_ID"))
                .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        @DisplayName("duplicate referenceId does not create additional ledger entries")
        void post_duplicateReferenceId_doesNotCreateExtraEntries() throws Exception {
            var body = validRequest("REF-DUPE-NO-EXTRA-ENTRIES");

            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(body)))
                .andExpect(status().isCreated());

            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(body)))
                .andExpect(status().isConflict());

            // Exactly 2 entries from the first (successful) request — never 4
            assertThat(ledgerEntryRepository.count())
                .as("duplicate request must not persist additional entries")
                .isEqualTo(2);
        }
    }

    // =========================================================================
    // 405 Method Not Allowed
    // =========================================================================

    @Nested
    @DisplayName("405 Method Not Allowed")
    class MethodNotAllowed {

        @Test
        @DisplayName("GET on POST-only endpoint returns 405 with METHOD_NOT_ALLOWED error code")
        void get_onPostOnlyEndpoint_returns405() throws Exception {
            mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("Request method is not supported for this endpoint."))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.path").value(ENDPOINT));
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Map<String, Object> validRequest(String referenceId) {
        return requestWith(
            debitAccount.getId(),
            creditAccount.getId(),
            new BigDecimal("250.00"),
            referenceId
        );
    }

    private Map<String, Object> requestWith(
            UUID debitId, UUID creditId, BigDecimal amount, String referenceId) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("debitAccountId",  debitId);
        body.put("creditAccountId", creditId);
        body.put("amount",          amount);
        body.put("referenceId",     referenceId);
        return body;
    }

    private String toJson(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
