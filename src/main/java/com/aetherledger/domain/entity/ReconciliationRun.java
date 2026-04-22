package com.aetherledger.domain.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Immutable audit record of a single batch reconciliation run.
 *
 * <p>Created once when {@code POST /api/v1/reconciliation/batch} completes and
 * never mutated afterwards — all columns carry {@code updatable = false}.
 * Every item processed during the run is captured as a child
 * {@link ReconciliationRunItem}, giving operators full per-item traceability.
 */
@Entity
@Table(name = "reconciliation_runs")
public class ReconciliationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "total_items", nullable = false, updatable = false)
    private int totalItems;

    @Column(name = "updated_count", nullable = false, updatable = false)
    private int updatedCount;

    @Column(name = "matched_count", nullable = false, updatable = false)
    private int matchedCount;

    @Column(name = "mismatch_count", nullable = false, updatable = false)
    private int mismatchCount;

    @Column(name = "missing_transaction_count", nullable = false, updatable = false)
    private int missingTransactionCount;

    @Column(name = "missing_external_reference_count", nullable = false, updatable = false)
    private int missingExternalReferenceCount;

    @OneToMany(
        mappedBy = "reconciliationRun",
        cascade = { CascadeType.PERSIST, CascadeType.MERGE },
        fetch = FetchType.LAZY
    )
    private List<ReconciliationRunItem> items = new ArrayList<>();

    /** Reserved for JPA proxying — do not call from application code. */
    protected ReconciliationRun() {}

    public static ReconciliationRun create(
            int totalItems,
            int updatedCount,
            int matchedCount,
            int mismatchCount,
            int missingTransactionCount,
            int missingExternalReferenceCount) {

        ReconciliationRun run = new ReconciliationRun();
        run.totalItems                   = totalItems;
        run.updatedCount                 = updatedCount;
        run.matchedCount                 = matchedCount;
        run.mismatchCount                = mismatchCount;
        run.missingTransactionCount      = missingTransactionCount;
        run.missingExternalReferenceCount = missingExternalReferenceCount;
        return run;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    /**
     * Appends one item record to this run.  Must be called before the run is
     * saved so that cascade {@code PERSIST} writes all items atomically.
     */
    public void addItem(String referenceId, UUID transactionId,
                        String reconciliationResult, String itemStatus) {
        ReconciliationRunItem item = ReconciliationRunItem.create(
            this, referenceId, transactionId, reconciliationResult, itemStatus);
        this.items.add(item);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public UUID    getId()                           { return id; }
    public Instant getCreatedAt()                    { return createdAt; }
    public int     getTotalItems()                   { return totalItems; }
    public int     getUpdatedCount()                 { return updatedCount; }
    public int     getMatchedCount()                 { return matchedCount; }
    public int     getMismatchCount()                { return mismatchCount; }
    public int     getMissingTransactionCount()      { return missingTransactionCount; }
    public int     getMissingExternalReferenceCount(){ return missingExternalReferenceCount; }

    public List<ReconciliationRunItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
