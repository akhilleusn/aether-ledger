package com.aetherledger.api;

import com.aetherledger.api.dto.LedgerIntegrityReportResponse;
import com.aetherledger.api.dto.LedgerIssueResponse;
import com.aetherledger.api.dto.ReconciliationHealthResponse;
import com.aetherledger.service.OpsService;
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
public class OpsController {

    private final OpsService opsService;

    @GetMapping("/ledger")
    public LedgerIntegrityReportResponse getLedgerIntegrityReport() {
        log.debug("Ledger integrity report requested");
        return opsService.getLedgerIntegrityReport();
    }

    @GetMapping("/ledger/issues")
    public List<LedgerIssueResponse> getLedgerIntegrityIssues() {
        log.debug("Ledger integrity issues requested");
        return opsService.getLedgerIntegrityIssues();
    }

    @GetMapping("/reconciliation")
    public ReconciliationHealthResponse getReconciliationHealth() {
        log.debug("Reconciliation health report requested");
        return opsService.getReconciliationHealth();
    }
}
