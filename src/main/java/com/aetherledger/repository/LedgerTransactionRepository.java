package com.aetherledger.repository;

import com.aetherledger.domain.entity.LedgerTransaction;
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
     * Eager JOIN FETCH to avoid N+1 when the caller needs both the
     * transaction and all of its ledger entries in a single round-trip.
     */
    @Query("""
        SELECT DISTINCT t
        FROM LedgerTransaction t
        LEFT JOIN FETCH t.ledgerEntries
        WHERE t.id = :id
        """)
    Optional<LedgerTransaction> findByIdWithEntries(@Param("id") UUID id);
}
