package com.aetherledger.repository;

import com.aetherledger.domain.entity.LedgerTransaction;
import com.aetherledger.domain.enums.ExternalStatus;
import com.aetherledger.domain.enums.TransactionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

    /**
     * Idempotency lookup — used before creating a new transaction to detect
     * a duplicate caller-supplied {@code referenceId}.
     */
    Optional<LedgerTransaction> findByReferenceId(String referenceId);

    boolean existsByReferenceId(String referenceId);

    List<LedgerTransaction> findByStatus(TransactionStatus status);

    /**
     * Acquires a pessimistic write lock before a status transition.
     * Prevents two concurrent threads from both reading PENDING and
     * independently racing toward SUCCESS or FAILED on the same record.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM LedgerTransaction t WHERE t.id = :id")
    Optional<LedgerTransaction> findByIdWithLock(@Param("id") UUID id);

    /**
     * Returns SUCCESS transactions that have not yet been reconciled and are
     * not currently held by another scheduler instance.
     *
     * <p>Ordered oldest-first so the job works through the backlog in
     * chronological order.  Capped at 500 to bound each run's work unit.
     */
    @Query("""
        SELECT t FROM LedgerTransaction t
        WHERE t.status = com.aetherledger.domain.enums.TransactionStatus.SUCCESS
          AND t.reconciledAt IS NULL
          AND t.processing = false
        ORDER BY t.createdAt ASC
        LIMIT 500
        """)
    List<LedgerTransaction> findUnreconciled();

    /**
     * Fetches a transaction with its two entries and each entry's account in
     * a single query, avoiding N+1 round-trips when the caller needs the full
     * audit detail (entry amounts, account names).
     */
    @Query("""
        SELECT DISTINCT t
        FROM LedgerTransaction t
        LEFT JOIN FETCH t.ledgerEntries e
        LEFT JOIN FETCH e.account
        WHERE t.id = :id
        """)
    Optional<LedgerTransaction> findByIdWithEntries(@Param("id") UUID id);

    // -------------------------------------------------------------------------
    // Reconciliation aggregate queries — used by the summary endpoint
    // -------------------------------------------------------------------------

    /** Transactions that have never been reconciled ({@code reconciledAt} is null). */
    @Query("SELECT COUNT(t) FROM LedgerTransaction t WHERE t.reconciledAt IS NULL")
    long countNotReconciled();

    /**
     * Transactions where a reconcile call was made but the external reference
     * or external status is absent, yielding {@code MISSING_EXTERNAL_REFERENCE}.
     */
    @Query("""
        SELECT COUNT(t) FROM LedgerTransaction t
        WHERE t.reconciledAt IS NOT NULL
        AND (t.externalReferenceId IS NULL OR t.externalStatus IS NULL)
        """)
    long countMissingExternalReference();

    /**
     * Transactions where internal and external statuses align, yielding {@code MATCHED}.
     * Mirrors the logic in {@link com.aetherledger.domain.entity.LedgerTransaction#computeReconciliationResult()}.
     */
    @Query("""
        SELECT COUNT(t) FROM LedgerTransaction t
        WHERE t.reconciledAt IS NOT NULL
        AND t.externalReferenceId IS NOT NULL
        AND t.externalStatus IS NOT NULL
        AND (
            (t.status = com.aetherledger.domain.enums.TransactionStatus.SUCCESS
             AND t.externalStatus = com.aetherledger.domain.enums.ExternalStatus.SUCCESS)
            OR (t.status = com.aetherledger.domain.enums.TransactionStatus.FAILED
                AND t.externalStatus = com.aetherledger.domain.enums.ExternalStatus.FAILED)
            OR (t.status = com.aetherledger.domain.enums.TransactionStatus.PENDING
                AND t.externalStatus = com.aetherledger.domain.enums.ExternalStatus.PENDING)
        )
        """)
    long countMatched();

    /**
     * Returns every transaction whose reconciliation result is not {@code MATCHED}.
     *
     * <p>A transaction is considered an "issue" when any of the following is true:
     * <ul>
     *   <li>{@code reconciledAt} is null (never reconciled)</li>
     *   <li>{@code externalReferenceId} is null (reference missing)</li>
     *   <li>{@code externalStatus} is null (status missing)</li>
     *   <li>Internal and external statuses disagree (STATUS_MISMATCH)</li>
     * </ul>
     *
     * Ordered newest first to surface recent discrepancies at the top.
     */
    @Query("""
        SELECT t FROM LedgerTransaction t
        WHERE t.reconciledAt IS NULL
           OR t.externalReferenceId IS NULL
           OR t.externalStatus IS NULL
           OR NOT (
               (t.status = com.aetherledger.domain.enums.TransactionStatus.SUCCESS
                AND t.externalStatus = com.aetherledger.domain.enums.ExternalStatus.SUCCESS)
               OR (t.status = com.aetherledger.domain.enums.TransactionStatus.FAILED
                   AND t.externalStatus = com.aetherledger.domain.enums.ExternalStatus.FAILED)
               OR (t.status = com.aetherledger.domain.enums.TransactionStatus.PENDING
                   AND t.externalStatus = com.aetherledger.domain.enums.ExternalStatus.PENDING)
           )
        ORDER BY t.createdAt DESC
        """)
    List<LedgerTransaction> findReconciliationIssues();

    /**
     * Same as {@link #findByIdWithEntries} but keyed on the caller-supplied
     * idempotency token rather than the system-assigned UUID.
     */
    @Query("""
        SELECT DISTINCT t
        FROM LedgerTransaction t
        LEFT JOIN FETCH t.ledgerEntries e
        LEFT JOIN FETCH e.account
        WHERE t.referenceId = :referenceId
        """)
    Optional<LedgerTransaction> findByReferenceIdWithEntries(@Param("referenceId") String referenceId);

    // -------------------------------------------------------------------------
    // Integrity aggregate queries — used by the ops/integrity endpoints
    // -------------------------------------------------------------------------

    /** Count of transactions in a given status — used for the ledger integrity summary. */
    long countByStatus(TransactionStatus status);

    /** Count of transactions that have been reversed (reversedByTransactionId is set). */
    @Query("SELECT COUNT(t) FROM LedgerTransaction t WHERE t.reversedByTransactionId IS NOT NULL")
    long countReversed();

    /**
     * Count of SUCCESS transactions whose ledger entries do not sum to zero.
     * Only considers transactions that actually have entries (SUM IS NOT NULL);
     * a SUCCESS transaction with zero entries is caught by {@link #countUnexpectedEntryCount}.
     */
    @Query("""
        SELECT COUNT(t) FROM LedgerTransaction t
        WHERE t.status = com.aetherledger.domain.enums.TransactionStatus.SUCCESS
        AND (SELECT SUM(e.amount) FROM LedgerEntry e WHERE e.ledgerTransaction.id = t.id)
              IS NOT NULL
        AND (SELECT SUM(e.amount) FROM LedgerEntry e WHERE e.ledgerTransaction.id = t.id) <> 0
        """)
    long countZeroSumViolations();

    /**
     * Count of transactions with an entry count inconsistent with their status:
     * SUCCESS must have exactly 2; PENDING and FAILED must have 0.
     */
    @Query("""
        SELECT COUNT(t) FROM LedgerTransaction t
        WHERE (t.status = com.aetherledger.domain.enums.TransactionStatus.SUCCESS
               AND (SELECT COUNT(e) FROM LedgerEntry e WHERE e.ledgerTransaction.id = t.id) <> 2)
           OR ((t.status = com.aetherledger.domain.enums.TransactionStatus.PENDING
                OR t.status = com.aetherledger.domain.enums.TransactionStatus.FAILED)
               AND (SELECT COUNT(e) FROM LedgerEntry e WHERE e.ledgerTransaction.id = t.id) > 0)
        """)
    long countUnexpectedEntryCount();

    /**
     * Returns every transaction that has at least one integrity violation, together
     * with its entry count and entry sum so the service can classify issue types
     * without additional round-trips.
     *
     * <p>Each element of the returned list is an {@code Object[3]}:
     * <ol>
     *   <li>[0] — {@link com.aetherledger.domain.entity.LedgerTransaction}</li>
     *   <li>[1] — {@code Long} entry count</li>
     *   <li>[2] — {@code BigDecimal} entry sum, or {@code null} when the transaction has no entries</li>
     * </ol>
     */
    @Query("""
        SELECT t,
               (SELECT COUNT(e) FROM LedgerEntry e WHERE e.ledgerTransaction.id = t.id),
               (SELECT SUM(e.amount) FROM LedgerEntry e WHERE e.ledgerTransaction.id = t.id)
        FROM LedgerTransaction t
        WHERE (t.status = com.aetherledger.domain.enums.TransactionStatus.SUCCESS
               AND (SELECT COUNT(e) FROM LedgerEntry e WHERE e.ledgerTransaction.id = t.id) <> 2)
           OR (t.status = com.aetherledger.domain.enums.TransactionStatus.SUCCESS
               AND (SELECT SUM(e.amount) FROM LedgerEntry e WHERE e.ledgerTransaction.id = t.id)
                     IS NOT NULL
               AND (SELECT SUM(e.amount) FROM LedgerEntry e WHERE e.ledgerTransaction.id = t.id) <> 0)
           OR ((t.status = com.aetherledger.domain.enums.TransactionStatus.PENDING
                OR t.status = com.aetherledger.domain.enums.TransactionStatus.FAILED)
               AND (SELECT COUNT(e) FROM LedgerEntry e WHERE e.ledgerTransaction.id = t.id) > 0)
        ORDER BY t.createdAt DESC
        """)
    List<Object[]> findIntegrityIssues();
}
