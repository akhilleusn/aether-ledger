package com.aetherledger.service;

import com.aetherledger.domain.entity.Account;
import com.aetherledger.domain.entity.LedgerEntry;
import com.aetherledger.domain.entity.LedgerTransaction;
import com.aetherledger.exception.AccountNotFoundException;
import com.aetherledger.exception.DuplicateReferenceIdException;
import com.aetherledger.exception.InvalidTransactionRequestException;
import com.aetherledger.repository.AccountRepository;
import com.aetherledger.repository.LedgerTransactionRepository;
import com.aetherledger.service.command.PostTransactionCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Core service for posting double-entry ledger transactions.
 *
 * <p>Every call to {@link #post} is atomic: either both ledger entries are
 * committed together with the transaction record, or nothing is written.
 *
 * <h3>Concurrency model</h3>
 * <ul>
 *   <li>Both accounts are locked with {@code SELECT ... FOR UPDATE} before any
 *       write, preventing phantom reads under concurrent load.</li>
 *   <li>Locks are always acquired in ascending UUID order to eliminate the
 *       deadlock that arises when two concurrent requests try to lock the same
 *       pair of accounts in opposite order.</li>
 *   <li>{@link LedgerTransaction} carries a {@code @Version} column; a
 *       concurrent status transition on the same record surfaces as
 *       {@code OptimisticLockException} rather than a silent overwrite.</li>
 * </ul>
 *
 * <h3>Idempotency model</h3>
 * The {@code referenceId} field carries an application-level unique constraint.
 * The service performs a pre-flight existence check as an early-return fast path,
 * then catches {@link DataIntegrityViolationException} as a second line of defence
 * against the race window between the check and the INSERT.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerTransactionService {

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository ledgerTransactionRepository;

    /**
     * Posts a double-entry ledger transaction atomically.
     *
     * <p>Produces exactly one {@link LedgerTransaction} and exactly two
     * {@link LedgerEntry} records: one debit (negative) on the source account
     * and one credit (positive) on the destination account.  Their amounts
     * always sum to zero.
     *
     * @param command the validated posting request
     * @return the persisted {@link LedgerTransaction} in {@code SUCCESS} state
     * @throws InvalidTransactionRequestException if the input is semantically invalid
     * @throws AccountNotFoundException           if either account does not exist
     * @throws DuplicateReferenceIdException      if the referenceId has already been used
     */
    @Transactional(rollbackFor = Exception.class)
    public LedgerTransaction post(PostTransactionCommand command) {
        validateCommand(command);
        rejectDuplicateReferenceId(command.referenceId());

        LockedAccounts accounts = lockAccountsInConsistentOrder(
            command.debitAccountId(),
            command.creditAccountId()
        );

        LedgerTransaction ledgerTx = LedgerTransaction.create(command.referenceId());

        // LedgerEntry factories normalise the amount to scale=4, wire both sides
        // of the bidirectional link, and guard against wrong-sign values.
        LedgerEntry.debit(accounts.debit(), ledgerTx, command.amount().negate());
        LedgerEntry.credit(accounts.credit(), ledgerTx, command.amount());

        assertDoubleEntryInvariant(ledgerTx);

        // Transition to SUCCESS before the INSERT so the record is never
        // visible to other sessions in a transient PENDING state.
        // If the save fails for any reason the whole @Transactional rolls back.
        ledgerTx.markAsSuccess();

        try {
            ledgerTx = ledgerTransactionRepository.save(ledgerTx);
        } catch (DataIntegrityViolationException ex) {
            // Guards the race window between the existence check above and the
            // INSERT — the UNIQUE constraint is the final enforcement barrier.
            log.warn("Duplicate referenceId detected at INSERT: {}", command.referenceId());
            throw new DuplicateReferenceIdException(command.referenceId());
        }

        log.info("Ledger transaction posted: id={} referenceId={} debit={} credit={} amount={}",
            ledgerTx.getId(),
            ledgerTx.getReferenceId(),
            command.debitAccountId(),
            command.creditAccountId(),
            command.amount()
        );

        return ledgerTx;
    }

    // -------------------------------------------------------------------------
    // Input validation
    // -------------------------------------------------------------------------

    private void validateCommand(PostTransactionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(command.debitAccountId(),  "debitAccountId must not be null");
        Objects.requireNonNull(command.creditAccountId(), "creditAccountId must not be null");
        Objects.requireNonNull(command.amount(),          "amount must not be null");
        Objects.requireNonNull(command.referenceId(),     "referenceId must not be null");

        if (command.referenceId().isBlank()) {
            throw new InvalidTransactionRequestException("referenceId must not be blank");
        }

        if (command.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidTransactionRequestException(
                "amount must be strictly positive, received: " + command.amount()
            );
        }

        if (command.debitAccountId().equals(command.creditAccountId())) {
            throw new InvalidTransactionRequestException(
                "debitAccountId and creditAccountId must refer to different accounts"
            );
        }
    }

    private void rejectDuplicateReferenceId(String referenceId) {
        if (ledgerTransactionRepository.existsByReferenceId(referenceId)) {
            throw new DuplicateReferenceIdException(referenceId);
        }
    }

    // -------------------------------------------------------------------------
    // Account locking
    // -------------------------------------------------------------------------

    /**
     * Acquires pessimistic write locks on both accounts in a globally consistent
     * order (ascending UUID) to prevent the AB / BA deadlock pattern.
     *
     * <p>Without ordering: if request-1 locks account-A then waits for account-B,
     * while request-2 locks account-B then waits for account-A, both requests
     * wait forever.  Locking the smaller UUID first eliminates the cycle.
     */
    private LockedAccounts lockAccountsInConsistentOrder(UUID debitId, UUID creditId) {
        if (debitId.compareTo(creditId) < 0) {
            Account debit  = requireAccountWithLock(debitId,  "debitAccountId");
            Account credit = requireAccountWithLock(creditId, "creditAccountId");
            return new LockedAccounts(debit, credit);
        } else {
            Account credit = requireAccountWithLock(creditId, "creditAccountId");
            Account debit  = requireAccountWithLock(debitId,  "debitAccountId");
            return new LockedAccounts(debit, credit);
        }
    }

    private Account requireAccountWithLock(UUID accountId, String field) {
        return accountRepository.findByIdWithLock(accountId)
            .orElseThrow(() -> new AccountNotFoundException(accountId, field));
    }

    // -------------------------------------------------------------------------
    // Invariant assertion
    // -------------------------------------------------------------------------

    /**
     * Verifies both the entry count (must be exactly 2) and the zero-sum rule
     * before anything is written to the database.
     *
     * <p>In a correctly wired system this method should never throw; it exists
     * as a hard stop against programming errors in the factory methods or future
     * refactors that break the invariant silently.
     */
    private void assertDoubleEntryInvariant(LedgerTransaction ledgerTx) {
        List<LedgerEntry> entries = ledgerTx.getLedgerEntries();

        if (entries.size() != 2) {
            throw new IllegalStateException(
                "Double-entry invariant violated for referenceId='"
                + ledgerTx.getReferenceId()
                + "': expected exactly 2 ledger entries, found " + entries.size()
            );
        }

        BigDecimal sum = entries.stream()
            .map(LedgerEntry::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (sum.compareTo(BigDecimal.ZERO) != 0) {
            throw new IllegalStateException(
                "Double-entry zero-sum invariant violated for referenceId='"
                + ledgerTx.getReferenceId()
                + "': entry amounts sum to " + sum + " (expected 0)"
            );
        }
    }

    // -------------------------------------------------------------------------
    // Internal types
    // -------------------------------------------------------------------------

    /**
     * Carries the two locked accounts after the lock-ordering step.
     * Using a named record instead of a plain array makes the call sites
     * self-documenting and prevents index mix-up errors.
     */
    private record LockedAccounts(Account debit, Account credit) {}
}
