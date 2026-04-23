package com.aetherledger.api;

import com.aetherledger.api.dto.LedgerIntegrityReportResponse;
import com.aetherledger.api.dto.LedgerIssueResponse;
import com.aetherledger.api.dto.ReconciliationHealthResponse;
import com.aetherledger.service.OpsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only operational observability endpoints.
 *
 * <p>These endpoints expose structural correctness checks and reconciliation
 * health without requiring direct database access.  No write operations are
 * performed; all methods are safe to call at any time.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ops/integrity")
@RequiredArgsConstructor
@Tag(name = "Operational Integrity", description = "Read-only ledger integrity checks and reconciliation health reports. " +
    "All endpoints are safe to call repeatedly without side effects.")
public class OpsController {

    private final OpsService opsService;

    @Operation(
        summary = "Ledger integrity report",
        description = "Returns a snapshot of the ledger's structural health. " +
            "`healthy` is `true` only when `zeroSumViolationsCount` and `transactionsWithUnexpectedEntryCount` are both zero. " +
            "A non-zero count in either field indicates data corruption or a bug in the transaction engine."
    )
    @ApiResponse(responseCode = "200", description = "Integrity report (check `healthy` field for pass/fail)")
    @GetMapping("/ledger")
    public LedgerIntegrityReportResponse getLedgerIntegrityReport() {
        log.debug("Ledger integrity report requested");
        return opsService.getLedgerIntegrityReport();
    }

    @Operation(
        summary = "List individual ledger integrity violations",
        description = "Returns the transactions that are driving a non-zero violation count in the integrity report. " +
            "Each item includes `issueTypes` containing one or more of: " +
            "`ZERO_SUM_VIOLATION` (entries do not net to zero) or " +
            "`UNEXPECTED_ENTRY_COUNT` (entry count is inconsistent with the transaction status). " +
            "Returns an empty list when the ledger is healthy."
    )
    @ApiResponse(responseCode = "200", description = "List of violated transactions (empty when ledger is healthy)")
    @GetMapping("/ledger/issues")
    public List<LedgerIssueResponse> getLedgerIntegrityIssues() {
        log.debug("Ledger integrity issues requested");
        return opsService.getLedgerIntegrityIssues();
    }

    @Operation(
        summary = "Reconciliation health report",
        description = "Returns aggregate reconciliation statistics focused on identifying problematic transactions. " +
            "`healthy` is `true` only when `mismatchCount` and `missingExternalReferenceCount` are both zero."
    )
    @ApiResponse(responseCode = "200", description = "Reconciliation health snapshot (check `healthy` field for pass/fail)")
    @GetMapping("/reconciliation")
    public ReconciliationHealthResponse getReconciliationHealth() {
        log.debug("Reconciliation health report requested");
        return opsService.getReconciliationHealth();
    }
}
