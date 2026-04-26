package com.aetherledger.api;

import com.aetherledger.domain.entity.Account;
import com.aetherledger.domain.enums.AccountType;
import com.aetherledger.repository.AccountRepository;
import com.aetherledger.repository.HoldRepository;
import com.aetherledger.repository.IdempotencyRecordRepository;
import com.aetherledger.repository.LedgerEntryRepository;
import com.aetherledger.repository.LedgerTransactionRepository;
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

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for HTTP-level idempotency on
 * {@code POST /api/v1/ledger-transactions}.
 *
 * <p>Tests exercise all four paths:
 * <ul>
 *   <li>New key → process normally, store record, return 201</li>
 *   <li>Same key + same body → cache hit, return original 201 without creating a new transaction</li>
 *   <li>Same key + different body → 409 IDEMPOTENCY_KEY_CONFLICT</li>
 *   <li>Missing key → proceed normally (backward compatibility)</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Idempotency — POST /api/v1/ledger-transactions")
class IdempotencyIntegrationTest extends AbstractIntegrationTest {

    private static final String TX_ENDPOINT      = "/api/v1/ledger-transactions";
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AccountRepository accountRepository;
    @Autowired HoldRepository holdRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired LedgerTransactionRepository ledgerTransactionRepository;
    @Autowired IdempotencyRecordRepository idempotencyRecordRepository;

    private Account alice;
    private Account bob;

    @BeforeEach
    void setUp() {
        idempotencyRecordRepository.deleteAllInBatch();
        ledgerEntryRepository.deleteAllInBatch();
        holdRepository.deleteAllInBatch();
        ledgerTransactionRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();

        alice = accountRepository.save(Account.of("Idempotency – Alice", AccountType.USER));
        bob   = accountRepository.save(Account.of("Idempotency – Bob",   AccountType.USER));
    }

    // =========================================================================
    // No Idempotency-Key header — backward compatibility
    // =========================================================================

    @Nested
    @DisplayName("Without Idempotency-Key header")
    class WithoutKey {

        @Test
        @DisplayName("returns 201 and creates transaction normally")
        void noKey_returns201() throws Exception {
            mockMvc.perform(post(TX_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(txBody("IDEM-NO-KEY-001", "50.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").isNotEmpty())
                .andExpect(jsonPath("$.referenceId").value("IDEM-NO-KEY-001"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));

            assertThat(idempotencyRecordRepository.count()).isZero();
        }

        @Test
        @DisplayName("blank Idempotency-Key header is treated as absent")
        void blankKey_treatedAsAbsent() throws Exception {
            mockMvc.perform(post(TX_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(IDEMPOTENCY_HEADER, "   ")
                    .content(txBody("IDEM-BLANK-KEY-001", "10.00")))
                .andExpect(status().isCreated());

            assertThat(idempotencyRecordRepository.count()).isZero();
        }
    }

    // =========================================================================
    // New Idempotency-Key — first request
    // =========================================================================

    @Nested
    @DisplayName("First request with a new Idempotency-Key")
    class FirstRequest {

        @Test
        @DisplayName("returns 201, creates transaction, and stores idempotency record")
        void newKey_returns201AndStoresRecord() throws Exception {
            String key = UUID.randomUUID().toString();

            MvcResult result = mockMvc.perform(post(TX_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(IDEMPOTENCY_HEADER, key)
                    .content(txBody("IDEM-FIRST-001", "75.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").isNotEmpty())
                .andExpect(jsonPath("$.referenceId").value("IDEM-FIRST-001"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andReturn();

            // Idempotency record must be stored
            assertThat(idempotencyRecordRepository.count()).isEqualTo(1);
            var record = idempotencyRecordRepository.findById(key);
            assertThat(record).isPresent();
            assertThat(record.get().getResponseStatus()).isEqualTo(201);
            assertThat(record.get().getResponseBody()).contains("IDEM-FIRST-001");

            // Exactly one transaction in DB
            assertThat(ledgerTransactionRepository.count()).isEqualTo(1);
        }
    }

    // =========================================================================
    // Same key + same body — idempotent replay
    // =========================================================================

    @Nested
    @DisplayName("Retry with same Idempotency-Key and same body")
    class SameKeyAndBody {

        @Test
        @DisplayName("returns original 201 response without creating a duplicate transaction")
        void sameKeySameBody_returnsCachedResponse() throws Exception {
            String key  = UUID.randomUUID().toString();
            String body = txBody("IDEM-RETRY-001", "100.00");

            // First request
            MvcResult first = mockMvc.perform(post(TX_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(IDEMPOTENCY_HEADER, key)
                    .content(body))
                .andExpect(status().isCreated())
                .andReturn();

            JsonNode firstJson = objectMapper.readTree(first.getResponse().getContentAsString());
            String originalTxId = firstJson.get("transactionId").asText();

            // Retry with same key + same body
            MvcResult second = mockMvc.perform(post(TX_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(IDEMPOTENCY_HEADER, key)
                    .content(body))
                .andExpect(status().isCreated())
                .andReturn();

            JsonNode secondJson = objectMapper.readTree(second.getResponse().getContentAsString());

            // Same transaction ID in both responses
            assertThat(secondJson.get("transactionId").asText()).isEqualTo(originalTxId);
            assertThat(secondJson.get("referenceId").asText()).isEqualTo("IDEM-RETRY-001");
            assertThat(secondJson.get("status").asText()).isEqualTo("SUCCESS");

            // Only one transaction was created
            assertThat(ledgerTransactionRepository.count()).isEqualTo(1);
            // Only one idempotency record
            assertThat(idempotencyRecordRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("third retry also returns the same cached response")
        void multipleRetries_allReturnSameResponse() throws Exception {
            String key  = UUID.randomUUID().toString();
            String body = txBody("IDEM-MULTI-001", "25.00");

            MvcResult first = mockMvc.perform(post(TX_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(IDEMPOTENCY_HEADER, key)
                    .content(body))
                .andExpect(status().isCreated())
                .andReturn();

            String originalTxId = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("transactionId").asText();

            for (int i = 0; i < 3; i++) {
                MvcResult retry = mockMvc.perform(post(TX_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IDEMPOTENCY_HEADER, key)
                        .content(body))
                    .andExpect(status().isCreated())
                    .andReturn();
                String txId = objectMapper.readTree(retry.getResponse().getContentAsString())
                    .get("transactionId").asText();
                assertThat(txId).isEqualTo(originalTxId);
            }

            assertThat(ledgerTransactionRepository.count()).isEqualTo(1);
        }
    }

    // =========================================================================
    // Same key + different body — conflict
    // =========================================================================

    @Nested
    @DisplayName("Retry with same Idempotency-Key but different request body")
    class SameKeyDifferentBody {

        @Test
        @DisplayName("returns 409 IDEMPOTENCY_KEY_CONFLICT with clear message")
        void sameKeyDifferentBody_returns409() throws Exception {
            String key = UUID.randomUUID().toString();

            // First request
            mockMvc.perform(post(TX_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(IDEMPOTENCY_HEADER, key)
                    .content(txBody("IDEM-CONFLICT-001", "50.00")))
                .andExpect(status().isCreated());

            // Second request — different amount (different body fingerprint)
            mockMvc.perform(post(TX_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(IDEMPOTENCY_HEADER, key)
                    .content(txBody("IDEM-CONFLICT-002", "99.99")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_CONFLICT"))
                .andExpect(jsonPath("$.message").value(
                    org.hamcrest.Matchers.containsString(key)));

            // Only one transaction in DB
            assertThat(ledgerTransactionRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("same key with same referenceId but different amount → 409")
        void sameKeyDifferentAmount_returns409() throws Exception {
            String key = UUID.randomUUID().toString();

            mockMvc.perform(post(TX_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(IDEMPOTENCY_HEADER, key)
                    .content(txBody("IDEM-CONFLICT-AMT", "100.00")))
                .andExpect(status().isCreated());

            mockMvc.perform(post(TX_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(IDEMPOTENCY_HEADER, key)
                    .content(txBody("IDEM-CONFLICT-AMT", "200.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_CONFLICT"));
        }
    }

    // =========================================================================
    // Different keys are independent
    // =========================================================================

    @Nested
    @DisplayName("Different Idempotency-Keys are independent")
    class DifferentKeys {

        @Test
        @DisplayName("two distinct keys create two distinct transactions")
        void differentKeys_createSeparateTransactions() throws Exception {
            mockMvc.perform(post(TX_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(IDEMPOTENCY_HEADER, "key-A-" + UUID.randomUUID())
                    .content(txBody("IDEM-DIFF-A", "10.00")))
                .andExpect(status().isCreated());

            mockMvc.perform(post(TX_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(IDEMPOTENCY_HEADER, "key-B-" + UUID.randomUUID())
                    .content(txBody("IDEM-DIFF-B", "20.00")))
                .andExpect(status().isCreated());

            assertThat(ledgerTransactionRepository.count()).isEqualTo(2);
            assertThat(idempotencyRecordRepository.count()).isEqualTo(2);
        }
    }

    // =========================================================================
    // Response shape
    // =========================================================================

    @Nested
    @DisplayName("Response fields")
    class ResponseShape {

        @Test
        @DisplayName("first request response has all expected fields")
        void firstRequest_responseHasAllFields() throws Exception {
            String key = UUID.randomUUID().toString();

            mockMvc.perform(post(TX_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(IDEMPOTENCY_HEADER, key)
                    .content(txBody("IDEM-SHAPE-001", "30.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").isNotEmpty())
                .andExpect(jsonPath("$.referenceId").value("IDEM-SHAPE-001"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
        }

        @Test
        @DisplayName("replayed response has identical fields to the original")
        void replayedResponse_hasIdenticalFields() throws Exception {
            String key  = UUID.randomUUID().toString();
            String body = txBody("IDEM-SHAPE-002", "45.00");

            MvcResult first = mockMvc.perform(post(TX_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(IDEMPOTENCY_HEADER, key)
                    .content(body))
                .andReturn();

            MvcResult replay = mockMvc.perform(post(TX_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(IDEMPOTENCY_HEADER, key)
                    .content(body))
                .andReturn();

            JsonNode f = objectMapper.readTree(first.getResponse().getContentAsString());
            JsonNode r = objectMapper.readTree(replay.getResponse().getContentAsString());

            assertThat(r.get("transactionId").asText()).isEqualTo(f.get("transactionId").asText());
            assertThat(r.get("referenceId").asText()).isEqualTo(f.get("referenceId").asText());
            assertThat(r.get("status").asText()).isEqualTo(f.get("status").asText());
            assertThat(r.get("createdAt").asText()).isEqualTo(f.get("createdAt").asText());
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String txBody(String referenceId, String amount) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("debitAccountId",  alice.getId());
        body.put("creditAccountId", bob.getId());
        body.put("amount",          new BigDecimal(amount));
        body.put("referenceId",     referenceId);
        return objectMapper.writeValueAsString(body);
    }
}
