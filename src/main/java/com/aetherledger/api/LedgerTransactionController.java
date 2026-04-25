package com.aetherledger.api;

import com.aetherledger.api.dto.AuditTimelineItemResponse;
import com.aetherledger.api.dto.ErrorResponse;
import com.aetherledger.api.dto.PageResponse;
import com.aetherledger.api.dto.PostTransactionRequest;
import com.aetherledger.api.dto.ReconcileRequest;
import com.aetherledger.api.dto.ReconciliationInsightResponse;
import com.aetherledger.api.dto.ReversalRequest;
import com.aetherledger.api.dto.TransactionDetailResponse;
import com.aetherledger.api.dto.TransactionResponse;
import com.aetherledger.api.dto.TransactionSummaryResponse;
import com.aetherledger.service.AuditTimelineService;
import com.aetherledger.service.LedgerTransactionService;
import com.aetherledger.service.ReconciliationInsightService;
import com.aetherledger.service.command.PostTransactionCommand;
import com.aetherledger.service.command.ReverseTransactionCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Ledger Transactions", description = "Post, query, and manage double-entry ledger transactions. " +
    "Each transaction moves value from a debit account to a credit account for an equal amount.")
public class LedgerTransactionController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE     = 100;

    private final LedgerTransactionService ledgerTransactionService;
    private final ReconciliationInsightService reconciliationInsightService;
    private final AuditTimelineService auditTimelineService;

    @Operation(
        summary = "Post a transaction (immediate)",
        description = "Creates and immediately settles a double-entry transaction. " +
            "Debits `debitAccountId` and credits `creditAccountId` by `amount`. " +
            "The resulting transaction has status `SUCCESS`. " +
            "`referenceId` is an idempotency key — submitting the same value twice returns 409."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Transaction posted and settled"),
        @ApiResponse(responseCode = "400", description = "Validation error or debitAccountId equals creditAccountId",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Debit or credit account not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "Duplicate referenceId",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
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

    @Operation(
        summary = "Get transaction by ID",
        description = "Returns full transaction detail including all ledger entries."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Transaction found"),
        @ApiResponse(responseCode = "400", description = "Path variable is not a valid UUID",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Transaction not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}")
    public TransactionDetailResponse getById(
            @Parameter(description = "Transaction UUID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
            @PathVariable UUID id) {
        return TransactionDetailResponse.from(ledgerTransactionService.getById(id));
    }

    @Operation(
        summary = "Get transaction by reference ID",
        description = "Looks up a transaction using the caller-supplied idempotency key. " +
            "Useful for deduplication checks after a timeout or network failure."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Transaction found"),
        @ApiResponse(responseCode = "404", description = "No transaction with this referenceId",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/by-reference/{referenceId}")
    public TransactionDetailResponse getByReferenceId(
            @Parameter(description = "Caller-supplied idempotency key", example = "PAY-2024-001")
            @PathVariable String referenceId) {
        return TransactionDetailResponse.from(ledgerTransactionService.getByReferenceId(referenceId));
    }

    @Operation(
        summary = "Create a pending transaction",
        description = "Creates a transaction in `PENDING` status. No ledger entries are written yet. " +
            "Call `POST /{id}/complete` to settle or `POST /{id}/fail` to reject it. " +
            "Use this for two-phase flows where external confirmation is required before funds move."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Pending transaction created"),
        @ApiResponse(responseCode = "400", description = "Validation error",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Debit or credit account not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "Duplicate referenceId",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
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

    @Operation(
        summary = "Complete a pending transaction",
        description = "Transitions a `PENDING` transaction to `SUCCESS` and writes the two ledger entries. " +
            "Fails with 422 if the transaction is not currently in `PENDING` status."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Transaction completed and ledger entries written"),
        @ApiResponse(responseCode = "400", description = "Path variable is not a valid UUID",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Transaction not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "422", description = "Transaction is not in PENDING status",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/complete")
    public TransactionDetailResponse complete(
            @Parameter(description = "Transaction UUID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
            @PathVariable UUID id) {
        log.debug("Completing pending transaction: id={}", id);
        return TransactionDetailResponse.from(ledgerTransactionService.complete(id));
    }

    @Operation(
        summary = "Fail a pending transaction",
        description = "Transitions a `PENDING` transaction to `FAILED`. No ledger entries are written. " +
            "Fails with 422 if the transaction is not currently in `PENDING` status."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Transaction marked as failed"),
        @ApiResponse(responseCode = "400", description = "Path variable is not a valid UUID",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Transaction not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "422", description = "Transaction is not in PENDING status",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/fail")
    public TransactionDetailResponse fail(
            @Parameter(description = "Transaction UUID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
            @PathVariable UUID id) {
        log.debug("Failing pending transaction: id={}", id);
        return TransactionDetailResponse.from(ledgerTransactionService.fail(id));
    }

    @Operation(
        summary = "Reverse a transaction",
        description = "Creates a new `SUCCESS` transaction that exactly offsets the original. " +
            "The reversal debits the original credit account and credits the original debit account. " +
            "Only `SUCCESS` transactions that have not already been reversed can be reversed. " +
            "`referenceId` in the body is the unique key for the new reversal transaction."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Reversal transaction created"),
        @ApiResponse(responseCode = "400", description = "Validation error or invalid UUID",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Original transaction not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "Transaction has already been reversed, or reversal referenceId is a duplicate",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "422", description = "Transaction is not in a reversible state (must be SUCCESS)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/reversal")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionDetailResponse reverse(
            @Parameter(description = "UUID of the transaction to reverse", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
            @PathVariable UUID id,
            @RequestBody @Valid ReversalRequest request) {
        log.debug("Reversing ledger transaction: originalId={} reversalReferenceId='{}'",
            id, request.referenceId());

        ReverseTransactionCommand command = new ReverseTransactionCommand(id, request.referenceId());
        return TransactionDetailResponse.from(ledgerTransactionService.reverse(command));
    }

    @Operation(
        summary = "Reconcile a transaction against an external record",
        description = "Records the outcome of matching this transaction against an external payment provider. " +
            "Sets `externalReferenceId` and `externalStatus` on the transaction. " +
            "The `reconciliationResult` field is then computed automatically from the internal and external statuses."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Reconciliation data recorded"),
        @ApiResponse(responseCode = "400", description = "Validation error or invalid UUID",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Transaction not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/reconcile")
    public TransactionDetailResponse reconcile(
            @Parameter(description = "Transaction UUID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
            @PathVariable UUID id,
            @RequestBody @Valid ReconcileRequest request) {
        log.debug("Reconciling transaction: id={} externalStatus={}", id, request.externalStatus());
        return TransactionDetailResponse.from(
            ledgerTransactionService.reconcile(id, request.externalReferenceId(), request.externalStatus()));
    }

    @Operation(
        summary = "Get audit timeline",
        description = "Returns a chronological list of events for a transaction, assembled from " +
            "existing tables: ledger_transactions, ledger_entries, and outbox_events. " +
            "Events include transaction creation, ledger entry writes, reversal links, " +
            "reconciliation, and outbox delivery history. Sorted by occurredAt ascending."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Timeline returned (may be empty for unknown states)"),
        @ApiResponse(responseCode = "400", description = "Path variable is not a valid UUID",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Transaction not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}/audit-timeline")
    public List<AuditTimelineItemResponse> getAuditTimeline(
            @Parameter(description = "Transaction UUID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
            @PathVariable UUID id) {
        return auditTimelineService.getTimeline(id);
    }

    @Operation(
        summary = "Get reconciliation insight",
        description = "Returns an operator-facing explanation of the transaction's reconciliation state. " +
            "For STATUS_MISMATCH transactions the response includes specific possible causes and recommended " +
            "investigative steps. The explanation engine is rule-based by default and is designed to be " +
            "swapped for an AI provider (e.g. Claude) without changes to callers."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Insight generated successfully"),
        @ApiResponse(responseCode = "400", description = "Path variable is not a valid UUID",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Transaction not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}/reconciliation-insight")
    public ReconciliationInsightResponse getReconciliationInsight(
            @Parameter(description = "Transaction UUID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
            @PathVariable UUID id) {
        return reconciliationInsightService.getInsight(id);
    }

    @Operation(
        summary = "List transactions with reconciliation issues",
        description = "Returns all SUCCESS transactions where the internal status and external status disagree, " +
            "or where reconciliation has not been attempted. Useful for triage and remediation workflows."
    )
    @ApiResponse(responseCode = "200", description = "List of transactions needing attention (may be empty)")
    @GetMapping("/reconciliation/issues")
    public List<TransactionSummaryResponse> listReconciliationIssues() {
        return ledgerTransactionService.listReconciliationIssues().stream()
            .map(TransactionSummaryResponse::from)
            .toList();
    }

    @Operation(
        summary = "List transactions (paginated)",
        description = "Returns a paginated list of all transactions ordered by creation time descending (newest first)."
    )
    @ApiResponse(responseCode = "200", description = "Page of transaction summaries")
    @GetMapping
    public PageResponse<TransactionSummaryResponse> list(
            @Parameter(description = "Zero-based page number", example = "0")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Number of items per page (1–100)", example = "20")
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.from(ledgerTransactionService.list(pageable), TransactionSummaryResponse::from);
    }
}
