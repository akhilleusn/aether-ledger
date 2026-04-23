package com.aetherledger.api;

import com.aetherledger.api.dto.BatchReconcileRequest;
import com.aetherledger.api.dto.BatchReconcileResponse;
import com.aetherledger.api.dto.ErrorResponse;
import com.aetherledger.api.dto.ReconciliationRunResponse;
import com.aetherledger.api.dto.ReconciliationSummaryResponse;
import com.aetherledger.service.ReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for batch reconciliation operations.
 *
 * <p>Intentionally separate from {@link LedgerTransactionController}: reconciliation
 * is a cross-cutting concern that operates across many transactions at once and
 * deserves its own bounded context at the API layer.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/reconciliation")
@RequiredArgsConstructor
@Tag(name = "Reconciliation", description = "Batch-reconcile transactions against external payment provider records and audit past reconciliation runs.")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    @Operation(
        summary = "Run a batch reconciliation",
        description = "Matches up to 500 external provider records against internal ledger transactions by `referenceId`. " +
            "For each matched transaction, sets `externalReferenceId` and `externalStatus`, then computes the `reconciliationResult`. " +
            "The response includes per-item outcomes and aggregate counters. " +
            "Every call creates an immutable `ReconciliationRun` audit record retrievable via `GET /runs/{id}`."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Reconciliation run completed; check per-item results for individual outcomes"),
        @ApiResponse(responseCode = "400", description = "Request body fails validation (empty batch, item missing referenceId, etc.)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/batch")
    public BatchReconcileResponse batchReconcile(@RequestBody @Valid BatchReconcileRequest request) {
        log.debug("Batch reconcile request received: itemCount={}", request.items().size());
        return reconciliationService.batchReconcile(request.items());
    }

    @Operation(
        summary = "Get reconciliation summary",
        description = "Returns aggregate reconciliation statistics computed from the current state of all ledger transactions. " +
            "Counts are computed at query time — not cached — so they always reflect live data."
    )
    @ApiResponse(responseCode = "200", description = "Current reconciliation statistics")
    @GetMapping("/summary")
    public ReconciliationSummaryResponse summary() {
        return reconciliationService.getSummary();
    }

    @Operation(
        summary = "Get reconciliation run audit detail",
        description = "Returns the immutable audit record for a past reconciliation run, including all per-item outcomes. " +
            "The `runId` is included in every `POST /batch` response."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Run audit record found"),
        @ApiResponse(responseCode = "400", description = "Path variable is not a valid UUID",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Reconciliation run not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/runs/{id}")
    public ReconciliationRunResponse getRunById(
            @Parameter(description = "Reconciliation run UUID (returned by POST /batch)", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
            @PathVariable UUID id) {
        return ReconciliationRunResponse.from(reconciliationService.getRunById(id));
    }
}
