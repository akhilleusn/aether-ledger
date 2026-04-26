package com.aetherledger.service;

import com.aetherledger.domain.entity.Account;
import com.aetherledger.domain.entity.Hold;
import com.aetherledger.domain.entity.LedgerTransaction;
import com.aetherledger.domain.enums.HoldStatus;
import com.aetherledger.exception.AccountNotFoundException;
import com.aetherledger.exception.DuplicateHoldReferenceIdException;
import com.aetherledger.exception.HoldNotCapturableException;
import com.aetherledger.exception.HoldNotFoundException;
import com.aetherledger.exception.HoldNotReleasableException;
import com.aetherledger.repository.AccountRepository;
import com.aetherledger.repository.HoldRepository;
import com.aetherledger.service.command.PostTransactionCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Manages the full lifecycle of balance holds.
 *
 * <h3>Create</h3>
 * Reserves funds on an account by writing a {@link Hold} row. No ledger entries
 * are written at this point — only {@code availableBalance} shrinks.
 *
 * <h3>Capture</h3>
 * Transitions the hold to {@code CAPTURED} and posts a real double-entry
 * ledger transaction that moves the reserved amount from the account to the
 * "Settlement Clearing" system account.
 *
 * <h3>Release</h3>
 * Transitions the hold to {@code RELEASED}, cancelling the reservation without
 * any ledger movement.
 *
 * <p>Concurrent capture / release is guarded by a {@code SELECT FOR UPDATE}
 * pessimistic lock on the {@link Hold} row.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HoldService {

    private final HoldRepository holdRepository;
    private final AccountRepository accountRepository;
    private final LedgerTransactionService ledgerTransactionService;
    private final ClearingAccountProvider clearingAccountProvider;
    private final OutboxService outboxService;
    private final LedgerMetrics ledgerMetrics;

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public Hold create(UUID accountId, BigDecimal amount, String referenceId) {
        if (!accountRepository.existsById(accountId)) {
            throw new AccountNotFoundException(accountId, "id");
        }
        if (holdRepository.existsByReferenceId(referenceId)) {
            throw new DuplicateHoldReferenceIdException(referenceId);
        }

        Hold hold;
        try {
            hold = holdRepository.save(Hold.create(accountId, amount, referenceId));
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateHoldReferenceIdException(referenceId);
        }

        log.info("Hold created: id={} accountId={} amount={} referenceId='{}'",
            hold.getId(), accountId, amount, referenceId);
        outboxService.publishHoldCreated(hold);
        ledgerMetrics.holdCreated();
        return hold;
    }

    // -------------------------------------------------------------------------
    // Get
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Hold getById(UUID holdId) {
        return holdRepository.findById(holdId)
            .orElseThrow(() -> new HoldNotFoundException(holdId));
    }

    // -------------------------------------------------------------------------
    // Capture
    // -------------------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public Hold capture(UUID holdId) {
        Hold hold = holdRepository.findByIdWithLock(holdId)
            .orElseThrow(() -> new HoldNotFoundException(holdId));

        if (hold.getStatus() != HoldStatus.ACTIVE) {
            throw new HoldNotCapturableException(holdId, hold.getStatus());
        }

        hold.capture();

        Account clearingAccount = clearingAccountProvider.resolve();

        PostTransactionCommand command = new PostTransactionCommand(
            hold.getAccountId(),
            clearingAccount.getId(),
            hold.getAmount(),
            "HOLD-CAPTURE-" + hold.getId()
        );
        LedgerTransaction ledgerTx = ledgerTransactionService.post(command);

        log.info("Hold captured: holdId={} ledgerTxId={}", hold.getId(), ledgerTx.getId());
        outboxService.publishHoldCaptured(hold);
        ledgerMetrics.holdCaptured();
        return hold;
    }

    // -------------------------------------------------------------------------
    // Release
    // -------------------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public Hold release(UUID holdId) {
        Hold hold = holdRepository.findByIdWithLock(holdId)
            .orElseThrow(() -> new HoldNotFoundException(holdId));

        if (hold.getStatus() != HoldStatus.ACTIVE) {
            throw new HoldNotReleasableException(holdId, hold.getStatus());
        }

        hold.release();

        log.info("Hold released: holdId={}", hold.getId());
        outboxService.publishHoldReleased(hold);
        ledgerMetrics.holdReleased();
        return hold;
    }
}
