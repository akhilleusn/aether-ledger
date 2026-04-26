package com.aetherledger.domain.entity;

import com.aetherledger.domain.enums.HoldStatus;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A reservation of funds on an account that reduces {@code availableBalance}
 * without moving money in the ledger.
 *
 * <p>Lifecycle:
 * <pre>
 *   ACTIVE ──► CAPTURED  (ledger transaction created; funds move)
 *   ACTIVE ──► RELEASED  (reservation cancelled; no ledger movement)
 * </pre>
 *
 * <p>The {@code @Version} column guards concurrent capture / release:
 * two concurrent requests on the same hold will race at commit time — the
 * loser surfaces as {@link jakarta.persistence.OptimisticLockException}.
 */
@Entity
@Table(
    name = "holds",
    indexes = {
        @Index(name = "idx_holds_account_id",    columnList = "account_id"),
        @Index(name = "idx_holds_account_active", columnList = "account_id")
    }
)
public class Hold {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "reference_id", nullable = false, unique = true, updatable = false, length = 255)
    private String referenceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private HoldStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    protected Hold() {}

    public static Hold create(UUID accountId, BigDecimal amount, String referenceId) {
        Objects.requireNonNull(accountId,   "accountId must not be null");
        Objects.requireNonNull(amount,      "amount must not be null");
        Objects.requireNonNull(referenceId, "referenceId must not be null");
        Hold h = new Hold();
        h.accountId   = accountId;
        h.amount      = amount.setScale(4, RoundingMode.HALF_UP);
        h.referenceId = referenceId;
        h.status      = HoldStatus.ACTIVE;
        return h;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public void capture() {
        requireStatus(HoldStatus.ACTIVE, "CAPTURED");
        this.status     = HoldStatus.CAPTURED;
        this.capturedAt = Instant.now();
    }

    public void release() {
        requireStatus(HoldStatus.ACTIVE, "RELEASED");
        this.status     = HoldStatus.RELEASED;
        this.releasedAt = Instant.now();
    }

    private void requireStatus(HoldStatus required, String target) {
        if (this.status != required) {
            throw new IllegalStateException(
                "Hold " + id + " cannot transition to " + target +
                ": current status is " + status);
        }
    }

    public UUID       getId()          { return id; }
    public UUID       getAccountId()   { return accountId; }
    public BigDecimal getAmount()      { return amount; }
    public String     getReferenceId() { return referenceId; }
    public HoldStatus getStatus()      { return status; }
    public Long       getVersion()     { return version; }
    public Instant    getCreatedAt()   { return createdAt; }
    public Instant    getCapturedAt()  { return capturedAt; }
    public Instant    getReleasedAt()  { return releasedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Hold other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override public int hashCode() { return getClass().hashCode(); }

    @Override
    public String toString() {
        return "Hold{id=" + id + ", accountId=" + accountId +
               ", amount=" + amount + ", status=" + status + "}";
    }
}
