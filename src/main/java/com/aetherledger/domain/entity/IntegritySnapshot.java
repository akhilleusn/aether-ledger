package com.aetherledger.domain.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable point-in-time record of the ledger's structural health.
 *
 * <p>Created by {@code POST /api/v1/ops/integrity/snapshots} and never mutated —
 * all columns carry {@code updatable = false}.  Counts are sourced from the live
 * integrity report at the moment of creation.
 */
@Entity
@Table(name = "integrity_snapshots")
public class IntegritySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "generated_at", nullable = false, updatable = false)
    private Instant generatedAt;

    @Column(nullable = false, updatable = false)
    private boolean healthy;

    @Column(name = "total_transactions", nullable = false, updatable = false)
    private long totalTransactions;

    @Column(name = "success_count", nullable = false, updatable = false)
    private long successCount;

    @Column(name = "pending_count", nullable = false, updatable = false)
    private long pendingCount;

    @Column(name = "failed_count", nullable = false, updatable = false)
    private long failedCount;

    /** Reserved for JPA proxying — do not call from application code. */
    protected IntegritySnapshot() {}

    public static IntegritySnapshot create(
            boolean healthy,
            long totalTransactions,
            long successCount,
            long pendingCount,
            long failedCount) {

        IntegritySnapshot s = new IntegritySnapshot();
        s.healthy           = healthy;
        s.totalTransactions = totalTransactions;
        s.successCount      = successCount;
        s.pendingCount      = pendingCount;
        s.failedCount       = failedCount;
        return s;
    }

    @PrePersist
    protected void onCreate() {
        this.generatedAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public UUID    getId()                { return id; }
    public Instant getGeneratedAt()       { return generatedAt; }
    public boolean isHealthy()            { return healthy; }
    public long    getTotalTransactions() { return totalTransactions; }
    public long    getSuccessCount()      { return successCount; }
    public long    getPendingCount()      { return pendingCount; }
    public long    getFailedCount()       { return failedCount; }
}
