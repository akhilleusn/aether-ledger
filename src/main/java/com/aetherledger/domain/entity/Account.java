package com.aetherledger.domain.entity;

import com.aetherledger.domain.enums.AccountType;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
    name = "accounts",
    indexes = {
        @Index(name = "idx_account_type", columnList = "type")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_account_name", columnNames = "name")
    }
)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotBlank
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private AccountType type;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Guards concurrent updates (e.g. name changes) against lost writes.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @OneToMany(mappedBy = "account", fetch = FetchType.LAZY)
    private List<LedgerEntry> ledgerEntries = new ArrayList<>();

    /** Reserved for JPA proxying — do not call from application code. */
    protected Account() {}

    public static Account of(String name, AccountType type) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        Objects.requireNonNull(type, "type must not be null");

        Account account = new Account();
        account.name = name;
        account.type = type;
        return account;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public UUID getId()                        { return id; }
    public String getName()                    { return name; }
    public AccountType getType()               { return type; }
    public Instant getCreatedAt()              { return createdAt; }
    public Long getVersion()                   { return version; }

    public List<LedgerEntry> getLedgerEntries() {
        return Collections.unmodifiableList(ledgerEntries);
    }

    // -------------------------------------------------------------------------
    // Controlled mutation — only the display name may change after creation
    // -------------------------------------------------------------------------

    public void setName(String name) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        this.name = name;
    }

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    /**
     * Equality is based on the database-assigned id.
     * Two transient (un-persisted) instances with null ids are never equal.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Account other)) return false;
        return id != null && id.equals(other.id);
    }

    /**
     * Constant across the entity lifecycle so that instances placed in a
     * Set or HashMap before persistence remain findable after the id is assigned.
     */
    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "Account{id=" + id + ", name='" + name + "', type=" + type + "}";
    }
}
