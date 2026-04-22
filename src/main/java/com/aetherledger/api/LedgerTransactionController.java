package com.aetherledger.api;

import com.aetherledger.api.dto.PostTransactionRequest;
import com.aetherledger.api.dto.TransactionResponse;
import com.aetherledger.domain.entity.LedgerTransaction;
import com.aetherledger.service.LedgerTransactionService;
import com.aetherledger.service.command.PostTransactionCommand;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for ledger transaction operations.
 *
 * <p>This controller is intentionally thin: it translates HTTP concerns
 * (request body, status codes, response shape) into domain calls and back.
 * All business logic lives in {@link LedgerTransactionService}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ledger-transactions")
@RequiredArgsConstructor
public class LedgerTransactionController {

    private final LedgerTransactionService ledgerTransactionService;

    /**
     * Posts a double-entry ledger transaction.
     *
     * <p>On success, exactly one {@code LedgerTransaction} and exactly two
     * {@code LedgerEntry} records are persisted atomically.  The response
     * body contains the system-assigned transaction id, the echoed reference id,
     * the final {@code SUCCESS} status, and the creation timestamp.
     *
     * @param request validated request body
     * @return {@code 201 Created} with a {@link TransactionResponse} body
     */
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

        LedgerTransaction tx = ledgerTransactionService.post(command);

        return TransactionResponse.from(tx);
    }
}
