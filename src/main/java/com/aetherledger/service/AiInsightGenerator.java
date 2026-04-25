package com.aetherledger.service;

import com.aetherledger.config.AiInsightsProperties;
import com.aetherledger.domain.entity.LedgerTransaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * AI-backed {@link InsightGenerator} that is the primary bean in the context.
 *
 * <p>When {@code ai.insights.enabled=false} (the default), this generator
 * delegates immediately to {@link RuleBasedInsightGenerator} without any
 * external call.  When enabled, {@link #tryAiGenerate} is invoked; if it
 * returns {@code null} or throws for any reason, the rule-based generator is
 * used as a silent fallback so the endpoint never fails due to AI unavailability.
 *
 * <h3>Plugging in a real provider</h3>
 * Override or replace {@link #tryAiGenerate} with the actual provider call
 * (e.g. Claude, OpenAI).  Set these properties (or environment variables):
 * <pre>
 *   ai.insights.enabled  = true
 *   ai.insights.provider = claude          # or openai, etc.
 *   AI_INSIGHTS_API_KEY  = &lt;your key&gt;    # never hard-coded
 * </pre>
 *
 * <h3>Security</h3>
 * The API key is never logged.  Only the provider name appears in warning
 * messages so that log aggregators do not capture credentials.
 */
@Slf4j
@Primary
@Component
public class AiInsightGenerator implements InsightGenerator {

    private final AiInsightsProperties properties;
    private final RuleBasedInsightGenerator fallback;

    public AiInsightGenerator(AiInsightsProperties properties, RuleBasedInsightGenerator fallback) {
        this.properties = properties;
        this.fallback   = fallback;
    }

    @Override
    public Insight generate(LedgerTransaction tx) {
        if (!properties.isEnabled()) {
            log.debug("AI insights disabled — using rule-based generator");
            return fallback.generate(tx);
        }

        try {
            Insight aiInsight = tryAiGenerate(tx);
            if (aiInsight != null) {
                return aiInsight;
            }
            log.debug("AI provider returned no insight for transactionId={}, falling back to rule-based",
                tx.getId());
        } catch (Exception e) {
            // Log provider name only — never the API key.
            log.warn("AI insight generation failed for transactionId={} provider={}, " +
                     "falling back to rule-based: {}",
                tx.getId(), properties.getProvider(), e.getMessage());
        }

        return fallback.generate(tx);
    }

    /**
     * Stub for the real AI provider call.
     *
     * <p>Returns {@code null} so the fallback is always used until a real
     * implementation is plugged in.  Override this method in tests to simulate
     * AI success or failure without a live provider.
     *
     * @return an {@link Insight} with {@code insightSource="AI"}, or {@code null}
     *         to trigger the rule-based fallback
     */
    protected Insight tryAiGenerate(LedgerTransaction tx) {
        return null;
    }
}
