package com.aetherledger.api;

import com.aetherledger.api.dto.IntegritySnapshotResponse;
import com.aetherledger.api.dto.LedgerIntegrityReportResponse;
import com.aetherledger.api.dto.LedgerIssueResponse;
import com.aetherledger.api.dto.ReconciliationHealthResponse;
import com.aetherledger.service.OpsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Operational observability endpoints for ledger integrity and reconciliation health.
 *
 * <p>Live-check endpoints ({@code /ledger}, {@code /ledger/issues}, {@code /reconciliation})
 * are read-only and safe to call repeatedly without side effects.
 * Snapshot endpoints ({@code /snapshots}) persist an immutable point-in-time record of
 * the current integrity state.
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

    // -------------------------------------------------------------------------
    // Integrity snapshots
    // -------------------------------------------------------------------------

    @Operation(
        summary = "Create an integrity snapshot",
        description = "Captures the current ledger integrity state as an immutable, persisted snapshot. " +
            "`healthy` reflects whether the ledger is free of zero-sum violations and unexpected entry counts " +
            "at the moment of creation.  The snapshot is never mutated after creation."
    )
    @ApiResponse(responseCode = "201", description = "Snapshot created")
    @PostMapping("/snapshots")
    @ResponseStatus(HttpStatus.CREATED)
    public IntegritySnapshotResponse createSnapshot() {
        log.info("Integrity snapshot requested");
        return opsService.createIntegritySnapshot();
    }

    @Operation(
        summary = "List integrity snapshots",
        description = "Returns all persisted integrity snapshots, newest first."
    )
    @ApiResponse(responseCode = "200", description = "List of snapshots (may be empty)")
    @GetMapping("/snapshots")
    public List<IntegritySnapshotResponse> listSnapshots() {
        log.debug("Listing integrity snapshots");
        return opsService.listIntegritySnapshots();
    }

    @Operation(
        summary = "Get an integrity snapshot by ID",
        description = "Returns the persisted integrity snapshot with the given UUID."
    )
    @ApiResponse(responseCode = "200", description = "Snapshot found")
    @ApiResponse(responseCode = "404", description = "No snapshot with the given ID")
    @GetMapping("/snapshots/{id}")
    public IntegritySnapshotResponse getSnapshot(@PathVariable UUID id) {
        log.debug("Fetching integrity snapshot id={}", id);
        return opsService.getIntegritySnapshot(id);
    }
}
