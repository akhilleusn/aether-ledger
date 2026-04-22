package com.aetherledger.domain.entity;

import com.aetherledger.domain.enums.TransactionStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a double-entry ledger transaction.
 *
 * <p>A LedgerTransaction is the atomic unit of financial movement. It is always
 * accompanied by exactly two {@link LedgerEntry} records: one debit (negative
 * amount) and one credit (positive amount) that sum to zero.
 *
 * <p><strong>Immutability contract:</strong> {@code referenceId} and
 * {@code createdAt} are write-once (enforced via {@code updatable = false}).
 * The only permitted post-creation state change is a {@code PENDING} →
 * {@code SUCCESS} or {@code PENDING} → {@code FAILED} status transition,
 * expressed through the domain methods below.
 *
 * <p><strong>Idempotency:</strong> {@code referenceId} is the caller-supplied
 * idempotency key. The UNIQUE constraint at the database level is the final
 * enforcement barrier.
 */
@Entity
@Table(
    name = "ledger_transactions",
    indexes = {
        @Index(name = "idx_ledger_transaction_reference_id", columnList = "reference_id", unique = true)
    }
)
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotBlank
    @Column(name = "reference_id", nullable = false, unique = true, updatable = false, length = 255)
    private String referenceId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransactionStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Optimistic locking guard — two concurrent status transitions on the same
     * transaction will surface a {@code OptimisticLockException} rather than
     * silently overwriting each other.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * Entries are managed by the {@link LedgerEntry} side of the relationship.
     * {@code CascadeType.REMOVE} is intentionally omitted — ledger entries are
     * permanent financial records and must never be deleted.
     */
    @OneToMany(
        mappedBy = "ledgerTransaction",
        cascade = { CascadeType.PERSIST, CascadeType.MERGE },
        fetch = FetchType.LAZY
    )
    private List<LedgerEntry> ledgerEntries = new ArrayList<>();

    /** Reserved for JPA proxying — do not call from application code. */
    protected LedgerTransaction() {}

    /**
     * Creates a new {@code LedgerTransaction} in {@code PENDING} state.
     * Ledger entries must be attached (via {@link LedgerEntry}'s factory methods)
     * and the invariant validated before the transaction is finalised.
     */
    public static LedgerTransaction create(String referenceId) {
        Objects.requireNonNull(referenceId, "referenceId must not be null");
        if (referenceId.isBlank()) throw new IllegalArgumentException("referenceId must not be blank");

        LedgerTransaction tx = new LedgerTransaction();
        tx.referenceId = referenceId;
        tx.status = TransactionStatus.PENDING;
        return tx;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // State transitions — the only permitted mutations after creation
    // -------------------------------------------------------------------------

    public void markAsSuccess() {
        requirePending("SUCCESS");
        this.status = TransactionStatus.SUCCESS;
    }

    public void markAsFailed() {
        requirePending("FAILED");
        this.status = TransactionStatus.FAILED;
    }

    private void requirePending(String targetStatus) {
        if (this.status != TransactionStatus.PENDING) {
            throw new IllegalStateException(String.format(
                "Cannot transition LedgerTransaction %s to %s: current status is %s",
                id, targetStatus, status
            ));
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public UUID getId()                 { return id; }
    public String getReferenceId()      { return referenceId; }
    public TransactionStatus getStatus(){ return status; }
    public Instant getCreatedAt()       { return createdAt; }
    public Long getVersion()            { return version; }

    /** Returns an unmodifiable view; entries are added through {@link LedgerEntry}'s factory. */
    public List<LedgerEntry> getLedgerEntries() {
        return Collections.unmodifiableList(ledgerEntries);
    }

    /**
     * Package-private — called exclusively by {@link LedgerEntry#debit} and
     * {@link LedgerEntry#credit} to keep the bidirectional link consistent
     * without exposing a public mutator on this aggregate root.
     */
    void addLedgerEntry(LedgerEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        this.ledgerEntries.add(entry);
    }

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LedgerTransaction other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "LedgerTransaction{id=" + id
            + ", referenceId='" + referenceId + "'"
            + ", status=" + status
            + "}";
    }
}
