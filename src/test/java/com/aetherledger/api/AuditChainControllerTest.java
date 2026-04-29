package com.aetherledger.api;

import com.aetherledger.domain.entity.Account;
import com.aetherledger.domain.enums.AccountType;
import com.aetherledger.repository.AccountRepository;
import com.aetherledger.repository.HoldRepository;
import com.aetherledger.repository.LedgerAuditChainRepository;
import com.aetherledger.repository.LedgerEntryRepository;
import com.aetherledger.repository.LedgerTransactionRepository;
import com.aetherledger.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("AuditChainController")
class AuditChainControllerTest extends AbstractIntegrationTest {

    private static final String CHAIN_ENDPOINT  = "/api/v1/ops/ledger-integrity/chain";
    private static final String LATEST_ENDPOINT = "/api/v1/ops/ledger-integrity/chain/latest";
    private static final String VERIFY_ENDPOINT = "/api/v1/ops/ledger-integrity/chain/verify";
    private static final String EVENTS_ENDPOINT = "/api/v1/ops/ledger-integrity/chain/events";
    private static final String TX_ENDPOINT     = "/api/v1/ledger-transactions";
    private static final String HOLDS_ENDPOINT  = "/api/v1/holds";

    @Autowired WebApplicationContext         wac;
    @Autowired ObjectMapper                  objectMapper;
    MockMvc                                  mockMvc;
    @Autowired AccountRepository             accountRepository;
    @Autowired LedgerTransactionRepository   ledgerTransactionRepository;
    @Autowired LedgerEntryRepository         ledgerEntryRepository;
    @Autowired HoldRepository                holdRepository;
    @Autowired OutboxEventRepository         outboxEventRepository;
    @Autowired LedgerAuditChainRepository    auditChainRepository;
    @Autowired JdbcTemplate                  jdbcTemplate;

    private Account accountA;
    private Account accountB;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
            .defaultRequest(get("/").header(InternalApiKeyInterceptor.HEADER_NAME, "test-internal-api-key"))
            .build();

        resetDatabase();

        accountA = accountRepository.save(Account.of("Chain-A", AccountType.SYSTEM));
        accountB = accountRepository.save(Account.of("Chain-B", AccountType.SYSTEM));
    }

    // =========================================================================
    // GET /api/v1/ops/ledger-integrity/chain/latest
    // =========================================================================

    @Nested
    @DisplayName("GET /latest")
    class Latest {

        @Test
        @DisplayName("empty chain returns 404 AUDIT_CHAIN_ENTRY_NOT_FOUND")
        void emptyChain_returns404() throws Exception {
            mockMvc.perform(get(LATEST_ENDPOINT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("AUDIT_CHAIN_ENTRY_NOT_FOUND"));
        }

        @Test
        @DisplayName("after one transaction the latest entry reflects that event")
        void oneTransaction_latestHasCorrectEventType() throws Exception {
            postTransaction("REF-LATEST-001");

            mockMvc.perform(get(LATEST_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sequenceNumber").value(1))
                .andExpect(jsonPath("$.eventType").value("TRANSACTION_POSTED"))
                .andExpect(jsonPath("$.entityType").value("LEDGER_TRANSACTION"))
                .andExpect(jsonPath("$.currentHash").isNotEmpty())
                .andExpect(jsonPath("$.previousHash").doesNotExist())
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
        }

        @Test
        @DisplayName("latest always reflects the most recent event across multiple transactions")
        void multipleTransactions_latestIsNewest() throws Exception {
            postTransaction("REF-LATEST-A");
            postTransaction("REF-LATEST-B");
            postTransaction("REF-LATEST-C");

            mockMvc.perform(get(LATEST_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sequenceNumber").value(3));
        }
    }

    // =========================================================================
    // POST /api/v1/ops/ledger-integrity/chain/verify
    // =========================================================================

    @Nested
    @DisplayName("POST /verify")
    class Verify {

        @Test
        @DisplayName("empty chain is valid")
        void emptyChain_isValid() throws Exception {
            mockMvc.perform(post(VERIFY_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.checkedRecords").value(0))
                .andExpect(jsonPath("$.brokenAtSequenceNumber").doesNotExist())
                .andExpect(jsonPath("$.brokenAtId").doesNotExist())
                .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        @DisplayName("intact chain with one entry is valid")
        void oneEntry_isValid() throws Exception {
            postTransaction("REF-VERIFY-ONE");

            mockMvc.perform(post(VERIFY_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.checkedRecords").value(1))
                .andExpect(jsonPath("$.brokenAtSequenceNumber").doesNotExist());
        }

        @Test
        @DisplayName("intact chain across multiple events is valid")
        void multipleEvents_intactChainIsValid() throws Exception {
            postTransaction("REF-VERIFY-A");
            postTransaction("REF-VERIFY-B");
            String pendingId = createPendingTransaction("REF-VERIFY-PEND");
            mockMvc.perform(post(TX_ENDPOINT + "/{id}/complete", pendingId)).andExpect(status().isOk());

            mockMvc.perform(post(VERIFY_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.checkedRecords").value(greaterThanOrEqualTo(3)));
        }

        @Test
        @DisplayName("tampering an entry's eventType breaks the chain at that sequence number")
        void tamperedEntry_chainBroken() throws Exception {
            postTransaction("REF-TAMPER-A");
            postTransaction("REF-TAMPER-B");

            // Verify it's intact before tampering
            mockMvc.perform(post(VERIFY_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

            // Directly mutate the first entry's eventType to simulate tampering
            jdbcTemplate.update(
                "UPDATE ledger_audit_chain SET event_type = 'TAMPERED' WHERE sequence_number = 1");

            // Chain should now be broken at sequence 1
            mockMvc.perform(post(VERIFY_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.checkedRecords").value(2))
                .andExpect(jsonPath("$.brokenAtSequenceNumber").value(1))
                .andExpect(jsonPath("$.brokenAtId").isNotEmpty())
                .andExpect(jsonPath("$.message").value(containsString("sequence 1")));
        }

        @Test
        @DisplayName("tampering a middle entry is detected at that sequence number")
        void tamperedMiddleEntry_detectedAtCorrectSequence() throws Exception {
            postTransaction("REF-MID-A");
            postTransaction("REF-MID-B");
            postTransaction("REF-MID-C");

            // Tamper the second entry
            jdbcTemplate.update(
                "UPDATE ledger_audit_chain SET event_type = 'TAMPERED' WHERE sequence_number = 2");

            mockMvc.perform(post(VERIFY_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.brokenAtSequenceNumber").value(2));
        }
    }

    // =========================================================================
    // GET /api/v1/ops/ledger-integrity/chain/events/{entityId}
    // =========================================================================

    @Nested
    @DisplayName("GET /events/{entityId}")
    class EventsByEntityId {

        @Test
        @DisplayName("unknown entityId returns empty list")
        void unknownEntityId_returnsEmpty() throws Exception {
            mockMvc.perform(get(EVENTS_ENDPOINT + "/{entityId}", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("returns all chain events for a ledger transaction")
        void knownTransactionId_returnsEvents() throws Exception {
            String txId = postTransaction("REF-EVENTS-TX");

            mockMvc.perform(get(EVENTS_ENDPOINT + "/{entityId}", txId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].entityId").value(txId))
                .andExpect(jsonPath("$[0].eventType").value("TRANSACTION_POSTED"))
                .andExpect(jsonPath("$[0].payloadHash").isNotEmpty())
                .andExpect(jsonPath("$[0].currentHash").isNotEmpty());
        }

        @Test
        @DisplayName("pending→complete generates two entries for the same transaction ID")
        void pendingCompleted_twoEntriesForSameEntityId() throws Exception {
            String txId = createPendingTransaction("REF-EVENTS-PEND");

            mockMvc.perform(post(TX_ENDPOINT + "/{id}/complete", txId))
                .andExpect(status().isOk());

            // TRANSACTION_PENDING_CREATED + TRANSACTION_COMPLETED, newest first
            mockMvc.perform(get(EVENTS_ENDPOINT + "/{entityId}", txId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].eventType").value("TRANSACTION_COMPLETED"))
                .andExpect(jsonPath("$[1].eventType").value("TRANSACTION_PENDING_CREATED"));
        }

        @Test
        @DisplayName("events for different entities do not appear in each other's results")
        void differentEntities_noMixup() throws Exception {
            String txId1 = postTransaction("REF-SEP-A");
            String txId2 = postTransaction("REF-SEP-B");

            mockMvc.perform(get(EVENTS_ENDPOINT + "/{entityId}", txId1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].entityId").value(txId1));

            mockMvc.perform(get(EVENTS_ENDPOINT + "/{entityId}", txId2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].entityId").value(txId2));
        }
    }

    // =========================================================================
    // GET /api/v1/ops/ledger-integrity/chain  (paginated list)
    // =========================================================================

    @Nested
    @DisplayName("GET /  (paginated list)")
    class PaginatedList {

        @Test
        @DisplayName("empty chain returns empty content page")
        void emptyChain_emptyPage() throws Exception {
            mockMvc.perform(get(CHAIN_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0));
        }

        @Test
        @DisplayName("entries are returned newest-first")
        void entries_newestFirst() throws Exception {
            postTransaction("REF-PAGE-A");
            postTransaction("REF-PAGE-B");

            mockMvc.perform(get(CHAIN_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(greaterThanOrEqualTo(2))))
                .andExpect(jsonPath("$.content[0].sequenceNumber").value(2))
                .andExpect(jsonPath("$.content[1].sequenceNumber").value(1));
        }
    }

    // =========================================================================
    // Hold events appear in the chain
    // =========================================================================

    @Nested
    @DisplayName("Hold lifecycle events")
    class HoldLifecycle {

        @Test
        @DisplayName("creating a hold writes a HOLD_CREATED chain entry")
        void createHold_writesHoldCreatedEntry() throws Exception {
            MvcResult holdResult = mockMvc.perform(post(HOLDS_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdRequest(accountA.getId(), "50.00", "HOLD-CHAIN-001")))
                .andExpect(status().isCreated())
                .andReturn();

            String holdId = objectMapper.readTree(holdResult.getResponse().getContentAsString())
                .get("id").asText();

            mockMvc.perform(get(EVENTS_ENDPOINT + "/{entityId}", holdId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].eventType").value("HOLD_CREATED"))
                .andExpect(jsonPath("$[0].entityType").value("HOLD"));
        }

        @Test
        @DisplayName("releasing a hold writes a HOLD_RELEASED chain entry")
        void releaseHold_writesHoldReleasedEntry() throws Exception {
            MvcResult holdResult = mockMvc.perform(post(HOLDS_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdRequest(accountA.getId(), "30.00", "HOLD-CHAIN-REL")))
                .andExpect(status().isCreated())
                .andReturn();
            String holdId = objectMapper.readTree(holdResult.getResponse().getContentAsString())
                .get("id").asText();

            mockMvc.perform(post(HOLDS_ENDPOINT + "/{id}/release", holdId))
                .andExpect(status().isOk());

            mockMvc.perform(get(EVENTS_ENDPOINT + "/{entityId}", holdId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].eventType").value("HOLD_RELEASED"))
                .andExpect(jsonPath("$[1].eventType").value("HOLD_CREATED"));
        }
    }

    // =========================================================================
    // Chain hash linkage properties
    // =========================================================================

    @Nested
    @DisplayName("Hash linkage")
    class HashLinkage {

        @Test
        @DisplayName("genesis entry has null previousHash")
        void genesisEntry_hasnullPreviousHash() throws Exception {
            postTransaction("REF-GENESIS");

            mockMvc.perform(get(CHAIN_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].previousHash").doesNotExist());
        }

        @Test
        @DisplayName("second entry's previousHash equals the first entry's currentHash")
        void secondEntry_previousHashEqualsFirstCurrentHash() throws Exception {
            postTransaction("REF-LINK-A");
            postTransaction("REF-LINK-B");

            var result = mockMvc.perform(get(CHAIN_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andReturn();

            var tree = objectMapper.readTree(result.getResponse().getContentAsString());
            String newestCurrentHash   = tree.at("/content/0/currentHash").asText();
            String newestPreviousHash  = tree.at("/content/0/previousHash").asText();
            String oldestCurrentHash   = tree.at("/content/1/currentHash").asText();

            // newest entry's previousHash must equal oldest entry's currentHash
            org.assertj.core.api.Assertions.assertThat(newestPreviousHash)
                .isEqualTo(oldestCurrentHash);

            // currentHash must differ between entries
            org.assertj.core.api.Assertions.assertThat(newestCurrentHash)
                .isNotEqualTo(oldestCurrentHash);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String postTransaction(String referenceId) throws Exception {
        var result = mockMvc.perform(post(TX_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "debitAccountId",  accountA.getId(),
                    "creditAccountId", accountB.getId(),
                    "amount",          new BigDecimal("100.00"),
                    "referenceId",     referenceId
                ))))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
            .get("transactionId").asText();
    }

    private String createPendingTransaction(String referenceId) throws Exception {
        var result = mockMvc.perform(post(TX_ENDPOINT + "/pending")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "debitAccountId",  accountA.getId(),
                    "creditAccountId", accountB.getId(),
                    "amount",          new BigDecimal("50.00"),
                    "referenceId",     referenceId
                ))))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
            .get("transactionId").asText();
    }

    private String holdRequest(UUID accountId, String amount, String referenceId) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
            "accountId",   accountId.toString(),
            "amount",      amount,
            "referenceId", referenceId
        ));
    }
}
