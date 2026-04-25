package com.aetherledger.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Typed configuration for the AI insight engine.
 *
 * <p>All properties are optional and default to a safe disabled state.
 * Set {@code ai.insights.enabled=true} and configure a real provider to
 * activate language-model-generated insights.
 *
 * <p>The API key is read from the environment variable
 * {@code AI_INSIGHTS_API_KEY} and is never logged or exposed in responses.
 */
@Component
@ConfigurationProperties(prefix = "ai.insights")
public class AiInsightsProperties {

    /** Whether AI-generated insights are attempted. Defaults to {@code false}. */
    private boolean enabled = false;

    /** Provider identifier (e.g. {@code "claude"}, {@code "openai"}). Defaults to {@code "disabled"}. */
    private String provider = "disabled";

    /** API key injected via {@code AI_INSIGHTS_API_KEY} env var. Never logged. */
    private String apiKey = "";

    public boolean isEnabled()          { return enabled; }
    public void setEnabled(boolean v)   { this.enabled = v; }

    public String getProvider()         { return provider; }
    public void setProvider(String v)   { this.provider = v; }

    public String getApiKey()           { return apiKey; }
    public void setApiKey(String v)     { this.apiKey = v; }
}
