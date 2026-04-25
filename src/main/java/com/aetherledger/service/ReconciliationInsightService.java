package com.aetherledger.service;

import com.aetherledger.api.dto.ReconciliationInsightResponse;
import com.aetherledger.domain.entity.LedgerTransaction;
import com.aetherledger.exception.LedgerTransactionNotFoundException;
import com.aetherledger.repository.LedgerTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Generates operator-facing reconciliation insights for individual transactions.
 *
 * <p>Insight generation is delegated to the {@link InsightGenerator} port, which
 * defaults to {@link RuleBasedInsightGenerator}.  Swap the bean for an AI-backed
 * adapter to upgrade to language-model-generated explanations with no changes here.
 *
 * <p>This service is strictly read-only and never mutates ledger data.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationInsightService {

    private final LedgerTransactionRepository ledgerTransactionRepository;
    private final InsightGenerator insightGenerator;

    @Transactional(readOnly = true)
    public ReconciliationInsightResponse getInsight(UUID transactionId) {
        LedgerTransaction tx = ledgerTransactionRepository.findById(transactionId)
            .orElseThrow(() -> new LedgerTransactionNotFoundException("id", transactionId.toString()));

        InsightGenerator.Insight insight = insightGenerator.generate(tx);

        return new ReconciliationInsightResponse(
            tx.getId(),
            tx.getReferenceId(),
            tx.getStatus().name(),
            tx.getExternalStatus() != null ? tx.getExternalStatus().name() : null,
            tx.computeReconciliationResult().name(),
            insight.explanation(),
            insight.recommendedChecks(),
            insight.insightSource(),
            insight.riskLevel()
        );
    }
}
