package com.aetherledger.domain.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable per-item record within a {@link ReconciliationRun}.
 *
 * <p>Captures the outcome of matching one provider record against the internal
 * ledger during a batch reconciliation run.  Like ledger entries, these records
 * are append-only and must never be deleted or mutated after creation.
 */
@Entity
@Table(
    name = "reconciliation_run_items",
    indexes = {
        @Index(name = "idx_recon_run_item_run_id", columnList = "reconciliation_run_id")
    }
)
public class ReconciliationRunItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "reconciliation_run_id",
        nullable = false,
        updatable = false,
        foreignKey = @ForeignKey(name = "fk_recon_run_item_run")
    )
    private ReconciliationRun reconciliationRun;

    @Column(name = "reference_id", nullable = false, updatable = false, length = 255)
    private String referenceId;

    /** Null when no internal transaction was found for this item. */
    @Column(name = "transaction_id", updatable = false)
    private UUID transactionId;

    /** String representation of {@link com.aetherledger.domain.enums.ReconciliationResult};
     *  null when {@code itemStatus} is {@code NOT_FOUND}. */
    @Column(name = "reconciliation_result", updatable = false, length = 40)
    private String reconciliationResult;

    /** {@code UPDATED} when the transaction was found and updated; {@code NOT_FOUND} otherwise. */
    @Column(name = "item_status", nullable = false, updatable = false, length = 20)
    private String itemStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Reserved for JPA proxying — do not call from application code. */
    protected ReconciliationRunItem() {}

    /**
     * Package-private factory — called exclusively by
     * {@link ReconciliationRun#addItem} to keep the bidirectional link
     * consistent without exposing a public mutator.
     */
    static ReconciliationRunItem create(
            ReconciliationRun run,
            String referenceId,
            UUID transactionId,
            String reconciliationResult,
            String itemStatus) {

        Objects.requireNonNull(run,         "run must not be null");
        Objects.requireNonNull(referenceId, "referenceId must not be null");
        Objects.requireNonNull(itemStatus,  "itemStatus must not be null");

        ReconciliationRunItem item = new ReconciliationRunItem();
        item.reconciliationRun    = run;
        item.referenceId          = referenceId;
        item.transactionId        = transactionId;
        item.reconciliationResult = reconciliationResult;
        item.itemStatus           = itemStatus;
        return item;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public UUID               getId()                  { return id; }
    public ReconciliationRun  getReconciliationRun()   { return reconciliationRun; }
    public String             getReferenceId()         { return referenceId; }
    public UUID               getTransactionId()       { return transactionId; }
    public String             getReconciliationResult(){ return reconciliationResult; }
    public String             getItemStatus()          { return itemStatus; }
    public Instant            getCreatedAt()           { return createdAt; }
}
