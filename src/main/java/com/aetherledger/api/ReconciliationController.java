package com.aetherledger.api;

import com.aetherledger.api.dto.BatchReconcileRequest;
import com.aetherledger.api.dto.BatchReconcileResponse;
import com.aetherledger.api.dto.ReconciliationSummaryResponse;
import com.aetherledger.service.ReconciliationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

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
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    @PostMapping("/batch")
    public BatchReconcileResponse batchReconcile(@RequestBody @Valid BatchReconcileRequest request) {
        log.debug("Batch reconcile request received: itemCount={}", request.items().size());
        return reconciliationService.batchReconcile(request.items());
    }

    @GetMapping("/summary")
    public ReconciliationSummaryResponse summary() {
        return reconciliationService.getSummary();
    }
}
