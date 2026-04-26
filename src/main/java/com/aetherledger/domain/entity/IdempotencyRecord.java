package com.aetherledger.domain.entity;

import jakarta.persistence.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Durable idempotency cache entry for POST /api/v1/ledger-transactions.
 *
 * <p>Written in a {@code REQUIRES_NEW} transaction immediately after a
 * successful transaction creation. On a client retry carrying the same
 * {@code Idempotency-Key} header, the stored {@link #responseBody} is
 * returned verbatim — no duplicate transaction is created.
 *
 * <p>The {@link #requestFingerprint} (SHA-256 of the serialised request body)
 * detects misuse: the same key sent with a different body is rejected with
 * HTTP 409 before any database write occurs.
 *
 * <p>Records are valid until {@link #expiresAt}.  They are never updated;
 * a cleanup job may delete expired rows without affecting correctness.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord {

    @Id
    @Column(name = "idempotency_key", length = 255, nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", length = 64, nullable = false, updatable = false)
    private String requestFingerprint;

    @Column(name = "response_status", nullable = false, updatable = false)
    private int responseStatus;

    @Column(name = "response_body", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    protected IdempotencyRecord() {}

    public static IdempotencyRecord of(
            String idempotencyKey,
            String requestFingerprint,
            int responseStatus,
            String responseBody,
            Duration ttl) {

        Objects.requireNonNull(idempotencyKey,      "idempotencyKey must not be null");
        Objects.requireNonNull(requestFingerprint,  "requestFingerprint must not be null");
        Objects.requireNonNull(responseBody,        "responseBody must not be null");
        Objects.requireNonNull(ttl,                 "ttl must not be null");

        IdempotencyRecord r = new IdempotencyRecord();
        r.idempotencyKey    = idempotencyKey;
        r.requestFingerprint = requestFingerprint;
        r.responseStatus    = responseStatus;
        r.responseBody      = responseBody;
        r.createdAt         = Instant.now();
        r.expiresAt         = r.createdAt.plus(ttl);
        return r;
    }

    public String  getIdempotencyKey()     { return idempotencyKey; }
    public String  getRequestFingerprint() { return requestFingerprint; }
    public int     getResponseStatus()     { return responseStatus; }
    public String  getResponseBody()       { return responseBody; }
    public Instant getCreatedAt()          { return createdAt; }
    public Instant getExpiresAt()          { return expiresAt; }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IdempotencyRecord other)) return false;
        return idempotencyKey != null && idempotencyKey.equals(other.idempotencyKey);
    }

    @Override public int hashCode() { return getClass().hashCode(); }
}
