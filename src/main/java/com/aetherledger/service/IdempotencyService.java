package com.aetherledger.service;

import com.aetherledger.domain.entity.IdempotencyRecord;
import com.aetherledger.exception.IdempotencyKeyConflictException;
import com.aetherledger.repository.IdempotencyRecordRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * HTTP-level idempotency cache for POST /api/v1/ledger-transactions.
 *
 * <h3>Protocol</h3>
 * <ol>
 *   <li>Call {@link #fingerprint} on the validated request body.</li>
 *   <li>Call {@link #findExisting} — a cache hit means the response can be
 *       returned immediately; a conflict (same key, different body) is thrown
 *       as {@link IdempotencyKeyConflictException}.</li>
 *   <li>After the business operation succeeds, call {@link #store} to persist
 *       the response so future retries hit the cache.</li>
 * </ol>
 *
 * <h3>Atomicity note</h3>
 * {@link #store} runs in its own {@code REQUIRES_NEW} transaction so the
 * record is committed even if the caller later rolls back for an unrelated
 * reason.  There is a small window between the business commit and the
 * idempotency commit; in the (rare) event of a crash, a retry will receive
 * the normal duplicate-referenceId 409 instead of the cached 201 — the
 * transaction is still not duplicated.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    static final Duration TTL = Duration.ofHours(24);

    private final IdempotencyRecordRepository repository;
    private final ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // Stored response envelope
    // -------------------------------------------------------------------------

    public record StoredResponse(int status, String body) {}

    // -------------------------------------------------------------------------
    // Fingerprint
    // -------------------------------------------------------------------------

    /**
     * Computes a deterministic SHA-256 hex fingerprint of {@code requestBody}
     * by re-serialising it with Jackson.  Two equal request DTOs (same fields,
     * any JSON key order) produce the same fingerprint.
     */
    public String fingerprint(Object requestBody) {
        byte[] serialized;
        try {
            serialized = objectMapper.writeValueAsBytes(requestBody);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise request for fingerprinting", e);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(serialized));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // -------------------------------------------------------------------------
    // Cache look-up
    // -------------------------------------------------------------------------

    /**
     * Looks up a cached response for the given idempotency key.
     *
     * @return the stored response if the key exists and has not expired
     * @throws IdempotencyKeyConflictException if the key exists but the stored
     *         fingerprint does not match {@code requestFingerprint}
     */
    @Transactional(readOnly = true)
    public Optional<StoredResponse> findExisting(String key, String requestFingerprint) {
        return repository.findById(key)
            .filter(r -> !r.isExpired())
            .map(r -> {
                if (!r.getRequestFingerprint().equals(requestFingerprint)) {
                    log.warn("Idempotency key conflict: key='{}' stored fingerprint differs from request", key);
                    throw new IdempotencyKeyConflictException(key);
                }
                log.debug("Idempotency cache hit: key='{}'", key);
                return new StoredResponse(r.getResponseStatus(), r.getResponseBody());
            });
    }

    // -------------------------------------------------------------------------
    // Cache write
    // -------------------------------------------------------------------------

    /**
     * Persists the response for {@code key} in a dedicated {@code REQUIRES_NEW}
     * transaction so it is visible to retries regardless of the caller's
     * transaction outcome.
     *
     * <p>A {@link DataIntegrityViolationException} is silently swallowed: it
     * means a concurrent retry already stored the record — the stored response
     * is equivalent, so the outcome is correct.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void store(String key, String requestFingerprint, int status, Object responseBody) {
        String json;
        try {
            json = objectMapper.writeValueAsString(responseBody);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise idempotency response for key='{}': {}", key, e.getMessage());
            return;
        }
        try {
            repository.save(IdempotencyRecord.of(key, requestFingerprint, status, json, TTL));
            log.debug("Idempotency record stored: key='{}'", key);
        } catch (DataIntegrityViolationException e) {
            log.debug("Idempotency record already exists (concurrent write): key='{}'", key);
        }
    }

    // -------------------------------------------------------------------------
    // Deserialisation helper
    // -------------------------------------------------------------------------

    public <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                "Failed to deserialise cached idempotency response as " + type.getSimpleName(), e);
        }
    }
}
