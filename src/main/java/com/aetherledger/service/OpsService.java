package com.aetherledger.service;

import com.aetherledger.api.dto.LedgerIntegrityReportResponse;
import com.aetherledger.api.dto.LedgerIssueResponse;
import com.aetherledger.api.dto.ReconciliationHealthResponse;
import com.aetherledger.domain.entity.LedgerTransaction;
import com.aetherledger.domain.enums.TransactionStatus;
import com.aetherledger.repository.LedgerTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes operational observability reports for the ledger and reconciliation subsystems.
 *
 * <p>All reads are performed via targeted COUNT / aggregate queries — no full
 * table scans, no in-memory aggregation.  Every public method is read-only and
 * does not acquire any write locks.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsService {

    private final LedgerTransactionRepository ledgerTransactionRepository;

    // -------------------------------------------------------------------------
    // Ledger integrity
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public LedgerIntegrityReportResponse getLedgerIntegrityReport() {
        long total       = ledgerTransactionRepository.count();
        long successful  = ledgerTransactionRepository.countByStatus(TransactionStatus.SUCCESS);
        long pending     = ledgerTransactionRepository.countByStatus(TransactionStatus.PENDING);
        long failed      = ledgerTransactionRepository.countByStatus(TransactionStatus.FAILED);
        long reversed    = ledgerTransactionRepository.countReversed();
        long zeroSumViol = ledgerTransactionRepository.countZeroSumViolations();
        long unexpEntry  = ledgerTransactionRepository.countUnexpectedEntryCount();

        log.debug("Ledger integrity report: total={} success={} pending={} failed={} "
                + "reversed={} zeroSumViol={} unexpectedEntry={}",
            total, successful, pending, failed, reversed, zeroSumViol, unexpEntry);

        return new LedgerIntegrityReportResponse(
            total, successful, pending, failed, reversed,
            zeroSumViol, unexpEntry,
            zeroSumViol == 0 && unexpEntry == 0
        );
    }

    @Transactional(readOnly = true)
    public List<LedgerIssueResponse> getLedgerIntegrityIssues() {
        List<Object[]> rows = ledgerTransactionRepository.findIntegrityIssues();

        List<LedgerIssueResponse> issues = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            LedgerTransaction tx  = (LedgerTransaction) row[0];
            long   entryCount     = (Long)       row[1];
            BigDecimal entrySum   = row[2] != null ? (BigDecimal) row[2] : BigDecimal.ZERO;

            List<String> issueTypes = new ArrayList<>(2);

            boolean unexpectedCount = switch (tx.getStatus()) {
                case SUCCESS        -> entryCount != 2;
                case PENDING, FAILED -> entryCount != 0;
            };
            if (unexpectedCount) {
                issueTypes.add("UNEXPECTED_ENTRY_COUNT");
            }

            if (tx.getStatus() == TransactionStatus.SUCCESS
                    && entryCount > 0
                    && entrySum.compareTo(BigDecimal.ZERO) != 0) {
                issueTypes.add("ZERO_SUM_VIOLATION");
            }

            issues.add(new LedgerIssueResponse(
                tx.getId(),
                tx.getReferenceId(),
                tx.getStatus().name(),
                entryCount,
                entrySum,
                List.copyOf(issueTypes)
            ));
        }
        return issues;
    }

    // -------------------------------------------------------------------------
    // Reconciliation health
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ReconciliationHealthResponse getReconciliationHealth() {
        long total         = ledgerTransactionRepository.count();
        long notReconciled = ledgerTransactionRepository.countNotReconciled();
        long missingRef    = ledgerTransactionRepository.countMissingExternalReference();
        long matched       = ledgerTransactionRepository.countMatched();
        long mismatch      = total - notReconciled - missingRef - matched;

        log.debug("Reconciliation health: total={} matched={} mismatch={} notReconciled={} missingRef={}",
            total, matched, mismatch, notReconciled, missingRef);

        return new ReconciliationHealthResponse(
            total, matched, mismatch, notReconciled, missingRef,
            mismatch == 0 && missingRef == 0
        );
    }
}
