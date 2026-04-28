package com.aetherledger.api;

import com.aetherledger.repository.LedgerAuditChainRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@link InternalApiKeyInterceptor}.
 *
 * <p>The valid key ({@code "test-internal-api-key"}) is set in
 * {@code application-test.properties} as {@code internal.api-key}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("InternalApiKeyInterceptor")
class InternalApiKeyInterceptorTest extends AbstractIntegrationTest {

    /** Must match application-test.properties: internal.api-key */
    private static final String VALID_KEY = "test-internal-api-key";
    private static final String WRONG_KEY = "wrong-key-xyz-999";

    private static final String OPS_LATEST  = "/api/v1/ops/ledger-integrity/chain/latest";
    private static final String OPS_VERIFY  = "/api/v1/ops/ledger-integrity/chain/verify";
    private static final String PUBLIC_LIST = "/api/v1/accounts";

    @Autowired MockMvc                    mockMvc;
    @Autowired LedgerAuditChainRepository auditChainRepository;

    @BeforeEach
    void setUp() {
        // Ensure the chain is empty so /latest returns 404 (not 200) when key is correct,
        // making the "allowed" assertions deterministic.
        auditChainRepository.deleteAllInBatch();
    }

    // =========================================================================
    // Blocked — missing or wrong key
    // =========================================================================

    @Nested
    @DisplayName("Blocked requests")
    class Blocked {

        @Test
        @DisplayName("no header → 401 UNAUTHORIZED with correct error envelope")
        void noHeader_returns401() throws Exception {
            mockMvc.perform(get(OPS_LATEST))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.path").value(OPS_LATEST));
        }

        @Test
        @DisplayName("wrong key → 401 UNAUTHORIZED")
        void wrongKey_returns401() throws Exception {
            mockMvc.perform(get(OPS_LATEST)
                    .header(InternalApiKeyInterceptor.HEADER_NAME, WRONG_KEY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
        }

        @Test
        @DisplayName("empty key header → 401 UNAUTHORIZED")
        void emptyKey_returns401() throws Exception {
            mockMvc.perform(get(OPS_LATEST)
                    .header(InternalApiKeyInterceptor.HEADER_NAME, ""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
        }

        @Test
        @DisplayName("POST /verify without key → 401 UNAUTHORIZED")
        void verifyWithoutKey_returns401() throws Exception {
            mockMvc.perform(post(OPS_VERIFY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
        }
    }

    // =========================================================================
    // Allowed — correct key passes the interceptor
    // =========================================================================

    @Nested
    @DisplayName("Allowed requests")
    class Allowed {

        @Test
        @DisplayName("correct key on /latest passes interceptor (404 from empty chain is fine)")
        void correctKey_latestPasses() throws Exception {
            MvcResult result = mockMvc.perform(get(OPS_LATEST)
                    .header(InternalApiKeyInterceptor.HEADER_NAME, VALID_KEY))
                .andReturn();

            assertThat(result.getResponse().getStatus())
                .as("Correct key must not produce 401")
                .isNotEqualTo(401);
        }

        @Test
        @DisplayName("correct key on POST /verify passes interceptor")
        void correctKey_verifyPasses() throws Exception {
            MvcResult result = mockMvc.perform(post(OPS_VERIFY)
                    .header(InternalApiKeyInterceptor.HEADER_NAME, VALID_KEY))
                .andReturn();

            assertThat(result.getResponse().getStatus())
                .as("Correct key must not produce 401")
                .isNotEqualTo(401);
        }
    }

    // =========================================================================
    // Public endpoints — interceptor must not affect them
    // =========================================================================

    @Nested
    @DisplayName("Public endpoints unaffected")
    class PublicEndpoints {

        @Test
        @DisplayName("GET /accounts without key returns 200")
        void accounts_noKey_returns200() throws Exception {
            mockMvc.perform(get(PUBLIC_LIST))
                .andExpect(status().isOk());
        }
    }
}
