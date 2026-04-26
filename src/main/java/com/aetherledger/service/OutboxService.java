package com.aetherledger.service;

import com.aetherledger.domain.entity.Hold;
import com.aetherledger.domain.entity.LedgerTransaction;
import com.aetherledger.domain.entity.OutboxEvent;
import com.aetherledger.domain.entity.ReconciliationRun;
import com.aetherledger.domain.enums.OutboxEventType;
import com.aetherledger.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes outbox events in the same database transaction as the business
 * operation that produced them.
 *
 * <p>Callers are always inside a {@code @Transactional} method.  If the
 * business operation rolls back, the outbox row rolls back with it — no
 * phantom events.  If this service throws (e.g., serialisation failure),
 * the enclosing transaction rolls back too — no silent event loss.
 *
 * <p>This service is the only place that constructs {@link OutboxEvent} rows.
 * Controllers and other services never touch the outbox table directly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    static final String AGGREGATE_LEDGER_TRANSACTION  = "LEDGER_TRANSACTION";
    static final String AGGREGATE_RECONCILIATION_RUN  = "RECONCILIATION_RUN";
    static final String AGGREGATE_HOLD                = "HOLD";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // Public write methods — one per business event type
    // -------------------------------------------------------------------------

    /**
     * Written when an immediate (non-pending) transaction is posted.
     * Includes account IDs and amount from the original request because
     * the intent fields on the transaction entity are only populated for
     * PENDING transactions.
     */
    public void publishTransactionPosted(LedgerTransaction tx,
                                         UUID debitAccountId,
                                         UUID creditAccountId,
                                         BigDecimal amount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transactionId",   tx.getId());
        payload.put("referenceId",     tx.getReferenceId());
        payload.put("status",          tx.getStatus().name());
        payload.put("amount",          amount.toPlainString());
        payload.put("debitAccountId",  debitAccountId);
        payload.put("creditAccountId", creditAccountId);
        payload.put("createdAt",       tx.getCreatedAt().toString());
        persist(OutboxEventType.TRANSACTION_POSTED, tx.getId(), AGGREGATE_LEDGER_TRANSACTION, payload);
    }

    /**
     * Written when a PENDING transaction transitions to SUCCESS.
     * Intent fields (debitAccountId, creditAccountId, amount) are already
     * stored on the transaction entity from the createPending() call.
     */
    public void publishTransactionCompleted(LedgerTransaction tx) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transactionId",   tx.getId());
        payload.put("referenceId",     tx.getReferenceId());
        payload.put("status",          tx.getStatus().name());
        payload.put("amount",          tx.getAmount().toPlainString());
        payload.put("debitAccountId",  tx.getDebitAccountId());
        payload.put("creditAccountId", tx.getCreditAccountId());
        payload.put("createdAt",       tx.getCreatedAt().toString());
        persist(OutboxEventType.TRANSACTION_COMPLETED, tx.getId(), AGGREGATE_LEDGER_TRANSACTION, payload);
    }

    /**
     * Written when a reversal transaction is posted.
     * Includes a pointer back to the original transaction for full traceability.
     */
    public void publishTransactionReversed(LedgerTransaction reversal) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transactionId",            reversal.getId());
        payload.put("referenceId",              reversal.getReferenceId());
        payload.put("status",                   reversal.getStatus().name());
        payload.put("reversalOfTransactionId",  reversal.getReversalOfTransactionId());
        payload.put("createdAt",                reversal.getCreatedAt().toString());
        persist(OutboxEventType.TRANSACTION_REVERSED, reversal.getId(), AGGREGATE_LEDGER_TRANSACTION, payload);
    }

    /**
     * Written when a single transaction is reconciled against an external record.
     */
    public void publishTransactionReconciled(LedgerTransaction tx) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transactionId",        tx.getId());
        payload.put("referenceId",          tx.getReferenceId());
        payload.put("status",               tx.getStatus().name());
        payload.put("externalReferenceId",  tx.getExternalReferenceId());
        payload.put("externalStatus",       tx.getExternalStatus() != null ? tx.getExternalStatus().name() : null);
        payload.put("reconciliationResult", tx.computeReconciliationResult().name());
        payload.put("reconciledAt",         tx.getReconciledAt().toString());
        persist(OutboxEventType.TRANSACTION_RECONCILED, tx.getId(), AGGREGATE_LEDGER_TRANSACTION, payload);
    }

    /**
     * Written when a batch reconciliation run completes.
     * The payload carries aggregate counts so consumers can react without
     * fetching the full run record.
     */
    public void publishBatchReconciliationCompleted(ReconciliationRun run) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId",                       run.getId());
        payload.put("totalItems",                  run.getTotalItems());
        payload.put("updatedCount",                run.getUpdatedCount());
        payload.put("matchedCount",                run.getMatchedCount());
        payload.put("mismatchCount",               run.getMismatchCount());
        payload.put("missingTransactionCount",     run.getMissingTransactionCount());
        payload.put("missingExternalReferenceCount", run.getMissingExternalReferenceCount());
        payload.put("createdAt",                   run.getCreatedAt().toString());
        persist(OutboxEventType.BATCH_RECONCILIATION_COMPLETED, run.getId(), AGGREGATE_RECONCILIATION_RUN, payload);
    }

    // -------------------------------------------------------------------------
    // Hold lifecycle events
    // -------------------------------------------------------------------------

    public void publishHoldCreated(Hold hold) {
        persist(OutboxEventType.HOLD_CREATED, hold.getId(), AGGREGATE_HOLD, holdPayload(hold));
    }

    public void publishHoldCaptured(Hold hold) {
        persist(OutboxEventType.HOLD_CAPTURED, hold.getId(), AGGREGATE_HOLD, holdPayload(hold));
    }

    public void publishHoldReleased(Hold hold) {
        persist(OutboxEventType.HOLD_RELEASED, hold.getId(), AGGREGATE_HOLD, holdPayload(hold));
    }

    private static Map<String, Object> holdPayload(Hold hold) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("holdId",      hold.getId());
        p.put("accountId",   hold.getAccountId());
        p.put("referenceId", hold.getReferenceId());
        p.put("amount",      hold.getAmount().toPlainString());
        p.put("status",      hold.getStatus().name());
        p.put("createdAt",   hold.getCreatedAt().toString());
        return p;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void persist(OutboxEventType type,
                         UUID aggregateId,
                         String aggregateType,
                         Map<String, Object> payloadMap) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payloadMap);
        } catch (JsonProcessingException e) {
            // Payload maps contain only primitives, UUIDs, and Strings — this
            // should never fail.  Re-throw as unchecked so the enclosing
            // @Transactional rolls back the business operation too.
            throw new IllegalStateException(
                "Failed to serialize outbox payload for event type " + type, e);
        }

        OutboxEvent event = OutboxEvent.of(type, aggregateId, aggregateType, json);
        outboxEventRepository.save(event);
        log.debug("Outbox event persisted: type={} aggregateId={}", type, aggregateId);
    }
}
