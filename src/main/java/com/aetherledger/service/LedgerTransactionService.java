package com.aetherledger.service;

import com.aetherledger.domain.entity.Account;
import com.aetherledger.domain.entity.LedgerEntry;
import com.aetherledger.domain.entity.LedgerTransaction;
import com.aetherledger.exception.AccountNotFoundException;
import com.aetherledger.exception.DuplicateReferenceIdException;
import com.aetherledger.exception.InvalidTransactionRequestException;
import com.aetherledger.exception.LedgerTransactionNotFoundException;
import com.aetherledger.domain.enums.ExternalStatus;
import com.aetherledger.exception.TransactionAlreadyReversedException;
import com.aetherledger.exception.TransactionNotCompletableException;
import com.aetherledger.exception.TransactionNotFailableException;
import com.aetherledger.exception.TransactionNotReversibleException;
import com.aetherledger.domain.enums.TransactionStatus;
import com.aetherledger.repository.AccountRepository;
import com.aetherledger.repository.LedgerEntryRepository;
import com.aetherledger.repository.LedgerTransactionRepository;
import com.aetherledger.service.command.PostTransactionCommand;
import com.aetherledger.service.command.ReverseTransactionCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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
    private final LedgerEntryRepository ledgerEntryRepository;
    private final OutboxService outboxService;

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
        log.debug("Posting ledger transaction: referenceId='{}' debitAccountId={} creditAccountId={} amount={}",
            command.referenceId(), command.debitAccountId(), command.creditAccountId(), command.amount());

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
            // Guards the race window between the pre-flight check and the INSERT.
            // The idx_ledger_transaction_reference_id UNIQUE constraint is the
            // final enforcement barrier against concurrent duplicate submissions.
            log.warn("Duplicate referenceId='{}' detected at INSERT — concurrent request race",
                command.referenceId());
            throw new DuplicateReferenceIdException(command.referenceId());
        }

        log.info("Ledger transaction posted: id={} referenceId='{}' debitAccountId={} creditAccountId={} amount={}",
            ledgerTx.getId(),
            ledgerTx.getReferenceId(),
            command.debitAccountId(),
            command.creditAccountId(),
            command.amount()
        );

        outboxService.publishTransactionPosted(
            ledgerTx, command.debitAccountId(), command.creditAccountId(), command.amount());

        return ledgerTx;
    }

    // -------------------------------------------------------------------------
    // Pending lifecycle
    // -------------------------------------------------------------------------

    /**
     * Creates a {@link LedgerTransaction} in {@code PENDING} state without
     * creating any ledger entries.  The debit/credit intent is captured on the
     * transaction so it can be materialised later by {@link #complete}.
     *
     * @throws InvalidTransactionRequestException if the command fails business validation
     * @throws AccountNotFoundException           if either account does not exist
     * @throws DuplicateReferenceIdException      if the referenceId has already been used
     */
    @Transactional(rollbackFor = Exception.class)
    public LedgerTransaction createPending(PostTransactionCommand command) {
        log.debug("Creating pending transaction: referenceId='{}' debitAccountId={} creditAccountId={} amount={}",
            command.referenceId(), command.debitAccountId(), command.creditAccountId(), command.amount());

        validateCommand(command);
        rejectDuplicateReferenceId(command.referenceId());

        // Validate accounts exist; no lock needed — no entries are created yet
        requireAccountExists(command.debitAccountId(),  "debitAccountId");
        requireAccountExists(command.creditAccountId(), "creditAccountId");

        LedgerTransaction tx = LedgerTransaction.createPending(
            command.referenceId(),
            command.debitAccountId(),
            command.creditAccountId(),
            command.amount()
        );

        try {
            tx = ledgerTransactionRepository.save(tx);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Duplicate referenceId='{}' detected at INSERT (pending) — concurrent request race",
                command.referenceId());
            throw new DuplicateReferenceIdException(command.referenceId());
        }

        log.info("Pending transaction created: id={} referenceId='{}' debitAccountId={} creditAccountId={} amount={}",
            tx.getId(), tx.getReferenceId(),
            command.debitAccountId(), command.creditAccountId(), command.amount());

        return tx;
    }

    /**
     * Transitions a {@code PENDING} transaction to {@code SUCCESS} and creates
     * the two {@link LedgerEntry} records that affect account balances.
     *
     * @throws LedgerTransactionNotFoundException if the transaction does not exist
     * @throws TransactionNotCompletableException if the transaction is not PENDING
     */
    @Transactional(rollbackFor = Exception.class)
    public LedgerTransaction complete(UUID transactionId) {
        log.debug("Completing pending transaction: id={}", transactionId);

        LedgerTransaction tx = ledgerTransactionRepository.findByIdWithLock(transactionId)
            .orElseThrow(() -> new LedgerTransactionNotFoundException("id", transactionId.toString()));

        if (tx.getStatus() != TransactionStatus.PENDING) {
            throw new TransactionNotCompletableException(transactionId, tx.getStatus());
        }

        LockedAccounts accounts = lockAccountsInConsistentOrder(
            tx.getDebitAccountId(), tx.getCreditAccountId());

        LedgerEntry.debit(accounts.debit(),   tx, tx.getAmount().negate());
        LedgerEntry.credit(accounts.credit(), tx, tx.getAmount());

        assertDoubleEntryInvariant(tx);
        tx.markAsSuccess();
        tx = ledgerTransactionRepository.save(tx);

        log.info("Pending transaction completed: id={} referenceId='{}' amount={}",
            tx.getId(), tx.getReferenceId(), tx.getAmount());

        outboxService.publishTransactionCompleted(tx);

        return tx;
    }

    /**
     * Transitions a {@code PENDING} transaction to {@code FAILED}.
     * No ledger entries are created; the ledger remains unaffected.
     *
     * @throws LedgerTransactionNotFoundException if the transaction does not exist
     * @throws TransactionNotFailableException    if the transaction is not PENDING
     */
    @Transactional(rollbackFor = Exception.class)
    public LedgerTransaction fail(UUID transactionId) {
        log.debug("Failing pending transaction: id={}", transactionId);

        LedgerTransaction tx = ledgerTransactionRepository.findByIdWithLock(transactionId)
            .orElseThrow(() -> new LedgerTransactionNotFoundException("id", transactionId.toString()));

        if (tx.getStatus() != TransactionStatus.PENDING) {
            throw new TransactionNotFailableException(transactionId, tx.getStatus());
        }

        tx.markAsFailed();
        ledgerTransactionRepository.save(tx);

        log.info("Pending transaction failed: id={} referenceId='{}'",
            tx.getId(), tx.getReferenceId());

        return ledgerTransactionRepository.findByIdWithEntries(transactionId).orElseThrow(
            () -> new LedgerTransactionNotFoundException("id", transactionId.toString()));
    }

    // -------------------------------------------------------------------------
    // Reversal
    // -------------------------------------------------------------------------

    /**
     * Reverses a previously posted transaction by creating a compensating
     * {@link LedgerTransaction} with two mirror-image {@link LedgerEntry} rows:
     * the original debit account is credited and the original credit account is
     * debited by the same absolute amount.
     *
     * <p>Both the original and the new reversal transaction are saved atomically.
     * The original gains a {@code reversedByTransactionId} linkage; the reversal
     * carries a {@code reversalOfTransactionId} linkage for full audit traceability.
     *
     * @param command the validated reversal request
     * @return the persisted reversal {@link LedgerTransaction} in {@code SUCCESS} state
     * @throws LedgerTransactionNotFoundException  if the original transaction does not exist
     * @throws TransactionNotReversibleException   if the original is not in {@code SUCCESS} state
     * @throws TransactionAlreadyReversedException if the original has already been reversed
     * @throws DuplicateReferenceIdException       if the reversal referenceId has already been used
     */
    @Transactional(rollbackFor = Exception.class)
    public LedgerTransaction reverse(ReverseTransactionCommand command) {
        log.debug("Reversing transaction: originalId={} reversalReferenceId='{}'",
            command.originalTransactionId(), command.reversalReferenceId());

        rejectDuplicateReferenceId(command.reversalReferenceId());

        // Lock the original transaction row to guard against concurrent reversal attempts.
        LedgerTransaction original = ledgerTransactionRepository
            .findByIdWithLock(command.originalTransactionId())
            .orElseThrow(() -> new LedgerTransactionNotFoundException(
                "id", command.originalTransactionId().toString()));

        if (original.getStatus() != TransactionStatus.SUCCESS) {
            throw new TransactionNotReversibleException(original.getId(), original.getStatus());
        }
        if (original.getReversedByTransactionId() != null) {
            throw new TransactionAlreadyReversedException(original.getId());
        }

        // Fetch the original entries together with their accounts (no N+1).
        List<LedgerEntry> originalEntries = ledgerEntryRepository
            .findByLedgerTransactionIdWithAccount(original.getId());

        LedgerEntry originalDebit  = originalEntries.stream()
            .filter(LedgerEntry::isDebit)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No debit entry found for transaction " + original.getId()));
        LedgerEntry originalCredit = originalEntries.stream()
            .filter(LedgerEntry::isCredit)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No credit entry found for transaction " + original.getId()));

        // Lock accounts in consistent UUID order (same pattern as post()).
        Account debitAccount  = originalDebit.getAccount();
        Account creditAccount = originalCredit.getAccount();
        lockAccountsInConsistentOrder(debitAccount.getId(), creditAccount.getId());

        // Reversal flips the direction: original debit account is credited,
        // original credit account is debited.
        BigDecimal positiveAmount = originalCredit.getAmount(); // always > 0

        LedgerTransaction reversal = LedgerTransaction.createReversal(
            command.reversalReferenceId(), original.getId());

        LedgerEntry.debit(creditAccount, reversal, positiveAmount.negate());
        LedgerEntry.credit(debitAccount, reversal, positiveAmount);

        assertDoubleEntryInvariant(reversal);
        reversal.markAsSuccess();

        try {
            reversal = ledgerTransactionRepository.save(reversal);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Duplicate referenceId='{}' detected at INSERT during reversal — concurrent request race",
                command.reversalReferenceId());
            throw new DuplicateReferenceIdException(command.reversalReferenceId());
        }

        // Atomically link the original to its reversal for audit.
        original.markAsReversedBy(reversal.getId());
        ledgerTransactionRepository.save(original);

        log.info("Transaction reversed: originalId={} reversalId={} reversalReferenceId='{}'",
            original.getId(), reversal.getId(), reversal.getReferenceId());

        outboxService.publishTransactionReversed(reversal);

        return reversal;
    }

    // -------------------------------------------------------------------------
    // Reconciliation
    // -------------------------------------------------------------------------

    /**
     * Records the external provider's view of a transaction and derives its
     * reconciliation result.  Calling this method a second time overwrites the
     * previous values, enabling re-reconcile and correction workflows.
     *
     * @throws LedgerTransactionNotFoundException if the transaction does not exist
     */
    @Transactional(rollbackFor = Exception.class)
    public LedgerTransaction reconcile(UUID transactionId,
                                       String externalReferenceId,
                                       ExternalStatus externalStatus) {
        log.debug("Reconciling transaction: id={} externalReferenceId='{}' externalStatus={}",
            transactionId, externalReferenceId, externalStatus);

        LedgerTransaction tx = ledgerTransactionRepository.findById(transactionId)
            .orElseThrow(() -> new LedgerTransactionNotFoundException("id", transactionId.toString()));

        tx.reconcile(externalReferenceId, externalStatus);
        ledgerTransactionRepository.save(tx);

        log.info("Transaction reconciled: id={} externalReferenceId='{}' externalStatus={} result={}",
            transactionId, externalReferenceId, externalStatus,
            tx.computeReconciliationResult());

        tx = ledgerTransactionRepository.findByIdWithEntries(transactionId).orElseThrow(
            () -> new LedgerTransactionNotFoundException("id", transactionId.toString()));

        outboxService.publishTransactionReconciled(tx);

        return tx;
    }

    /**
     * Returns all transactions whose reconciliation result is not {@link
     * com.aetherledger.domain.enums.ReconciliationResult#MATCHED}, ordered newest first.
     */
    @Transactional(readOnly = true)
    public List<LedgerTransaction> listReconciliationIssues() {
        return ledgerTransactionRepository.findReconciliationIssues();
    }

    // -------------------------------------------------------------------------
    // Read operations
    // -------------------------------------------------------------------------

    /**
     * Returns the full transaction detail including both ledger entries and
     * their associated account names.  Entries and accounts are fetched in a
     * single JOIN FETCH query — no N+1.
     */
    @Transactional(readOnly = true)
    public LedgerTransaction getById(UUID id) {
        return ledgerTransactionRepository.findByIdWithEntries(id)
            .orElseThrow(() -> new LedgerTransactionNotFoundException("id", id.toString()));
    }

    /**
     * Same as {@link #getById} but keyed on the caller-supplied idempotency token.
     */
    @Transactional(readOnly = true)
    public LedgerTransaction getByReferenceId(String referenceId) {
        return ledgerTransactionRepository.findByReferenceIdWithEntries(referenceId)
            .orElseThrow(() -> new LedgerTransactionNotFoundException("referenceId", referenceId));
    }

    /**
     * Returns a page of transactions ordered by the caller-supplied {@link Pageable}.
     * Callers must set the sort direction; this method does not impose an ordering.
     */
    @Transactional(readOnly = true)
    public Page<LedgerTransaction> list(Pageable pageable) {
        return ledgerTransactionRepository.findAll(pageable);
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

    private void requireAccountExists(UUID accountId, String field) {
        if (!accountRepository.existsById(accountId)) {
            throw new AccountNotFoundException(accountId, field);
        }
    }

    private void rejectDuplicateReferenceId(String referenceId) {
        if (ledgerTransactionRepository.existsByReferenceId(referenceId)) {
            log.warn("Rejected duplicate referenceId='{}' — transaction already committed", referenceId);
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
