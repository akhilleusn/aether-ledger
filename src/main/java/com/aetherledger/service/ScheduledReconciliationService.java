package com.aetherledger.service;

import com.aetherledger.domain.entity.LedgerTransaction;
import com.aetherledger.domain.enums.ReconciliationResult;
import com.aetherledger.repository.LedgerTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.OptimisticLockException;
import java.util.List;
import java.util.UUID;

/**
 * Processes unreconciled ledger transactions against the external provider.
 *
 * <h3>Concurrency model</h3>
 * Each transaction is processed in an isolated {@code REQUIRES_NEW} transaction.
 * The {@link LedgerTransaction#markProcessing()} call and the reconciliation
 * data are committed together.  Because {@link LedgerTransaction} carries a
 * {@code @Version} column, two concurrent scheduler instances that both read
 * the same row at version&nbsp;N will race at commit time: the winner's UPDATE
 * succeeds; the loser's UPDATE matches zero rows and surfaces as
 * {@link ObjectOptimisticLockingFailureException}, which is caught here and
 * counted as {@code skipped}.
 *
 * <h3>Self-proxy pattern</h3>
 * {@link #processSingle} must be called through the Spring proxy to honour
 * {@code @Transactional(REQUIRES_NEW)}.  Direct {@code this.processSingle()}
 * would bypass the proxy.  The {@code self} field is injected lazily to avoid
 * a circular-dependency failure at construction time.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledReconciliationService {

    private final LedgerTransactionRepository ledgerTransactionRepository;
    private final ExternalReconciliationClient externalClient;

    @Autowired
    @Lazy
    private ScheduledReconciliationService self;

    // -------------------------------------------------------------------------
    // Main entry point
    // -------------------------------------------------------------------------

    /**
     * Finds all eligible unreconciled transactions and processes each one
     * independently.  A failure on any individual transaction does not abort
     * the rest of the batch.
     */
    public void processUnreconciled() {
        log.info("Scheduled reconciliation job started");

        List<LedgerTransaction> candidates = ledgerTransactionRepository.findUnreconciled();

        if (candidates.isEmpty()) {
            log.info("Scheduled reconciliation job: no unreconciled transactions found");
            return;
        }

        log.info("Scheduled reconciliation job: processing {} candidate(s)", candidates.size());

        int matched = 0, mismatch = 0, skipped = 0, failed = 0;

        for (LedgerTransaction tx : candidates) {
            try {
                ReconciliationResult result = self.processSingle(tx.getId());
                if (result == null) {
                    skipped++;
                } else if (result == ReconciliationResult.MATCHED) {
                    matched++;
                } else {
                    mismatch++;
                }
            } catch (ObjectOptimisticLockingFailureException | OptimisticLockException e) {
                log.debug("Skipping transaction id={}: claimed by a concurrent scheduler instance",
                    tx.getId());
                skipped++;
            } catch (Exception e) {
                log.error("Failed to reconcile transaction id={}: {}", tx.getId(), e.getMessage(), e);
                failed++;
            }
        }

        log.info("Scheduled reconciliation job complete: matched={} mismatch={} skipped={} failed={}",
            matched, mismatch, skipped, failed);
    }

    // -------------------------------------------------------------------------
    // Per-transaction processing (REQUIRES_NEW)
    // -------------------------------------------------------------------------

    /**
     * Claims and reconciles a single transaction in its own isolated transaction.
     *
     * <p>Returns the computed {@link ReconciliationResult} on success, or
     * {@code null} if the transaction was already claimed or reconciled by the
     * time this method acquired a fresh read.
     *
     * <p>Throws {@link ObjectOptimisticLockingFailureException} at commit time
     * if a concurrent instance claimed the same row between the read and the
     * save — the caller is responsible for catching and counting this as skipped.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public ReconciliationResult processSingle(UUID transactionId) {
        LedgerTransaction tx = ledgerTransactionRepository.findById(transactionId).orElse(null);

        if (tx == null || tx.getReconciledAt() != null || tx.isProcessing()) {
            log.debug("Skipping transaction id={}: already reconciled or claimed", transactionId);
            return null;
        }

        // Claim the row — version bump at commit prevents concurrent double-processing.
        tx.markProcessing();

        ExternalResult external = externalClient.fetch(tx.getReferenceId());
        tx.reconcile(external.externalReferenceId(), external.externalStatus());
        tx.unmarkProcessing();

        // save() stages the UPDATE; the actual SQL runs at commit with a WHERE version=N
        // check.  If a concurrent instance already committed, this will throw OLE.
        ledgerTransactionRepository.save(tx);

        ReconciliationResult result = tx.computeReconciliationResult();
        log.debug("Reconciled transaction id={} referenceId='{}' result={}",
            tx.getId(), tx.getReferenceId(), result);

        return result;
    }
}
