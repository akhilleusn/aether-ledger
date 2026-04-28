package com.aetherledger.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Typed configuration for the internal ops API key.
 *
 * <p>The key is injected via the {@code INTERNAL_API_KEY} environment variable
 * and is never logged or included in API responses.
 *
 * <p>If the key is blank at runtime the interceptor <em>fails closed</em>:
 * every request to {@code /api/v1/ops/**} is denied with {@code 401}.
 */
@Component
@ConfigurationProperties(prefix = "internal")
public class InternalApiKeyProperties {

    /**
     * Expected value for the {@code X-Internal-Api-Key} request header.
     * Defaults to blank, which triggers fail-closed behaviour.
     */
    private String apiKey = "";

    public String getApiKey()         { return apiKey; }
    public void   setApiKey(String v) { this.apiKey = v; }
}
