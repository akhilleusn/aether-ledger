package com.aetherledger.repository;

import com.aetherledger.domain.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByAccountId(UUID accountId);

    List<LedgerEntry> findByLedgerTransactionId(UUID ledgerTransactionId);

    long countByLedgerTransactionId(UUID ledgerTransactionId);

    boolean existsByLedgerTransactionId(UUID ledgerTransactionId);

    /**
     * Computes the running balance of an account as the algebraic sum of all
     * its ledger entry amounts (credits positive, debits negative).
     * Returns {@code 0} when the account has no entries.
     *
     * <p>This queries the ledger directly and is always authoritative.
     * Never derive a balance from a cached column.
     */
    @Query("""
        SELECT COALESCE(SUM(le.amount), 0)
        FROM   LedgerEntry le
        WHERE  le.account.id = :accountId
        """)
    BigDecimal sumAmountByAccountId(@Param("accountId") UUID accountId);

    /**
     * Computes running balances for every account that has at least one ledger
     * entry, in a single round-trip.  Accounts with no entries are absent from
     * the result; callers should default those to {@link java.math.BigDecimal#ZERO}.
     *
     * <p>Used by the account-list endpoint to avoid N+1 balance queries.
     */
    @Query("""
        SELECT new com.aetherledger.repository.AccountBalanceSummary(le.account.id, SUM(le.amount))
        FROM   LedgerEntry le
        GROUP BY le.account.id
        """)
    List<AccountBalanceSummary> sumAmountGroupByAccountId();

    /**
     * Verifies the double-entry zero-sum invariant for a given transaction.
     * A healthy transaction returns exactly {@code 0}.  Any other value
     * indicates data corruption and must be treated as a critical error.
     */
    @Query("""
        SELECT COALESCE(SUM(le.amount), 0)
        FROM   LedgerEntry le
        WHERE  le.ledgerTransaction.id = :ledgerTransactionId
        """)
    BigDecimal sumAmountByLedgerTransactionId(@Param("ledgerTransactionId") UUID ledgerTransactionId);
}
