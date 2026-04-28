package com.aetherledger.service;

import com.aetherledger.api.dto.AuditChainVerificationResponse;
import com.aetherledger.domain.entity.LedgerAuditChainEntry;
import com.aetherledger.exception.AuditChainEntryNotFoundException;
import com.aetherledger.repository.LedgerAuditChainRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Manages the tamper-evident ledger audit hash chain.
 *
 * <h3>Chain invariant</h3>
 * Every entry stores:
 * <pre>
 *   payloadHash  = SHA-256(payload)
 *   currentHash  = SHA-256(sequenceNumber | eventType | entityType
 *                          | entityId | payloadHash | previousHash)
 * </pre>
 * where {@code previousHash} for the genesis entry is the literal string {@code "GENESIS"}.
 * Verification re-derives each {@code currentHash} from the stored fields; any discrepancy
 * means a row was altered after it was written.
 *
 * <h3>Concurrency safety</h3>
 * A PostgreSQL transaction-scoped advisory lock serialises all writes to the chain.
 * The lock is re-entrant within the same database transaction, which allows nested calls
 * (e.g. hold capture → ledger post → chain append) to succeed without deadlock.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditChainService {

    /**
     * Arbitrary key used as the PostgreSQL advisory lock identifier.
     * Any unique constant works; the value has no external meaning.
     */
    static final int CHAIN_ADVISORY_LOCK_KEY = 0x41455443; // "AETC"

    private final LedgerAuditChainRepository auditChainRepository;
    private final JdbcTemplate               jdbcTemplate;

    // -------------------------------------------------------------------------
    // Write
    // -------------------------------------------------------------------------

    /**
     * Appends a new entry to the chain within the caller's active transaction.
     *
     * <p>The advisory lock ensures that concurrent calls from separate database
     * connections cannot produce duplicate sequence numbers.  Because the lock is
     * transaction-scoped and re-entrant, nested calls within the same transaction
     * (e.g. {@code HoldService.capture} → {@code LedgerTransactionService.post}) work
     * correctly without blocking on the same connection.
     *
     * @param eventType  ledger event type (e.g. {@code "TRANSACTION_POSTED"})
     * @param entityType domain entity type (e.g. {@code "LEDGER_TRANSACTION"})
     * @param entityId   UUID of the domain entity that triggered the event
     * @param payload    deterministic string representation of the event data
     */
    @Transactional(rollbackFor = Exception.class)
    public LedgerAuditChainEntry append(
            String eventType,
            String entityType,
            UUID   entityId,
            String payload) {

        jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + CHAIN_ADVISORY_LOCK_KEY + ")");

        LedgerAuditChainEntry last = auditChainRepository
            .findTopByOrderBySequenceNumberDesc()
            .orElse(null);

        long   nextSeq   = last == null ? 1L : last.getSequenceNumber() + 1L;
        String prevHash  = last == null ? null : last.getCurrentHash();

        String payloadHash = sha256(payload);
        String currentHash = computeChainHash(nextSeq, eventType, entityType, entityId, payloadHash, prevHash);

        LedgerAuditChainEntry entry = LedgerAuditChainEntry.create(
            nextSeq, eventType, entityType, entityId, payloadHash, prevHash, currentHash);

        entry = auditChainRepository.save(entry);

        log.debug("Audit chain entry appended: seq={} eventType={} entityType={} entityId={}",
            nextSeq, eventType, entityType, entityId);

        return entry;
    }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public LedgerAuditChainEntry getLatest() {
        return auditChainRepository.findTopByOrderBySequenceNumberDesc()
            .orElseThrow(AuditChainEntryNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public LedgerAuditChainEntry getBySequenceNumber(long sequenceNumber) {
        return auditChainRepository.findBySequenceNumber(sequenceNumber)
            .orElseThrow(() -> new AuditChainEntryNotFoundException(sequenceNumber));
    }

    @Transactional(readOnly = true)
    public List<LedgerAuditChainEntry> getByEntityId(UUID entityId) {
        return auditChainRepository.findAllByEntityIdOrderBySequenceNumberDesc(entityId);
    }

    @Transactional(readOnly = true)
    public Page<LedgerAuditChainEntry> listEntries(Pageable pageable) {
        return auditChainRepository.findAllByOrderBySequenceNumberDesc(pageable);
    }

    // -------------------------------------------------------------------------
    // Verification
    // -------------------------------------------------------------------------

    /**
     * Verifies the integrity of the entire chain by re-deriving every
     * {@code currentHash} from its stored fields and confirming the
     * {@code previousHash} linkage between consecutive entries.
     *
     * <p>A {@code valid: false} result with a non-null {@code brokenAtSequenceNumber}
     * means the entry at that position was tampered with after it was written.
     */
    @Transactional(readOnly = true)
    public AuditChainVerificationResponse verifyChain() {
        List<LedgerAuditChainEntry> entries = auditChainRepository.findAllByOrderBySequenceNumberAsc();

        if (entries.isEmpty()) {
            return new AuditChainVerificationResponse(true, 0L, null, null,
                "Ledger audit chain is empty — no events have been recorded yet");
        }

        String expectedPrevHash = null;

        for (LedgerAuditChainEntry entry : entries) {

            if (!Objects.equals(entry.getPreviousHash(), expectedPrevHash)) {
                log.warn("Audit chain broken at seq={}: previousHash linkage mismatch", entry.getSequenceNumber());
                return new AuditChainVerificationResponse(
                    false, entries.size(), entry.getSequenceNumber(), entry.getId().toString(),
                    "Ledger audit chain is broken at sequence " + entry.getSequenceNumber());
            }

            String recomputed = computeChainHash(
                entry.getSequenceNumber(),
                entry.getEventType(),
                entry.getEntityType(),
                entry.getEntityId(),
                entry.getPayloadHash(),
                entry.getPreviousHash()
            );

            if (!recomputed.equals(entry.getCurrentHash())) {
                log.warn("Audit chain broken at seq={}: currentHash does not match recomputed value",
                    entry.getSequenceNumber());
                return new AuditChainVerificationResponse(
                    false, entries.size(), entry.getSequenceNumber(), entry.getId().toString(),
                    "Ledger audit chain is broken at sequence " + entry.getSequenceNumber());
            }

            expectedPrevHash = entry.getCurrentHash();
        }

        log.debug("Audit chain verified: {} entries all intact", entries.size());
        return new AuditChainVerificationResponse(
            true, entries.size(), null, null,
            "Ledger audit chain is intact: " + entries.size() + " entries verified");
    }

    // -------------------------------------------------------------------------
    // Payload builders — deterministic, pipe-delimited key=value strings
    // -------------------------------------------------------------------------

    public static String transactionPayload(
            UUID txId, String referenceId, String status,
            String amount, UUID debitAccountId, UUID creditAccountId) {
        return "txId=" + txId
             + "|ref=" + referenceId
             + "|status=" + status
             + "|amount=" + amount
             + "|debit=" + debitAccountId
             + "|credit=" + creditAccountId;
    }

    public static String holdPayload(
            UUID holdId, String referenceId, UUID accountId,
            String amount, String status) {
        return "holdId=" + holdId
             + "|ref=" + referenceId
             + "|accountId=" + accountId
             + "|amount=" + amount
             + "|status=" + status;
    }

    // -------------------------------------------------------------------------
    // Hash utilities — package-visible for unit testing
    // -------------------------------------------------------------------------

    static String computeChainHash(
            long   sequenceNumber,
            String eventType,
            String entityType,
            UUID   entityId,
            String payloadHash,
            String previousHash) {

        String input = sequenceNumber
                     + "|" + eventType
                     + "|" + entityType
                     + "|" + entityId
                     + "|" + payloadHash
                     + "|" + (previousHash != null ? previousHash : "GENESIS");
        return sha256(input);
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", ex);
        }
    }
}
