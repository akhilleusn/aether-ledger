package com.aetherledger.repository;

import com.aetherledger.domain.entity.ReconciliationRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, UUID> {

    /**
     * Fetches a run together with all its items in a single query.
     * The LEFT JOIN FETCH prevents N+1 when the caller iterates over items.
     */
    @Query("""
        SELECT r FROM ReconciliationRun r
        LEFT JOIN FETCH r.items
        WHERE r.id = :id
        """)
    Optional<ReconciliationRun> findByIdWithItems(@Param("id") UUID id);
}
