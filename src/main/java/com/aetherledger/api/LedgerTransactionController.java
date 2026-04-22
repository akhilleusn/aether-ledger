package com.aetherledger.api;

import com.aetherledger.api.dto.PageResponse;
import com.aetherledger.api.dto.PostTransactionRequest;
import com.aetherledger.api.dto.ReconcileRequest;
import com.aetherledger.api.dto.ReversalRequest;
import com.aetherledger.api.dto.TransactionDetailResponse;
import com.aetherledger.api.dto.TransactionResponse;
import com.aetherledger.api.dto.TransactionSummaryResponse;
import com.aetherledger.service.LedgerTransactionService;
import com.aetherledger.service.command.PostTransactionCommand;
import com.aetherledger.service.command.ReverseTransactionCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for ledger transaction operations.
 *
 * <p>Intentionally thin: translates HTTP concerns into domain calls and back.
 * All business logic lives in {@link LedgerTransactionService}.
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/ledger-transactions")
@RequiredArgsConstructor
public class LedgerTransactionController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE     = 100;

    private final LedgerTransactionService ledgerTransactionService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse post(@RequestBody @Valid PostTransactionRequest request) {
        log.debug("Posting ledger transaction: referenceId={}", request.referenceId());

        PostTransactionCommand command = new PostTransactionCommand(
            request.debitAccountId(),
            request.creditAccountId(),
            request.amount(),
            request.referenceId()
        );

        return TransactionResponse.from(ledgerTransactionService.post(command));
    }

    @GetMapping("/{id}")
    public TransactionDetailResponse getById(@PathVariable UUID id) {
        return TransactionDetailResponse.from(ledgerTransactionService.getById(id));
    }

    @GetMapping("/by-reference/{referenceId}")
    public TransactionDetailResponse getByReferenceId(@PathVariable String referenceId) {
        return TransactionDetailResponse.from(ledgerTransactionService.getByReferenceId(referenceId));
    }

    @PostMapping("/pending")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse createPending(@RequestBody @Valid PostTransactionRequest request) {
        log.debug("Creating pending transaction: referenceId={}", request.referenceId());

        PostTransactionCommand command = new PostTransactionCommand(
            request.debitAccountId(),
            request.creditAccountId(),
            request.amount(),
            request.referenceId()
        );

        return TransactionResponse.from(ledgerTransactionService.createPending(command));
    }

    @PostMapping("/{id}/complete")
    public TransactionDetailResponse complete(@PathVariable UUID id) {
        log.debug("Completing pending transaction: id={}", id);
        return TransactionDetailResponse.from(ledgerTransactionService.complete(id));
    }

    @PostMapping("/{id}/fail")
    public TransactionDetailResponse fail(@PathVariable UUID id) {
        log.debug("Failing pending transaction: id={}", id);
        return TransactionDetailResponse.from(ledgerTransactionService.fail(id));
    }

    @PostMapping("/{id}/reversal")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionDetailResponse reverse(
            @PathVariable UUID id,
            @RequestBody @Valid ReversalRequest request) {
        log.debug("Reversing ledger transaction: originalId={} reversalReferenceId='{}'",
            id, request.referenceId());

        ReverseTransactionCommand command = new ReverseTransactionCommand(id, request.referenceId());
        return TransactionDetailResponse.from(ledgerTransactionService.reverse(command));
    }

    @PostMapping("/{id}/reconcile")
    public TransactionDetailResponse reconcile(
            @PathVariable UUID id,
            @RequestBody @Valid ReconcileRequest request) {
        log.debug("Reconciling transaction: id={} externalStatus={}", id, request.externalStatus());
        return TransactionDetailResponse.from(
            ledgerTransactionService.reconcile(id, request.externalReferenceId(), request.externalStatus()));
    }

    @GetMapping("/reconciliation/issues")
    public List<TransactionSummaryResponse> listReconciliationIssues() {
        return ledgerTransactionService.listReconciliationIssues().stream()
            .map(TransactionSummaryResponse::from)
            .toList();
    }

    @GetMapping
    public PageResponse<TransactionSummaryResponse> list(
            @RequestParam(defaultValue = "0")                    @Min(0)                    int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.from(ledgerTransactionService.list(pageable), TransactionSummaryResponse::from);
    }
}
