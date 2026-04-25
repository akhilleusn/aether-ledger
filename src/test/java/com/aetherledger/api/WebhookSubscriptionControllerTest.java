package com.aetherledger.api;

import com.aetherledger.repository.WebhookDeliveryRepository;
import com.aetherledger.repository.WebhookSubscriptionRepository;
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

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("WebhookSubscriptionController")
class WebhookSubscriptionControllerTest extends AbstractIntegrationTest {

    private static final String ENDPOINT = "/api/v1/webhook-subscriptions";

    @Autowired MockMvc                        mockMvc;
    @Autowired ObjectMapper                   objectMapper;
    @Autowired WebhookSubscriptionRepository  subscriptionRepository;
    @Autowired WebhookDeliveryRepository      deliveryRepository;

    @BeforeEach
    void setUp() {
        deliveryRepository.deleteAllInBatch();
        subscriptionRepository.deleteAllInBatch();
    }

    // =========================================================================
    // POST — create
    // =========================================================================

    @Nested
    @DisplayName("POST — create subscription")
    class Create {

        @Test
        @DisplayName("valid request returns 201 with subscription body")
        void create_validRequest_returns201() throws Exception {
            var body = Map.of(
                "targetUrl", "https://example.com/webhooks",
                "eventType", "TRANSACTION_POSTED"
            );
            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.targetUrl").value("https://example.com/webhooks"))
                .andExpect(jsonPath("$.eventType").value("TRANSACTION_POSTED"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
        }

        @Test
        @DisplayName("http URL is accepted")
        void create_httpUrl_accepted() throws Exception {
            var body = Map.of(
                "targetUrl", "http://internal.example.com/hook",
                "eventType", "TRANSACTION_COMPLETED"
            );
            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("missing targetUrl returns 400")
        void create_missingTargetUrl_returns400() throws Exception {
            var body = Map.of("eventType", "TRANSACTION_POSTED");
            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("invalid URL scheme returns 400")
        void create_ftpUrl_returns400() throws Exception {
            var body = Map.of(
                "targetUrl", "ftp://example.com/hook",
                "eventType", "TRANSACTION_POSTED"
            );
            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("unknown eventType returns 400")
        void create_unknownEventType_returns400() throws Exception {
            var body = Map.of(
                "targetUrl", "https://example.com/webhooks",
                "eventType", "NOT_A_REAL_EVENT"
            );
            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("missing eventType returns 400")
        void create_missingEventType_returns400() throws Exception {
            var body = Map.of("targetUrl", "https://example.com/webhooks");
            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }
    }

    // =========================================================================
    // GET — list active
    // =========================================================================

    @Nested
    @DisplayName("GET — list active subscriptions")
    class ListActive {

        @Test
        @DisplayName("returns only active subscriptions")
        void listActive_returnsOnlyActive() throws Exception {
            createSubscription("https://a.example.com/hook", "TRANSACTION_POSTED");
            createSubscription("https://b.example.com/hook", "TRANSACTION_REVERSED");

            mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[1].active").value(true));
        }

        @Test
        @DisplayName("returns empty list when no subscriptions exist")
        void listActive_noSubscriptions_returnsEmpty() throws Exception {
            mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        }
    }

    // =========================================================================
    // DELETE — deactivate
    // =========================================================================

    @Nested
    @DisplayName("DELETE — deactivate subscription")
    class Deactivate {

        @Test
        @DisplayName("deactivated subscription no longer appears in active list")
        void deactivate_removesFromActiveList() throws Exception {
            String id = createSubscription("https://example.com/hook", "TRANSACTION_POSTED");

            mockMvc.perform(delete(ENDPOINT + "/" + id))
                .andExpect(status().isNoContent());

            mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("unknown UUID returns 404")
        void deactivate_unknownId_returns404() throws Exception {
            mockMvc.perform(delete(ENDPOINT + "/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("WEBHOOK_SUBSCRIPTION_NOT_FOUND"));
        }

        @Test
        @DisplayName("invalid UUID returns 400")
        void deactivate_invalidUuid_returns400() throws Exception {
            mockMvc.perform(delete(ENDPOINT + "/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PATH_VARIABLE"));
        }
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private String createSubscription(String targetUrl, String eventType) throws Exception {
        var body = Map.of("targetUrl", targetUrl, "eventType", eventType);
        String json = mockMvc.perform(post(ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        @SuppressWarnings("unchecked")
        String id = (String) objectMapper.readValue(json, Map.class).get("id");
        return id;
    }
}
