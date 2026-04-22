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

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("AccountController")
class AccountControllerTest {

    private static final String ENDPOINT = "/api/v1/accounts";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AccountRepository accountRepository;
    @Autowired LedgerTransactionRepository ledgerTransactionRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;

    @BeforeEach
    void setUp() {
        // Delete in FK-safe order: entries → transactions → accounts
        ledgerEntryRepository.deleteAllInBatch();
        ledgerTransactionRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
    }

    // =========================================================================
    // POST /api/v1/accounts
    // =========================================================================

    @Nested
    @DisplayName("POST /api/v1/accounts")
    class CreateAccount {

        @Nested
        @DisplayName("success scenarios")
        class SuccessScenarios {

            @Test
            @DisplayName("valid request returns 201 with id, name, type, createdAt")
            void create_validRequest_returns201() throws Exception {
                mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(createRequest("Alice", "USER"))))
                    .andExpect(status().isCreated())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.id").isNotEmpty())
                    .andExpect(jsonPath("$.name").value("Alice"))
                    .andExpect(jsonPath("$.type").value("USER"))
                    .andExpect(jsonPath("$.createdAt").isNotEmpty())
                    .andExpect(jsonPath("$.currentBalance").doesNotExist());
            }

            @Test
            @DisplayName("MERCHANT type account is created successfully")
            void create_merchantType_returns201() throws Exception {
                mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(createRequest("Acme Corp", "MERCHANT"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("Acme Corp"));
            }

            @Test
            @DisplayName("SYSTEM type account is created successfully")
            void create_systemType_returns201() throws Exception {
                mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(createRequest("Escrow Pool", "SYSTEM"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("Escrow Pool"));
            }

            @Test
            @DisplayName("two accounts with distinct names both succeed")
            void create_distinctNames_bothSucceed() throws Exception {
                mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(createRequest("Alice", "USER"))))
                    .andExpect(status().isCreated());

                mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(createRequest("Bob", "USER"))))
                    .andExpect(status().isCreated());
            }
        }

        @Nested
        @DisplayName("400 Bad Request")
        class BadRequest {

            @Test
            @DisplayName("blank name returns 400 with VALIDATION_FAILED")
            void create_blankName_returns400() throws Exception {
                mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(createRequest("   ", "USER"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
            }

            @Test
            @DisplayName("missing name returns 400 with VALIDATION_FAILED")
            void create_missingName_returns400() throws Exception {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("type", "USER");

                mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(body)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
            }

            @Test
            @DisplayName("missing type returns 400 with VALIDATION_FAILED")
            void create_missingType_returns400() throws Exception {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("name", "Alice");

                mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(body)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
            }

            @Test
            @DisplayName("invalid type value returns 400 with MALFORMED_REQUEST")
            void create_invalidType_returns400() throws Exception {
                mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(createRequest("Alice", "INVALID"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
            }
        }

        @Nested
        @DisplayName("409 Conflict")
        class Conflict {

            @Test
            @DisplayName("duplicate name returns 409 with DUPLICATE_ACCOUNT_NAME")
            void create_duplicateName_returns409() throws Exception {
                accountRepository.save(Account.of("Alice", AccountType.USER));

                mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(createRequest("Alice", "MERCHANT"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("DUPLICATE_ACCOUNT_NAME"))
                    .andExpect(jsonPath("$.message").isNotEmpty());
            }
        }
    }

    // =========================================================================
    // GET /api/v1/accounts/{id}
    // =========================================================================

    @Nested
    @DisplayName("GET /api/v1/accounts/{id}")
    class GetById {

        @Test
        @DisplayName("returns 200 with currentBalance = 0 when account has no entries")
        void getById_noEntries_returnsZeroBalance() throws Exception {
            Account account = accountRepository.save(Account.of("Carol", AccountType.USER));

            mockMvc.perform(get(ENDPOINT + "/{id}", account.getId()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(account.getId().toString()))
                .andExpect(jsonPath("$.name").value("Carol"))
                .andExpect(jsonPath("$.type").value("USER"))
                .andExpect(jsonPath("$.currentBalance").value(0))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
        }

        @Test
        @DisplayName("returns 404 with ACCOUNT_NOT_FOUND for unknown id")
        void getById_unknownId_returns404() throws Exception {
            mockMvc.perform(get(ENDPOINT + "/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_FOUND"));
        }

        @Test
        @DisplayName("balance reflects posted ledger transactions")
        void getById_afterTransaction_balanceIsCorrect() throws Exception {
            Account payer    = accountRepository.save(Account.of("Payer",    AccountType.USER));
            Account merchant = accountRepository.save(Account.of("Merchant", AccountType.MERCHANT));

            postTransaction(payer.getId(), merchant.getId(), new BigDecimal("150.00"), "REF-GET-BY-ID-BAL");

            mockMvc.perform(get(ENDPOINT + "/{id}", merchant.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBalance").value(150.00));

            mockMvc.perform(get(ENDPOINT + "/{id}", payer.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBalance").value(-150.00));
        }
    }

    // =========================================================================
    // GET /api/v1/accounts
    // =========================================================================

    @Nested
    @DisplayName("GET /api/v1/accounts")
    class ListAccounts {

        @Test
        @DisplayName("returns empty list when no accounts exist")
        void list_noAccounts_returnsEmptyList() throws Exception {
            mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        @DisplayName("returns all accounts with id, name, type, currentBalance, createdAt")
        void list_returnsAllAccounts() throws Exception {
            accountRepository.save(Account.of("Alice",   AccountType.USER));
            accountRepository.save(Account.of("Bob",     AccountType.USER));
            accountRepository.save(Account.of("Escrow",  AccountType.SYSTEM));

            mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].id").isNotEmpty())
                .andExpect(jsonPath("$[0].name").isNotEmpty())
                .andExpect(jsonPath("$[0].type").isNotEmpty())
                .andExpect(jsonPath("$[0].currentBalance").exists())
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty());
        }

        @Test
        @DisplayName("every account in the list carries currentBalance = 0 when no entries exist")
        void list_noEntries_allBalancesAreZero() throws Exception {
            accountRepository.save(Account.of("Alpha", AccountType.USER));
            accountRepository.save(Account.of("Beta",  AccountType.MERCHANT));

            mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].currentBalance").value(0))
                .andExpect(jsonPath("$[1].currentBalance").value(0));
        }

        @Test
        @DisplayName("list reflects computed balances after a ledger transaction")
        void list_afterTransaction_balancesAreComputed() throws Exception {
            Account sender   = accountRepository.save(Account.of("Sender",   AccountType.SYSTEM));
            Account receiver = accountRepository.save(Account.of("Receiver", AccountType.USER));

            postTransaction(sender.getId(), receiver.getId(), new BigDecimal("200.00"), "REF-LIST-BAL-001");

            mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

            // Verify each account's balance individually to avoid ordering dependence
            mockMvc.perform(get(ENDPOINT + "/{id}", sender.getId()))
                .andExpect(jsonPath("$.currentBalance").value(-200.00));

            mockMvc.perform(get(ENDPOINT + "/{id}", receiver.getId()))
                .andExpect(jsonPath("$.currentBalance").value(200.00));
        }

        @Test
        @DisplayName("balance accumulates correctly across multiple transactions")
        void list_multipleTransactions_balanceAccumulates() throws Exception {
            Account wallet  = accountRepository.save(Account.of("Wallet",  AccountType.USER));
            Account reserve = accountRepository.save(Account.of("Reserve", AccountType.SYSTEM));

            // Wallet receives 500, then sends 200 → net +300
            postTransaction(reserve.getId(), wallet.getId(),  new BigDecimal("500.00"), "REF-MULTI-TX1");
            postTransaction(wallet.getId(),  reserve.getId(), new BigDecimal("200.00"), "REF-MULTI-TX2");

            mockMvc.perform(get(ENDPOINT + "/{id}", wallet.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBalance").value(300.00));

            mockMvc.perform(get(ENDPOINT + "/{id}", reserve.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBalance").value(-300.00));
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Map<String, Object> createRequest(String name, String type) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("type", type);
        return body;
    }

    private void postTransaction(UUID debitId, UUID creditId, BigDecimal amount, String referenceId)
            throws Exception {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("debitAccountId",  debitId);
        body.put("creditAccountId", creditId);
        body.put("amount",          amount);
        body.put("referenceId",     referenceId);

        mockMvc.perform(post("/api/v1/ledger-transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(body)))
            .andExpect(status().isCreated());
    }

    private String toJson(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
