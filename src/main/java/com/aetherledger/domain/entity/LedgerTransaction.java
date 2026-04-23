package com.aetherledger.domain.entity;

import com.aetherledger.domain.enums.ExternalStatus;
import com.aetherledger.domain.enums.ReconciliationResult;
import com.aetherledger.domain.enums.TransactionStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
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

    // -------------------------------------------------------------------------
    // Reconciliation fields — nullable; populated by an explicit reconcile call
    // -------------------------------------------------------------------------

    /**
     * The identifier used by the external payment provider for this transaction.
     * Blank values are normalised to {@code null} on write.
     */
    @Column(name = "external_reference_id", length = 255)
    private String externalReferenceId;

    /**
     * The status last reported by the external provider.
     * {@code null} until the first reconcile call.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "external_status", length = 20)
    private ExternalStatus externalStatus;

    /** UTC timestamp of the most recent reconcile call; {@code null} if never reconciled. */
    @Column(name = "reconciled_at")
    private Instant reconciledAt;

    /**
     * Intent fields — set once at PENDING creation, null for transactions posted
     * directly to SUCCESS via {@link #create}.  These capture the authorised
     * debit/credit plan so {@code complete()} can create entries without the
     * caller re-supplying them.
     */
    @Column(name = "debit_account_id", updatable = false)
    private UUID debitAccountId;

    @Column(name = "credit_account_id", updatable = false)
    private UUID creditAccountId;

    @Column(name = "amount", precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    /**
     * When non-null, identifies the original transaction this record reverses.
     * Set once at creation and never updated.
     */
    @Column(name = "reversal_of_transaction_id", updatable = false)
    private UUID reversalOfTransactionId;

    /**
     * When non-null, identifies the reversal transaction that compensated this record.
     * Set exactly once when a reversal is posted; the DB-level UNIQUE constraint
     * ({@code uq_reversal_of_transaction_id}) prevents two reversals from targeting
     * the same original.
     */
    @Column(name = "reversed_by_transaction_id", unique = true)
    private UUID reversedByTransactionId;

    /**
     * Set to {@code true} by the scheduled reconciliation job when it claims this
     * transaction for processing.  Cleared on successful commit.  Combined with the
     * {@code @Version} column, acts as an optimistic-lock guard against duplicate
     * processing across concurrent scheduler instances.
     */
    @Column(name = "processing", nullable = false)
    private boolean processing = false;

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

    /**
     * Creates a new {@code LedgerTransaction} in {@code PENDING} state, capturing
     * the debit/credit intent so it can be finalised later via
     * {@code LedgerTransactionService.complete()}.
     * No {@link LedgerEntry} records are created at this point.
     */
    public static LedgerTransaction createPending(
            String referenceId,
            UUID debitAccountId,
            UUID creditAccountId,
            BigDecimal amount) {

        Objects.requireNonNull(debitAccountId,  "debitAccountId must not be null");
        Objects.requireNonNull(creditAccountId, "creditAccountId must not be null");
        Objects.requireNonNull(amount,           "amount must not be null");

        LedgerTransaction tx = create(referenceId);
        tx.debitAccountId  = debitAccountId;
        tx.creditAccountId = creditAccountId;
        tx.amount          = amount.setScale(4);
        return tx;
    }

    /**
     * Creates a reversal transaction that compensates {@code original}.
     * Sets {@code reversalOfTransactionId} to the original's id at construction
     * time (the column is {@code updatable = false}).
     */
    public static LedgerTransaction createReversal(String referenceId, UUID originalTransactionId) {
        Objects.requireNonNull(originalTransactionId, "originalTransactionId must not be null");
        LedgerTransaction tx = create(referenceId);
        tx.reversalOfTransactionId = originalTransactionId;
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

    /** Claims this transaction for scheduled reconciliation processing. */
    public void markProcessing() {
        this.processing = true;
    }

    /** Releases the processing claim after successful reconciliation. */
    public void unmarkProcessing() {
        this.processing = false;
    }

    // -------------------------------------------------------------------------
    // Reconciliation domain logic
    // -------------------------------------------------------------------------

    /**
     * Records an external provider's view of this transaction.
     * Blank {@code externalReferenceId} values are normalised to {@code null}
     * so that the stored row is always unambiguously null-or-present.
     * Calling this method again overwrites a previous reconciliation, supporting
     * re-reconcile / correction workflows.
     */
    public void reconcile(String externalReferenceId, ExternalStatus externalStatus) {
        this.externalReferenceId = (externalReferenceId != null && !externalReferenceId.isBlank())
            ? externalReferenceId.strip()
            : null;
        this.externalStatus = externalStatus;
        this.reconciledAt   = Instant.now();
    }

    /**
     * Derives the reconciliation outcome from the stored fields.
     * This value is <strong>never persisted</strong>; always computed on demand.
     *
     * <p>Rules:
     * <ol>
     *   <li>No reconcile call yet → {@code NOT_RECONCILED}</li>
     *   <li>External reference or status missing → {@code MISSING_EXTERNAL_REFERENCE}</li>
     *   <li>Internal and external statuses semantically agree → {@code MATCHED}</li>
     *   <li>Otherwise → {@code STATUS_MISMATCH}</li>
     * </ol>
     */
    public ReconciliationResult computeReconciliationResult() {
        if (reconciledAt == null) {
            return ReconciliationResult.NOT_RECONCILED;
        }
        if (externalReferenceId == null || externalStatus == null) {
            return ReconciliationResult.MISSING_EXTERNAL_REFERENCE;
        }
        boolean matched = switch (status) {
            case SUCCESS -> externalStatus == ExternalStatus.SUCCESS;
            case FAILED  -> externalStatus == ExternalStatus.FAILED;
            case PENDING -> externalStatus == ExternalStatus.PENDING;
        };
        return matched ? ReconciliationResult.MATCHED : ReconciliationResult.STATUS_MISMATCH;
    }

    /**
     * Records the id of the reversal transaction on this original.
     * May only be called once; subsequent calls are a programming error.
     */
    public void markAsReversedBy(UUID reversalTransactionId) {
        Objects.requireNonNull(reversalTransactionId, "reversalTransactionId must not be null");
        if (this.reversedByTransactionId != null) {
            throw new IllegalStateException(
                "Transaction " + id + " is already marked as reversed by " + reversedByTransactionId);
        }
        this.reversedByTransactionId = reversalTransactionId;
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

    public UUID getId()                         { return id; }
    public String getReferenceId()              { return referenceId; }
    public TransactionStatus getStatus()        { return status; }
    public Instant getCreatedAt()               { return createdAt; }
    public Long getVersion()                    { return version; }
    public UUID getDebitAccountId()             { return debitAccountId; }
    public UUID getCreditAccountId()            { return creditAccountId; }
    public BigDecimal getAmount()               { return amount; }
    public UUID getReversalOfTransactionId()    { return reversalOfTransactionId; }
    public UUID getReversedByTransactionId()    { return reversedByTransactionId; }
    public String getExternalReferenceId()      { return externalReferenceId; }
    public ExternalStatus getExternalStatus()   { return externalStatus; }
    public Instant getReconciledAt()            { return reconciledAt; }
    public boolean isProcessing()               { return processing; }

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
