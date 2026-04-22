package com.aetherledger.domain.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable record of a single side of a double-entry ledger movement.
 *
 * <p><strong>Sign convention:</strong>
 * <ul>
 *   <li>Positive amount → <em>credit</em>  (value flows into the account)</li>
 *   <li>Negative amount → <em>debit</em>   (value flows out of the account)</li>
 * </ul>
 *
 * <p>Every {@link LedgerTransaction} must produce exactly two entries whose
 * amounts sum to zero.  This invariant is validated by the service layer before
 * any entry is persisted.
 *
 * <p><strong>Immutability:</strong> All columns carry {@code updatable = false}.
 * There is no {@code @Version} field because this entity is never updated.
 * Hibernate will raise an error if any column is modified after the initial INSERT.
 *
 * <p><strong>Monetary precision:</strong> Amounts are stored as
 * {@code NUMERIC(19, 4)}, giving 15 integer digits and 4 decimal places — sufficient
 * for any real-world ledger balance without floating-point rounding errors.
 */
@Entity
@Table(
    name = "ledger_entries",
    indexes = {
        @Index(name = "idx_ledger_entry_account_id",             columnList = "account_id"),
        @Index(name = "idx_ledger_entry_ledger_transaction_id",  columnList = "ledger_transaction_id")
    }
)
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "account_id",
        nullable = false,
        updatable = false,
        foreignKey = @ForeignKey(name = "fk_ledger_entry_account")
    )
    private Account account;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "ledger_transaction_id",
        nullable = false,
        updatable = false,
        foreignKey = @ForeignKey(name = "fk_ledger_entry_ledger_transaction")
    )
    private LedgerTransaction ledgerTransaction;

    /**
     * The monetary value of this entry.
     * Positive = credit.  Negative = debit.  Zero is never valid.
     * Scale is normalised to 4 decimal places at construction time.
     */
    @NotNull
    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Reserved for JPA proxying — do not call from application code. */
    protected LedgerEntry() {}

    // -------------------------------------------------------------------------
    // Static factories — the only sanctioned way to create entries
    // -------------------------------------------------------------------------

    /**
     * Creates a <strong>credit</strong> entry (positive amount) against the
     * given account within the given transaction.
     *
     * @param account            the account receiving the credit
     * @param ledgerTransaction  the owning transaction
     * @param amount             must be strictly positive
     * @throws IllegalArgumentException if {@code amount} is zero or negative
     */
    public static LedgerEntry credit(
            Account account,
            LedgerTransaction ledgerTransaction,
            BigDecimal amount) {

        Objects.requireNonNull(amount, "amount must not be null");
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                "Credit amount must be strictly positive, received: " + amount
            );
        }
        return build(account, ledgerTransaction, amount);
    }

    /**
     * Creates a <strong>debit</strong> entry (negative amount) against the
     * given account within the given transaction.
     *
     * @param account            the account being debited
     * @param ledgerTransaction  the owning transaction
     * @param amount             must be strictly negative
     * @throws IllegalArgumentException if {@code amount} is zero or positive
     */
    public static LedgerEntry debit(
            Account account,
            LedgerTransaction ledgerTransaction,
            BigDecimal amount) {

        Objects.requireNonNull(amount, "amount must not be null");
        if (amount.compareTo(BigDecimal.ZERO) >= 0) {
            throw new IllegalArgumentException(
                "Debit amount must be strictly negative, received: " + amount
            );
        }
        return build(account, ledgerTransaction, amount);
    }

    private static LedgerEntry build(
            Account account,
            LedgerTransaction ledgerTransaction,
            BigDecimal amount) {

        Objects.requireNonNull(account,            "account must not be null");
        Objects.requireNonNull(ledgerTransaction,  "ledgerTransaction must not be null");

        LedgerEntry entry = new LedgerEntry();
        entry.account           = account;
        entry.ledgerTransaction = ledgerTransaction;
        entry.amount            = amount.setScale(4);   // normalise precision at construction time

        // Keep the in-memory bidirectional link consistent so callers can
        // inspect ledgerTransaction.getLedgerEntries() before the flush.
        ledgerTransaction.addLedgerEntry(entry);

        return entry;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Domain helpers
    // -------------------------------------------------------------------------

    public boolean isCredit() {
        return amount.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean isDebit() {
        return amount.compareTo(BigDecimal.ZERO) < 0;
    }

    // -------------------------------------------------------------------------
    // Accessors — no setters; this class is immutable after construction
    // -------------------------------------------------------------------------

    public UUID getId()                           { return id; }
    public Account getAccount()                   { return account; }
    public LedgerTransaction getLedgerTransaction(){ return ledgerTransaction; }
    public BigDecimal getAmount()                 { return amount; }
    public Instant getCreatedAt()                 { return createdAt; }

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LedgerEntry other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "LedgerEntry{id=" + id
            + ", accountId=" + (account != null ? account.getId() : null)
            + ", amount=" + amount
            + ", type=" + (isCredit() ? "CREDIT" : "DEBIT")
            + "}";
    }
}
