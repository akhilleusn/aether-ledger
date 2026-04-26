package com.aetherledger.repository;

import com.aetherledger.domain.entity.Hold;
import com.aetherledger.domain.enums.HoldStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HoldRepository extends JpaRepository<Hold, UUID> {

    boolean existsByReferenceId(String referenceId);

    /**
     * Pessimistic write lock for capture / release transitions.
     * Prevents two concurrent requests from both reading ACTIVE status
     * and both attempting the state change.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM Hold h WHERE h.id = :id")
    Optional<Hold> findByIdWithLock(@Param("id") UUID id);

    /**
     * Sum of reserved amounts for an account in a given status.
     * Returns {@link BigDecimal#ZERO} when no matching holds exist.
     */
    @Query("""
        SELECT COALESCE(SUM(h.amount), 0)
        FROM Hold h
        WHERE h.accountId = :accountId
          AND h.status    = :status
        """)
    BigDecimal sumAmountByAccountIdAndStatus(
        @Param("accountId") UUID accountId,
        @Param("status")    HoldStatus status);
}
