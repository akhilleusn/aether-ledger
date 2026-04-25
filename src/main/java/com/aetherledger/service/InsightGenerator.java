package com.aetherledger.service;

import com.aetherledger.domain.entity.LedgerTransaction;

import java.util.List;

/**
 * Port interface for generating operator-facing reconciliation insights.
 *
 * <p>The default implementation ({@link RuleBasedInsightGenerator}) applies
 * deterministic rules.  Swap this bean with a real AI adapter — for example,
 * one backed by Claude or OpenAI — to upgrade to language-model-generated
 * explanations with no changes to callers.
 */
public interface InsightGenerator {

    Insight generate(LedgerTransaction tx);

    record Insight(
        String explanation,
        List<String> recommendedChecks,
        /** {@code LOW}, {@code MEDIUM}, or {@code HIGH}. */
        String riskLevel,
        /** {@code RULE_BASED} or {@code AI}. */
        String insightSource
    ) {}
}
