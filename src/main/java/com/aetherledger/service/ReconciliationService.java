package com.aetherledger.service;

import com.aetherledger.api.dto.BatchReconcileItem;
import com.aetherledger.api.dto.BatchReconcileItemResult;
import com.aetherledger.api.dto.BatchReconcileResponse;
import com.aetherledger.api.dto.ReconciliationSummaryResponse;
import com.aetherledger.domain.entity.LedgerTransaction;
import com.aetherledger.domain.entity.ReconciliationRun;
import com.aetherledger.domain.enums.ReconciliationResult;
import com.aetherledger.exception.ReconciliationRunNotFoundException;
import com.aetherledger.repository.LedgerTransactionRepository;
import com.aetherledger.repository.ReconciliationRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates reconciliation operations against the ledger.
 *
 * <h3>Batch reconciliation</h3>
 * Each item in the batch is matched by internal {@code referenceId}.
 * If found, the transaction's external reconciliation fields are updated and
 * the derived {@link ReconciliationResult} is computed.
 * If not found, the item is counted as a missing transaction — it is never
 * treated as an error so that partial provider files are fully processed.
 *
 * <h3>Summary</h3>
 * Aggregate counts are computed via targeted COUNT queries — no full table scan.
 * The mismatch count is derived: {@code total - notReconciled - missingRef - matched}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final LedgerTransactionRepository ledgerTransactionRepository;
    private final ReconciliationRunRepository reconciliationRunRepository;

    @Transactional(rollbackFor = Exception.class)
    public BatchReconcileResponse batchReconcile(List<BatchReconcileItem> items) {
        log.debug("Starting batch reconciliation: itemCount={}", items.size());

        List<BatchReconcileItemResult> results = new ArrayList<>(items.size());
        int updatedCount = 0, matchedCount = 0, mismatchCount = 0,
            missingCount = 0, missingRefCount = 0;

        for (BatchReconcileItem item : items) {
            Optional<LedgerTransaction> txOpt =
                ledgerTransactionRepository.findByReferenceId(item.referenceId());

            if (txOpt.isEmpty()) {
                results.add(BatchReconcileItemResult.notFound(item.referenceId()));
                missingCount++;
                continue;
            }

            LedgerTransaction tx = txOpt.get();
            tx.reconcile(item.externalReferenceId(), item.externalStatus());
            ledgerTransactionRepository.save(tx);

            ReconciliationResult result = tx.computeReconciliationResult();
            results.add(BatchReconcileItemResult.updated(item.referenceId(), tx.getId(), result));

            updatedCount++;
            switch (result) {
                case MATCHED                    -> matchedCount++;
                case STATUS_MISMATCH            -> mismatchCount++;
                case MISSING_EXTERNAL_REFERENCE -> missingRefCount++;
                default -> {}  // NOT_RECONCILED cannot occur immediately after reconcile()
            }
        }

        log.info("Batch reconciliation complete: total={} updated={} matched={} mismatch={} "
                + "missingTransaction={} missingExternalRef={}",
            items.size(), updatedCount, matchedCount, mismatchCount, missingCount, missingRefCount);

        ReconciliationRun run = ReconciliationRun.create(
            items.size(), updatedCount, matchedCount, mismatchCount, missingCount, missingRefCount);

        for (BatchReconcileItemResult r : results) {
            run.addItem(r.referenceId(), r.transactionId(), r.reconciliationResult(), r.status());
        }

        ReconciliationRun saved = reconciliationRunRepository.save(run);

        return new BatchReconcileResponse(
            saved.getId(),
            items.size(), updatedCount, matchedCount, mismatchCount,
            missingCount, missingRefCount, results);
    }

    @Transactional(readOnly = true)
    public ReconciliationRun getRunById(UUID runId) {
        return reconciliationRunRepository.findByIdWithItems(runId)
            .orElseThrow(() -> new ReconciliationRunNotFoundException(runId));
    }

    @Transactional(readOnly = true)
    public ReconciliationSummaryResponse getSummary() {
        long total         = ledgerTransactionRepository.count();
        long notReconciled = ledgerTransactionRepository.countNotReconciled();
        long missingRef    = ledgerTransactionRepository.countMissingExternalReference();
        long matched       = ledgerTransactionRepository.countMatched();
        long mismatch      = total - notReconciled - missingRef - matched;

        return new ReconciliationSummaryResponse(total, notReconciled, matched, mismatch, missingRef);
    }
}
