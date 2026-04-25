package com.aetherledger.service;

import com.aetherledger.api.dto.AuditTimelineItemResponse;
import com.aetherledger.domain.entity.LedgerEntry;
import com.aetherledger.domain.entity.LedgerTransaction;
import com.aetherledger.domain.entity.OutboxEvent;
import com.aetherledger.exception.LedgerTransactionNotFoundException;
import com.aetherledger.repository.LedgerTransactionRepository;
import com.aetherledger.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Assembles a chronological audit timeline for a single ledger transaction by
 * reading existing tables — no dedicated audit table is required.
 *
 * <p>Data sources:
 * <ul>
 *   <li>{@code ledger_transactions} — creation, reconciliation, reversal linkage</li>
 *   <li>{@code ledger_entries}       — when entries were written</li>
 *   <li>{@code outbox_events}        — domain event delivery history</li>
 * </ul>
 *
 * <p>This service is strictly read-only and never mutates ledger data.
 */
@Service
@RequiredArgsConstructor
public class AuditTimelineService {

    private static final Map<String, Integer> EVENT_PRIORITY = Map.of(
        "TRANSACTION_CREATED",      0,
        "REVERSAL_OF",              1,
        "LEDGER_ENTRIES_WRITTEN",   2,
        "OUTBOX_EVENT_QUEUED",      3,
        "OUTBOX_EVENT_PUBLISHED",   4,
        "RECONCILIATION_RECORDED",  5,
        "REVERSED_BY",              6
    );

    private final LedgerTransactionRepository ledgerTransactionRepository;
    private final OutboxEventRepository outboxEventRepository;

    @Transactional(readOnly = true)
    public List<AuditTimelineItemResponse> getTimeline(UUID transactionId) {
        LedgerTransaction tx = ledgerTransactionRepository.findByIdWithEntries(transactionId)
            .orElseThrow(() -> new LedgerTransactionNotFoundException("id", transactionId.toString()));

        List<AuditTimelineItemResponse> items = new ArrayList<>();

        items.add(transactionCreated(tx));

        if (tx.getReversalOfTransactionId() != null) {
            ledgerTransactionRepository.findById(tx.getReversalOfTransactionId())
                .ifPresent(original -> items.add(reversalOf(tx, original)));
        }

        if (!tx.getLedgerEntries().isEmpty()) {
            items.add(ledgerEntriesWritten(tx));
        }

        outboxEventRepository.findByAggregateIdOrderByCreatedAtAsc(transactionId)
            .forEach(event -> items.add(outboxItem(event)));

        if (tx.getReconciledAt() != null) {
            items.add(reconciliationRecorded(tx));
        }

        if (tx.getReversedByTransactionId() != null) {
            ledgerTransactionRepository.findById(tx.getReversedByTransactionId())
                .ifPresent(reversal -> items.add(reversedBy(reversal)));
        }

        items.sort(Comparator
            .comparing(AuditTimelineItemResponse::occurredAt)
            .thenComparingInt(item -> EVENT_PRIORITY.getOrDefault(item.eventType(), 99)));

        return items;
    }

    // -------------------------------------------------------------------------
    // Item builders
    // -------------------------------------------------------------------------

    private static AuditTimelineItemResponse transactionCreated(LedgerTransaction tx) {
        boolean isPendingFlow = tx.getDebitAccountId() != null;
        String description = isPendingFlow
            ? "Transaction created with status PENDING"
            : "Transaction created with status SUCCESS";
        return new AuditTimelineItemResponse(
            "TRANSACTION_CREATED",
            tx.getCreatedAt(),
            description,
            "ledger",
            Map.of("referenceId", tx.getReferenceId(),
                   "status", tx.getStatus().name())
        );
    }

    private static AuditTimelineItemResponse ledgerEntriesWritten(LedgerTransaction tx) {
        LedgerEntry debit  = tx.getLedgerEntries().stream().filter(LedgerEntry::isDebit).findFirst().orElseThrow();
        LedgerEntry credit = tx.getLedgerEntries().stream().filter(LedgerEntry::isCredit).findFirst().orElseThrow();

        boolean wasCompleted = tx.getDebitAccountId() != null;
        String description = wasCompleted
            ? "Ledger entries written — pending transaction completed to SUCCESS"
            : "Ledger entries written — transaction settled immediately";

        Instant occurredAt = debit.getCreatedAt();

        return new AuditTimelineItemResponse(
            "LEDGER_ENTRIES_WRITTEN",
            occurredAt,
            description,
            "ledger",
            Map.of("debitAccountId",  debit.getAccount().getId().toString(),
                   "creditAccountId", credit.getAccount().getId().toString(),
                   "amount",          credit.getAmount().toPlainString())
        );
    }

    private static AuditTimelineItemResponse reversalOf(LedgerTransaction reversal, LedgerTransaction original) {
        return new AuditTimelineItemResponse(
            "REVERSAL_OF",
            reversal.getCreatedAt(),
            "This transaction was created as a reversal of " + original.getReferenceId(),
            "ledger",
            Map.of("originalTransactionId", original.getId().toString(),
                   "originalReferenceId",   original.getReferenceId())
        );
    }

    private static AuditTimelineItemResponse reversedBy(LedgerTransaction reversalTx) {
        return new AuditTimelineItemResponse(
            "REVERSED_BY",
            reversalTx.getCreatedAt(),
            "Transaction reversed by " + reversalTx.getReferenceId(),
            "ledger",
            Map.of("reversalTransactionId", reversalTx.getId().toString(),
                   "reversalReferenceId",   reversalTx.getReferenceId())
        );
    }

    private static AuditTimelineItemResponse reconciliationRecorded(LedgerTransaction tx) {
        Map<String, Object> metadata;
        if (tx.getExternalReferenceId() != null && tx.getExternalStatus() != null) {
            metadata = Map.of(
                "externalReferenceId",  tx.getExternalReferenceId(),
                "externalStatus",       tx.getExternalStatus().name(),
                "reconciliationResult", tx.computeReconciliationResult().name()
            );
        } else {
            metadata = Map.of(
                "reconciliationResult", tx.computeReconciliationResult().name()
            );
        }
        return new AuditTimelineItemResponse(
            "RECONCILIATION_RECORDED",
            tx.getReconciledAt(),
            "Reconciliation recorded — result: " + tx.computeReconciliationResult().name(),
            "reconciliation",
            metadata
        );
    }

    private static AuditTimelineItemResponse outboxItem(OutboxEvent event) {
        if (event.isPublished()) {
            Instant occurredAt = event.getPublishedAt() != null
                ? event.getPublishedAt()
                : event.getCreatedAt();
            return new AuditTimelineItemResponse(
                "OUTBOX_EVENT_PUBLISHED",
                occurredAt,
                "Domain event published: " + event.getEventType(),
                "outbox",
                Map.of("domainEventType", event.getEventType(),
                       "queuedAt",        event.getCreatedAt().toString())
            );
        }
        return new AuditTimelineItemResponse(
            "OUTBOX_EVENT_QUEUED",
            event.getCreatedAt(),
            "Domain event queued: " + event.getEventType(),
            "outbox",
            Map.of("domainEventType", event.getEventType())
        );
    }
}
