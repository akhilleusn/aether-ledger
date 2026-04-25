package com.aetherledger.api;

import com.aetherledger.config.AiInsightsProperties;
import com.aetherledger.domain.entity.LedgerTransaction;
import com.aetherledger.service.AiInsightGenerator;
import com.aetherledger.service.InsightGenerator.Insight;
import com.aetherledger.service.RuleBasedInsightGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AiInsightGenerator}.
 *
 * <p>No Spring context — beans are instantiated directly.
 * AI provider calls are controlled via anonymous subclasses of
 * {@code AiInsightGenerator} that override {@code tryAiGenerate}.
 */
@DisplayName("AiInsightGenerator")
class AiInsightGeneratorTest {

    private AiInsightsProperties disabledProps;
    private AiInsightsProperties enabledProps;
    private RuleBasedInsightGenerator fallback;
    private LedgerTransaction tx;

    @BeforeEach
    void setUp() {
        disabledProps = new AiInsightsProperties(); // enabled=false by default

        enabledProps = new AiInsightsProperties();
        enabledProps.setEnabled(true);
        enabledProps.setProvider("stub");
        // API key intentionally not set — verifies no-key-no-crash behaviour

        fallback = new RuleBasedInsightGenerator();

        // Minimal transaction — NOT_RECONCILED (reconciledAt is null)
        tx = LedgerTransaction.create("TEST-REF-001");
    }

    @Nested
    @DisplayName("AI disabled")
    class AiDisabled {

        @Test
        @DisplayName("returns RULE_BASED insight when disabled")
        void generate_disabled_returnsRuleBasedInsight() {
            var generator = new AiInsightGenerator(disabledProps, fallback);
            Insight insight = generator.generate(tx);
            assertThat(insight.insightSource()).isEqualTo("RULE_BASED");
        }

        @Test
        @DisplayName("risk level is set by rule-based generator")
        void generate_disabled_hasRiskLevel() {
            var generator = new AiInsightGenerator(disabledProps, fallback);
            Insight insight = generator.generate(tx);
            assertThat(insight.riskLevel()).isIn("LOW", "MEDIUM", "HIGH");
        }

        @Test
        @DisplayName("explanation is non-empty")
        void generate_disabled_hasExplanation() {
            var generator = new AiInsightGenerator(disabledProps, fallback);
            Insight insight = generator.generate(tx);
            assertThat(insight.explanation()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("AI enabled — provider returns null (stub)")
    class AiEnabledStubNull {

        @Test
        @DisplayName("falls back to rule-based when tryAiGenerate returns null")
        void generate_enabledNullReturn_fallsBackToRuleBased() {
            var generator = new AiInsightGenerator(enabledProps, fallback) {
                @Override
                protected Insight tryAiGenerate(LedgerTransaction t) { return null; }
            };
            Insight insight = generator.generate(tx);
            assertThat(insight.insightSource()).isEqualTo("RULE_BASED");
        }
    }

    @Nested
    @DisplayName("AI enabled — provider throws")
    class AiEnabledProviderThrows {

        @Test
        @DisplayName("falls back to rule-based on exception — endpoint never breaks")
        void generate_enabledProviderThrows_fallsBackToRuleBased() {
            var generator = new AiInsightGenerator(enabledProps, fallback) {
                @Override
                protected Insight tryAiGenerate(LedgerTransaction t) {
                    throw new RuntimeException("Simulated provider failure");
                }
            };
            Insight insight = generator.generate(tx);
            assertThat(insight.insightSource()).isEqualTo("RULE_BASED");
            assertThat(insight.explanation()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("AI enabled — provider succeeds")
    class AiEnabledProviderSucceeds {

        @Test
        @DisplayName("returns AI insight with insightSource=AI")
        void generate_enabledProviderSucceeds_returnsAiInsight() {
            Insight aiInsight = new Insight(
                "AI-generated explanation",
                List.of("AI check 1", "AI check 2"),
                "HIGH",
                "AI"
            );
            var generator = new AiInsightGenerator(enabledProps, fallback) {
                @Override
                protected Insight tryAiGenerate(LedgerTransaction t) { return aiInsight; }
            };
            Insight insight = generator.generate(tx);
            assertThat(insight.insightSource()).isEqualTo("AI");
            assertThat(insight.riskLevel()).isEqualTo("HIGH");
            assertThat(insight.explanation()).isEqualTo("AI-generated explanation");
        }

        @Test
        @DisplayName("AI insight is returned unchanged — fallback is not called")
        void generate_enabledProviderSucceeds_doesNotCallFallback() {
            Insight aiInsight = new Insight(
                "AI explanation", List.of("check"), "MEDIUM", "AI"
            );
            var generator = new AiInsightGenerator(enabledProps, fallback) {
                @Override
                protected Insight tryAiGenerate(LedgerTransaction t) { return aiInsight; }
            };
            Insight insight = generator.generate(tx);
            assertThat(insight).isSameAs(aiInsight);
        }
    }

    @Nested
    @DisplayName("no API key configured")
    class NoApiKey {

        @Test
        @DisplayName("missing API key does not cause startup or runtime failure")
        void generate_noApiKey_doesNotThrow() {
            enabledProps.setApiKey("");
            var generator = new AiInsightGenerator(enabledProps, fallback);
            // tryAiGenerate returns null by default — fallback used silently
            assertThat(generator.generate(tx)).isNotNull();
        }
    }
}
