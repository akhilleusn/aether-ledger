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
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full-stack integration tests for the ledger-transactions API.
 *
 * <p>Runs against a real PostgreSQL instance managed by Testcontainers.
 * Flyway applies the production migration scripts before the first test,
 * and each test method starts with a clean slate by wiping all tables in
 * foreign-key-safe order inside {@code setUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("LedgerTransactionController")
class LedgerTransactionControllerTest extends AbstractIntegrationTest {

    private static final String ENDPOINT = "/api/v1/ledger-transactions";

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
        // Delete in FK-safe order: entries → holds → transactions → accounts
        ledgerEntryRepository.deleteAllInBatch();
        holdRepository.deleteAllInBatch();
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
    // Idempotency safety
    // =========================================================================

    @Nested
    @DisplayName("Idempotency safety")
    class IdempotencySafety {

        @Test
        @DisplayName("second request with identical payload is rejected with 409 DUPLICATE_REFERENCE_ID")
        void post_sameReferenceIdTwice_secondIsRejected() throws Exception {
            var body = validRequest("REF-IDEM-SAFETY-001");

            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(body)))
                .andExpect(status().isCreated());

            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(body)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_REFERENCE_ID"))
                .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        @DisplayName("duplicate referenceId leaves exactly 2 ledger entries in the database")
        void post_sameReferenceIdTwice_databaseHasExactlyTwoEntries() throws Exception {
            var body = validRequest("REF-IDEM-SAFETY-002");

            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(body)))
                .andExpect(status().isCreated());

            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(body)))
                .andExpect(status().isConflict());

            assertThat(ledgerEntryRepository.count())
                .as("exactly 2 entries from the first (successful) request — the duplicate must not persist")
                .isEqualTo(2);
        }

        @Test
        @DisplayName("duplicate referenceId leaves exactly 1 transaction in the database")
        void post_sameReferenceIdTwice_databaseHasExactlyOneTransaction() throws Exception {
            var body = validRequest("REF-IDEM-SAFETY-003");

            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(body)))
                .andExpect(status().isCreated());

            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(body)))
                .andExpect(status().isConflict());

            assertThat(ledgerTransactionRepository.count())
                .as("exactly 1 transaction — the duplicate must not create a new record")
                .isEqualTo(1);
        }
    }

    // =========================================================================
    // 405 Method Not Allowed
    // =========================================================================

    @Nested
    @DisplayName("405 Method Not Allowed")
    class MethodNotAllowed {

        @Test
        @DisplayName("DELETE on endpoint returns 405 with METHOD_NOT_ALLOWED error code")
        void delete_onEndpoint_returns405() throws Exception {
            mockMvc.perform(delete(ENDPOINT))
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
    // GET /api/v1/ledger-transactions/{id}
    // =========================================================================

    @Nested
    @DisplayName("GET /api/v1/ledger-transactions/{id}")
    class GetById {

        @Test
        @DisplayName("returns 200 with transaction detail and exactly 2 entries")
        void getById_existingTransaction_returns200WithTwoEntries() throws Exception {
            var body = validRequest("REF-GET-BY-ID-001");
            var postResult = mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(body)))
                .andExpect(status().isCreated())
                .andReturn();

            String transactionId = objectMapper.readTree(postResult.getResponse().getContentAsString())
                .get("transactionId").asText();

            mockMvc.perform(get(ENDPOINT + "/{id}", transactionId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transactionId").value(transactionId))
                .andExpect(jsonPath("$.referenceId").value("REF-GET-BY-ID-001"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.entries", hasSize(2)))
                .andExpect(jsonPath("$.entries[0].entryId").isNotEmpty())
                .andExpect(jsonPath("$.entries[0].accountId").isNotEmpty())
                .andExpect(jsonPath("$.entries[0].accountName").isNotEmpty())
                .andExpect(jsonPath("$.entries[0].amount").isNotEmpty())
                .andExpect(jsonPath("$.entries[0].createdAt").isNotEmpty());
        }

        @Test
        @DisplayName("entries carry the correct account names")
        void getById_entries_carryCorrectAccountNames() throws Exception {
            var body = validRequest("REF-GET-BY-ID-NAMES");
            var postResult = mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(body)))
                .andExpect(status().isCreated())
                .andReturn();

            String transactionId = objectMapper.readTree(postResult.getResponse().getContentAsString())
                .get("transactionId").asText();

            mockMvc.perform(get(ENDPOINT + "/{id}", transactionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[*].accountName",
                    containsInAnyOrder(debitAccount.getName(), creditAccount.getName())));
        }

        @Test
        @DisplayName("unknown id returns 404 with TRANSACTION_NOT_FOUND")
        void getById_unknownId_returns404() throws Exception {
            mockMvc.perform(get(ENDPOINT + "/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TRANSACTION_NOT_FOUND"));
        }

        @Test
        @DisplayName("non-UUID id returns 400 with INVALID_PATH_VARIABLE")
        void getById_invalidUuid_returns400() throws Exception {
            mockMvc.perform(get(ENDPOINT + "/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PATH_VARIABLE"))
                .andExpect(jsonPath("$.message").value("Request path contains an invalid value."));
        }
    }

    // =========================================================================
    // GET /api/v1/ledger-transactions/by-reference/{referenceId}
    // =========================================================================

    @Nested
    @DisplayName("GET /api/v1/ledger-transactions/by-reference/{referenceId}")
    class GetByReferenceId {

        @Test
        @DisplayName("returns 200 with same shape as get-by-id")
        void getByReferenceId_existing_returns200() throws Exception {
            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(validRequest("REF-BY-REF-001"))))
                .andExpect(status().isCreated());

            mockMvc.perform(get(ENDPOINT + "/by-reference/REF-BY-REF-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceId").value("REF-BY-REF-001"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.entries", hasSize(2)));
        }

        @Test
        @DisplayName("unknown referenceId returns 404 with TRANSACTION_NOT_FOUND")
        void getByReferenceId_unknown_returns404() throws Exception {
            mockMvc.perform(get(ENDPOINT + "/by-reference/no-such-ref"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TRANSACTION_NOT_FOUND"));
        }
    }

    // =========================================================================
    // GET /api/v1/ledger-transactions  (list with pagination)
    // =========================================================================

    @Nested
    @DisplayName("GET /api/v1/ledger-transactions (list)")
    class ListTransactions {

        @Test
        @DisplayName("returns empty page when no transactions exist")
        void list_noTransactions_returnsEmptyPage() throws Exception {
            mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
        }

        @Test
        @DisplayName("returns transactions ordered newest first")
        void list_multipleTransactions_newestFirst() throws Exception {
            mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(validRequest("REF-LIST-FIRST")))).andExpect(status().isCreated());
            mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(validRequest("REF-LIST-SECOND")))).andExpect(status().isCreated());
            mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(validRequest("REF-LIST-THIRD")))).andExpect(status().isCreated());

            mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)))
                // newest referenceId was posted last
                .andExpect(jsonPath("$.content[0].referenceId").value("REF-LIST-THIRD"))
                .andExpect(jsonPath("$.content[1].referenceId").value("REF-LIST-SECOND"))
                .andExpect(jsonPath("$.content[2].referenceId").value("REF-LIST-FIRST"));
        }

        @Test
        @DisplayName("list response carries expected summary fields (no entries)")
        void list_responseShape_hasNoEntries() throws Exception {
            mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(validRequest("REF-LIST-SHAPE")))).andExpect(status().isCreated());

            mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].transactionId").isNotEmpty())
                .andExpect(jsonPath("$.content[0].referenceId").value("REF-LIST-SHAPE"))
                .andExpect(jsonPath("$.content[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.content[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.content[0].entries").doesNotExist());
        }

        @Test
        @DisplayName("pagination: page=0 size=2 returns first 2 of 3 transactions")
        void list_pagination_firstPage() throws Exception {
            mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(validRequest("REF-PAGE-A")))).andExpect(status().isCreated());
            mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(validRequest("REF-PAGE-B")))).andExpect(status().isCreated());
            mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(validRequest("REF-PAGE-C")))).andExpect(status().isCreated());

            mockMvc.perform(get(ENDPOINT).param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(2)))
                .andExpect(jsonPath("$.totalElements", is(3)))
                .andExpect(jsonPath("$.totalPages", is(2)));
        }

        @Test
        @DisplayName("pagination: page=1 size=2 returns the remaining transaction")
        void list_pagination_secondPage() throws Exception {
            mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(validRequest("REF-PAGE2-A")))).andExpect(status().isCreated());
            mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(validRequest("REF-PAGE2-B")))).andExpect(status().isCreated());
            mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(validRequest("REF-PAGE2-C")))).andExpect(status().isCreated());

            mockMvc.perform(get(ENDPOINT).param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.page", is(1)));
        }

        @Test
        @DisplayName("size exceeding 100 returns 400 VALIDATION_FAILED")
        void list_sizeOverMax_returns400() throws Exception {
            mockMvc.perform(get(ENDPOINT).param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("negative page returns 400 VALIDATION_FAILED")
        void list_negativePage_returns400() throws Exception {
            mockMvc.perform(get(ENDPOINT).param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }
    }

    // =========================================================================
    // POST /api/v1/ledger-transactions/{id}/reconcile
    // GET  /api/v1/ledger-transactions/reconciliation/issues
    // =========================================================================

    @Nested
    @DisplayName("Reconciliation")
    class ReconciliationTests {

        private static final String RECONCILE_PATH  = ENDPOINT + "/{id}/reconcile";
        private static final String ISSUES_PATH      = ENDPOINT + "/reconciliation/issues";

        @Test
        @DisplayName("reconcile SUCCESS tx with external SUCCESS → MATCHED")
        void reconcile_successWithExternalSuccess_returnsMatched() throws Exception {
            String txId = postTransaction("REF-RECON-001");

            mockMvc.perform(post(RECONCILE_PATH, txId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of(
                        "externalReferenceId", "EXT-001",
                        "externalStatus",      "SUCCESS"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reconciliationResult").value("MATCHED"))
                .andExpect(jsonPath("$.externalReferenceId").value("EXT-001"))
                .andExpect(jsonPath("$.externalStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.reconciledAt").isNotEmpty());
        }

        @Test
        @DisplayName("reconcile FAILED tx with external FAILED → MATCHED")
        void reconcile_failedWithExternalFailed_returnsMatched() throws Exception {
            String pendingId = createPendingTransaction("REF-RECON-002");
            mockMvc.perform(post(ENDPOINT + "/{id}/fail", pendingId)).andExpect(status().isOk());

            mockMvc.perform(post(RECONCILE_PATH, pendingId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of(
                        "externalReferenceId", "EXT-002",
                        "externalStatus",      "FAILED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reconciliationResult").value("MATCHED"));
        }

        @Test
        @DisplayName("reconcile SUCCESS tx with external FAILED → STATUS_MISMATCH")
        void reconcile_successWithExternalFailed_returnsMismatch() throws Exception {
            String txId = postTransaction("REF-RECON-003");

            mockMvc.perform(post(RECONCILE_PATH, txId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of(
                        "externalReferenceId", "EXT-003",
                        "externalStatus",      "FAILED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reconciliationResult").value("STATUS_MISMATCH"));
        }

        @Test
        @DisplayName("reconcile without external reference → MISSING_EXTERNAL_REFERENCE")
        void reconcile_withoutExternalReference_returnsMissingReference() throws Exception {
            String txId = postTransaction("REF-RECON-004");

            // externalReferenceId deliberately omitted
            mockMvc.perform(post(RECONCILE_PATH, txId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of("externalStatus", "SUCCESS"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reconciliationResult").value("MISSING_EXTERNAL_REFERENCE"))
                .andExpect(jsonPath("$.externalReferenceId").doesNotExist());
        }

        @Test
        @DisplayName("unreconciled transaction detail shows NOT_RECONCILED")
        void getById_unreconciledTransaction_showsNotReconciled() throws Exception {
            String txId = postTransaction("REF-RECON-005");

            mockMvc.perform(get(ENDPOINT + "/{id}", txId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reconciliationResult").value("NOT_RECONCILED"))
                .andExpect(jsonPath("$.externalReferenceId").doesNotExist())
                .andExpect(jsonPath("$.externalStatus").doesNotExist())
                .andExpect(jsonPath("$.reconciledAt").doesNotExist());
        }

        @Test
        @DisplayName("reconciliation issues endpoint excludes MATCHED transactions")
        void listIssues_excludesMatchedTransactions() throws Exception {
            String txId1 = postTransaction("REF-RECON-006a"); // will be reconciled → MATCHED
            String txId2 = postTransaction("REF-RECON-006b"); // left unreconciled

            mockMvc.perform(post(RECONCILE_PATH, txId1)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of(
                        "externalReferenceId", "EXT-006",
                        "externalStatus",      "SUCCESS"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reconciliationResult").value("MATCHED"));

            mockMvc.perform(get(ISSUES_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].transactionId", containsInAnyOrder(txId2)))
                .andExpect(jsonPath("$[*].transactionId",
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(txId1))));
        }

        @Test
        @DisplayName("reconciliation issues endpoint includes STATUS_MISMATCH transactions")
        void listIssues_includesMismatchedTransactions() throws Exception {
            String txId = postTransaction("REF-RECON-007");

            mockMvc.perform(post(RECONCILE_PATH, txId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of(
                        "externalReferenceId", "EXT-007",
                        "externalStatus",      "FAILED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reconciliationResult").value("STATUS_MISMATCH"));

            mockMvc.perform(get(ISSUES_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].transactionId", org.hamcrest.Matchers.hasItem(txId)))
                .andExpect(jsonPath("$[0].reconciliationResult").value("STATUS_MISMATCH"));
        }

        @Test
        @DisplayName("transaction detail carries all reconciliation fields after reconcile")
        void reconcile_detailResponseContainsAllReconciliationFields() throws Exception {
            String txId = postTransaction("REF-RECON-008");

            mockMvc.perform(post(RECONCILE_PATH, txId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of(
                        "externalReferenceId", "EXT-008",
                        "externalStatus",      "SUCCESS"))))
                .andExpect(status().isOk());

            mockMvc.perform(get(ENDPOINT + "/{id}", txId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalReferenceId").value("EXT-008"))
                .andExpect(jsonPath("$.externalStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.reconciledAt").isNotEmpty())
                .andExpect(jsonPath("$.reconciliationResult").value("MATCHED"));
        }

        @Test
        @DisplayName("reconciling non-existent transaction returns 404 TRANSACTION_NOT_FOUND")
        void reconcile_nonExistentTransaction_returns404() throws Exception {
            mockMvc.perform(post(RECONCILE_PATH, UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of(
                        "externalReferenceId", "EXT-NF",
                        "externalStatus",      "SUCCESS"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TRANSACTION_NOT_FOUND"));
        }
    }

    // =========================================================================
    // POST /api/v1/ledger-transactions/pending
    // =========================================================================

    @Nested
    @DisplayName("POST /api/v1/ledger-transactions/pending")
    class CreatePending {

        @Test
        @DisplayName("valid request returns 201 with PENDING status")
        void createPending_validRequest_returns201WithPendingStatus() throws Exception {
            mockMvc.perform(post(ENDPOINT + "/pending")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(validRequest("REF-PENDING-001"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").isNotEmpty())
                .andExpect(jsonPath("$.referenceId").value("REF-PENDING-001"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
        }

        @Test
        @DisplayName("pending transaction creates zero ledger entries")
        void createPending_validRequest_hasZeroLedgerEntries() throws Exception {
            mockMvc.perform(post(ENDPOINT + "/pending")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(validRequest("REF-PENDING-002"))))
                .andExpect(status().isCreated());

            assertThat(ledgerEntryRepository.count())
                .as("PENDING transaction must not create any ledger entries")
                .isZero();
        }

        @Test
        @DisplayName("non-existent debit account returns 404 ACCOUNT_NOT_FOUND")
        void createPending_nonExistentDebitAccount_returns404() throws Exception {
            mockMvc.perform(post(ENDPOINT + "/pending")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(requestWith(UUID.randomUUID(), creditAccount.getId(),
                        new BigDecimal("100.00"), "REF-PENDING-003"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_FOUND"));
        }

        @Test
        @DisplayName("duplicate referenceId returns 409 DUPLICATE_REFERENCE_ID")
        void createPending_duplicateReferenceId_returns409() throws Exception {
            mockMvc.perform(post(ENDPOINT + "/pending")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(validRequest("REF-PENDING-DUPE"))))
                .andExpect(status().isCreated());

            mockMvc.perform(post(ENDPOINT + "/pending")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(validRequest("REF-PENDING-DUPE"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_REFERENCE_ID"));
        }

        @Test
        @DisplayName("blank referenceId returns 400 VALIDATION_FAILED")
        void createPending_blankReferenceId_returns400() throws Exception {
            mockMvc.perform(post(ENDPOINT + "/pending")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(requestWith(debitAccount.getId(), creditAccount.getId(),
                        new BigDecimal("100.00"), "   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }
    }

    // =========================================================================
    // POST /api/v1/ledger-transactions/{id}/complete
    // =========================================================================

    @Nested
    @DisplayName("POST /api/v1/ledger-transactions/{id}/complete")
    class CompletePendingTransaction {

        @Test
        @DisplayName("completing pending transaction returns 200 with SUCCESS status and 2 entries")
        void complete_pendingTransaction_returnsSuccessWithTwoEntries() throws Exception {
            String pendingId = createPendingTransaction("REF-COMPLETE-001");

            mockMvc.perform(post(ENDPOINT + "/{id}/complete", pendingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(pendingId))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.entries", hasSize(2)));

            assertThat(ledgerEntryRepository.count())
                .as("completion must create exactly 2 ledger entries")
                .isEqualTo(2);
        }

        @Test
        @DisplayName("account balances are zero before completion and non-zero after")
        void complete_balancesUpdateOnlyAfterCompletion() throws Exception {
            String pendingId = createPendingTransaction("REF-COMPLETE-002");

            // Before completion — balances must be unaffected
            mockMvc.perform(get("/api/v1/accounts/{id}", debitAccount.getId()))
                .andExpect(jsonPath("$.currentBalance").value("0"));
            mockMvc.perform(get("/api/v1/accounts/{id}", creditAccount.getId()))
                .andExpect(jsonPath("$.currentBalance").value("0"));

            mockMvc.perform(post(ENDPOINT + "/{id}/complete", pendingId))
                .andExpect(status().isOk());

            // After completion — debit is negative, credit is positive
            mockMvc.perform(get("/api/v1/accounts/{id}", debitAccount.getId()))
                .andExpect(jsonPath("$.currentBalance").value(-250.0));
            mockMvc.perform(get("/api/v1/accounts/{id}", creditAccount.getId()))
                .andExpect(jsonPath("$.currentBalance").value(250.0));
        }

        @Test
        @DisplayName("completing a SUCCESS transaction returns 422 TRANSACTION_NOT_COMPLETABLE")
        void complete_successTransaction_returns422() throws Exception {
            String txId = postTransaction("REF-COMPLETE-003");

            mockMvc.perform(post(ENDPOINT + "/{id}/complete", txId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("TRANSACTION_NOT_COMPLETABLE"));
        }

        @Test
        @DisplayName("completing a FAILED transaction returns 422 TRANSACTION_NOT_COMPLETABLE")
        void complete_failedTransaction_returns422() throws Exception {
            String pendingId = createPendingTransaction("REF-COMPLETE-004");
            mockMvc.perform(post(ENDPOINT + "/{id}/fail", pendingId)).andExpect(status().isOk());

            mockMvc.perform(post(ENDPOINT + "/{id}/complete", pendingId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("TRANSACTION_NOT_COMPLETABLE"));
        }

        @Test
        @DisplayName("completing non-existent transaction returns 404 TRANSACTION_NOT_FOUND")
        void complete_nonExistentTransaction_returns404() throws Exception {
            mockMvc.perform(post(ENDPOINT + "/{id}/complete", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TRANSACTION_NOT_FOUND"));
        }

        @Test
        @DisplayName("completed transaction can be reversed")
        void complete_completedTransactionCanBeReversed() throws Exception {
            String pendingId = createPendingTransaction("REF-COMPLETE-005");
            mockMvc.perform(post(ENDPOINT + "/{id}/complete", pendingId)).andExpect(status().isOk());

            mockMvc.perform(post(ENDPOINT + "/{id}/reversal", pendingId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of("referenceId", "REF-COMPLETE-005-REV"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reversalOfTransactionId").value(pendingId));
        }
    }

    // =========================================================================
    // POST /api/v1/ledger-transactions/{id}/fail
    // =========================================================================

    @Nested
    @DisplayName("POST /api/v1/ledger-transactions/{id}/fail")
    class FailPendingTransaction {

        @Test
        @DisplayName("failing pending transaction returns 200 with FAILED status and no entries")
        void fail_pendingTransaction_returnsFailedWithNoEntries() throws Exception {
            String pendingId = createPendingTransaction("REF-FAIL-001");

            mockMvc.perform(post(ENDPOINT + "/{id}/fail", pendingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(pendingId))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.entries").isEmpty());

            assertThat(ledgerEntryRepository.count())
                .as("failed transaction must not create any ledger entries")
                .isZero();
        }

        @Test
        @DisplayName("failing a SUCCESS transaction returns 422 TRANSACTION_NOT_FAILABLE")
        void fail_successTransaction_returns422() throws Exception {
            String txId = postTransaction("REF-FAIL-002");

            mockMvc.perform(post(ENDPOINT + "/{id}/fail", txId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("TRANSACTION_NOT_FAILABLE"));
        }

        @Test
        @DisplayName("failing an already-failed transaction returns 422 TRANSACTION_NOT_FAILABLE")
        void fail_alreadyFailedTransaction_returns422() throws Exception {
            String pendingId = createPendingTransaction("REF-FAIL-003");
            mockMvc.perform(post(ENDPOINT + "/{id}/fail", pendingId)).andExpect(status().isOk());

            mockMvc.perform(post(ENDPOINT + "/{id}/fail", pendingId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("TRANSACTION_NOT_FAILABLE"));
        }

        @Test
        @DisplayName("failing non-existent transaction returns 404 TRANSACTION_NOT_FOUND")
        void fail_nonExistentTransaction_returns404() throws Exception {
            mockMvc.perform(post(ENDPOINT + "/{id}/fail", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TRANSACTION_NOT_FOUND"));
        }

        @Test
        @DisplayName("failed transaction cannot be reversed — returns 422 TRANSACTION_NOT_REVERSIBLE")
        void fail_failedTransactionCannotBeReversed() throws Exception {
            String pendingId = createPendingTransaction("REF-FAIL-004");
            mockMvc.perform(post(ENDPOINT + "/{id}/fail", pendingId)).andExpect(status().isOk());

            mockMvc.perform(post(ENDPOINT + "/{id}/reversal", pendingId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of("referenceId", "REF-FAIL-004-REV"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("TRANSACTION_NOT_REVERSIBLE"));
        }
    }

    // =========================================================================
    // POST /api/v1/ledger-transactions/{id}/reversal
    // =========================================================================

    @Nested
    @DisplayName("POST /api/v1/ledger-transactions/{id}/reversal")
    class Reversal {

        @Test
        @DisplayName("valid reversal returns 201 with correct response body")
        void reverse_validRequest_returns201WithBody() throws Exception {
            String transactionId = postTransaction("REF-REVERSAL-ORIG-001");

            mockMvc.perform(post(ENDPOINT + "/{id}/reversal", transactionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of("referenceId", "REF-REVERSAL-NEW-001"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").isNotEmpty())
                .andExpect(jsonPath("$.referenceId").value("REF-REVERSAL-NEW-001"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.reversalOfTransactionId").value(transactionId))
                .andExpect(jsonPath("$.entries", hasSize(2)));
        }

        @Test
        @DisplayName("reversal creates 2 new mirror-image entries (4 entries total)")
        void reverse_validRequest_createsTwoAdditionalEntries() throws Exception {
            String transactionId = postTransaction("REF-REVERSAL-ORIG-002");

            mockMvc.perform(post(ENDPOINT + "/{id}/reversal", transactionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of("referenceId", "REF-REVERSAL-NEW-002"))))
                .andExpect(status().isCreated());

            assertThat(ledgerEntryRepository.count())
                .as("original 2 entries + 2 reversal entries = 4")
                .isEqualTo(4);
        }

        @Test
        @DisplayName("reversal entries are zero-sum (double-entry invariant holds)")
        void reverse_validRequest_reversalEntriesSumToZero() throws Exception {
            String transactionId = postTransaction("REF-REVERSAL-ORIG-003");

            var reversalResult = mockMvc.perform(post(ENDPOINT + "/{id}/reversal", transactionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of("referenceId", "REF-REVERSAL-NEW-003"))))
                .andExpect(status().isCreated())
                .andReturn();

            String reversalId = objectMapper.readTree(
                    reversalResult.getResponse().getContentAsString())
                .get("transactionId").asText();

            BigDecimal sum = ledgerEntryRepository
                .sumAmountByLedgerTransactionId(UUID.fromString(reversalId));
            assertThat(sum.compareTo(BigDecimal.ZERO))
                .as("reversal entries must sum to zero")
                .isZero();
        }

        @Test
        @DisplayName("original transaction is linked to reversal via reversedByTransactionId")
        void reverse_validRequest_originalIsLinkedToReversal() throws Exception {
            String transactionId = postTransaction("REF-REVERSAL-ORIG-004");

            var reversalResult = mockMvc.perform(post(ENDPOINT + "/{id}/reversal", transactionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of("referenceId", "REF-REVERSAL-NEW-004"))))
                .andExpect(status().isCreated())
                .andReturn();

            String reversalId = objectMapper.readTree(
                    reversalResult.getResponse().getContentAsString())
                .get("transactionId").asText();

            // Reload the original and confirm the linkage
            mockMvc.perform(get(ENDPOINT + "/{id}", transactionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reversedByTransactionId").value(reversalId));
        }

        @Test
        @DisplayName("reversing an already-reversed transaction returns 409 TRANSACTION_ALREADY_REVERSED")
        void reverse_alreadyReversed_returns409() throws Exception {
            String transactionId = postTransaction("REF-REVERSAL-ORIG-005");

            // First reversal — must succeed
            mockMvc.perform(post(ENDPOINT + "/{id}/reversal", transactionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of("referenceId", "REF-REVERSAL-NEW-005a"))))
                .andExpect(status().isCreated());

            // Second reversal of same original — must be rejected
            mockMvc.perform(post(ENDPOINT + "/{id}/reversal", transactionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of("referenceId", "REF-REVERSAL-NEW-005b"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TRANSACTION_ALREADY_REVERSED"));
        }

        @Test
        @DisplayName("reversing a non-existent transaction returns 404 TRANSACTION_NOT_FOUND")
        void reverse_nonExistentTransaction_returns404() throws Exception {
            mockMvc.perform(post(ENDPOINT + "/{id}/reversal", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of("referenceId", "REF-REVERSAL-404"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TRANSACTION_NOT_FOUND"));
        }

        @Test
        @DisplayName("blank referenceId in reversal request returns 400 VALIDATION_FAILED")
        void reverse_blankReferenceId_returns400() throws Exception {
            String transactionId = postTransaction("REF-REVERSAL-ORIG-007");

            mockMvc.perform(post(ENDPOINT + "/{id}/reversal", transactionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of("referenceId", "   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("duplicate reversal referenceId returns 409 DUPLICATE_REFERENCE_ID")
        void reverse_duplicateReversalReferenceId_returns409() throws Exception {
            String transactionId1 = postTransaction("REF-REVERSAL-ORIG-008a");
            String transactionId2 = postTransaction("REF-REVERSAL-ORIG-008b");

            // First reversal uses the referenceId
            mockMvc.perform(post(ENDPOINT + "/{id}/reversal", transactionId1)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of("referenceId", "REF-REVERSAL-SHARED"))))
                .andExpect(status().isCreated());

            // Second reversal on a different original but same referenceId — must be rejected
            mockMvc.perform(post(ENDPOINT + "/{id}/reversal", transactionId2)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of("referenceId", "REF-REVERSAL-SHARED"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_REFERENCE_ID"));
        }

    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Posts a SUCCESS transaction via POST /api/v1/ledger-transactions and returns its transactionId. */
    private String postTransaction(String referenceId) throws Exception {
        var result = mockMvc.perform(post(ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(validRequest(referenceId))))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
            .get("transactionId").asText();
    }

    /** Posts a PENDING transaction and returns its transactionId string. */
    private String createPendingTransaction(String referenceId) throws Exception {
        var result = mockMvc.perform(post(ENDPOINT + "/pending")
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(validRequest(referenceId))))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
            .get("transactionId").asText();
    }

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
