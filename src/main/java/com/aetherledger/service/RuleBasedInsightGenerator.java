package com.aetherledger.service;

import com.aetherledger.domain.entity.LedgerTransaction;
import com.aetherledger.domain.enums.ExternalStatus;
import com.aetherledger.domain.enums.TransactionStatus;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic rule-based {@link InsightGenerator}.
 *
 * <p>Derives operator-friendly explanations from the transaction's reconciliation
 * state without making any external calls.  Replace this bean with an AI-backed
 * adapter (e.g. Claude, OpenAI) to produce language-model-generated insights —
 * no callers need to change.
 *
 * <p>Language guidelines applied throughout:
 * <ul>
 *   <li>Uses "Possible causes" — never asserts the system is definitively wrong.</li>
 *   <li>Recommends checks — never prescribes a mandatory fix.</li>
 *   <li>Avoids medical, legal, or financial advice wording.</li>
 * </ul>
 */
@Component
public class RuleBasedInsightGenerator implements InsightGenerator {

    private static final Map<String, Insight> MISMATCH_TABLE = buildMismatchTable();

    @Override
    public Insight generate(LedgerTransaction tx) {
        return switch (tx.computeReconciliationResult()) {
            case NOT_RECONCILED             -> notReconciled();
            case MATCHED                    -> matched();
            case MISSING_EXTERNAL_REFERENCE -> missingExternalReference();
            case STATUS_MISMATCH            -> mismatch(tx.getStatus(), tx.getExternalStatus());
        };
    }

    // -------------------------------------------------------------------------
    // Static insight factories
    // -------------------------------------------------------------------------

    private static Insight notReconciled() {
        return new Insight(
            "No external reconciliation data has been recorded for this transaction. " +
            "The provider has not been queried yet, or the scheduled reconciliation job has not " +
            "processed this transaction.",
            List.of(
                "Verify the reconciliation batch covers this transaction's date range.",
                "Confirm the scheduled reconciliation job is enabled and running.",
                "Check whether the transaction's referenceId was submitted to the external provider."
            )
        );
    }

    private static Insight matched() {
        return new Insight(
            "Internal and external statuses are in agreement. No anomaly detected.",
            List.of("No action required.")
        );
    }

    private static Insight missingExternalReference() {
        return new Insight(
            "A reconciliation attempt was recorded but the external provider reference ID or status " +
            "was not supplied. The outcome cannot be fully verified without complete external data.",
            List.of(
                "Re-submit reconciliation with the provider's reference ID and status.",
                "Confirm whether the provider issued a reference ID for this transaction.",
                "Check for provider-side processing delays or API errors during the reconciliation call."
            )
        );
    }

    private static Insight mismatch(TransactionStatus internal, ExternalStatus external) {
        String key = internal.name() + "-" + (external != null ? external.name() : "UNKNOWN");
        return MISMATCH_TABLE.getOrDefault(key, genericMismatch(internal, external));
    }

    private static Insight genericMismatch(TransactionStatus internal, ExternalStatus external) {
        return new Insight(
            String.format(
                "The internal ledger status (%s) does not agree with the external provider status (%s). " +
                "Possible causes include a synchronisation delay, an incorrect external reference ID, " +
                "or a provider-side update that has not yet been reflected. Manual review is recommended.",
                internal, external
            ),
            List.of(
                "Verify the external reference ID maps to the correct provider transaction.",
                "Check provider logs for this referenceId.",
                "Escalate to the payments operations team if the discrepancy cannot be explained."
            )
        );
    }

    // -------------------------------------------------------------------------
    // Mismatch lookup table  (internal status — external status)
    // -------------------------------------------------------------------------

    private static Map<String, Insight> buildMismatchTable() {
        Map<String, Insight> m = new HashMap<>();

        // SUCCESS internal
        m.put("SUCCESS-FAILED", new Insight(
            "The internal ledger records this transaction as successful, but the external provider " +
            "reports it as failed. Possible causes include: the provider declined the transaction after " +
            "the internal commit was recorded, a delayed chargeback or reversal from the provider, or " +
            "a data synchronisation gap between systems.",
            List.of(
                "Verify the provider's transaction log for this referenceId.",
                "Check whether a chargeback or reversal was issued by the provider.",
                "Review the transaction timeline for processing delays.",
                "Confirm the correct externalReferenceId was supplied during reconciliation.",
                "Escalate to the payments operations team if the discrepancy cannot be explained."
            )
        ));
        m.put("SUCCESS-PENDING", new Insight(
            "The internal ledger records this transaction as successful, but the external provider " +
            "reports it as still pending. Possible causes include: a settlement delay on the provider " +
            "side, or the provider's state had not yet updated when this reconciliation was run.",
            List.of(
                "Re-run reconciliation after the provider's settlement window has passed.",
                "Check the provider's expected settlement SLA for this transaction type.",
                "Verify that the external reference ID maps to the correct provider transaction.",
                "Monitor for a subsequent status update from the provider."
            )
        ));
        m.put("SUCCESS-NOT_FOUND", new Insight(
            "The internal ledger records this transaction as successful, but the external provider " +
            "has no record of it. Possible causes include: an incorrect external reference ID, " +
            "the transaction being routed through an intermediary, or a provider-side data retention issue.",
            List.of(
                "Confirm the externalReferenceId supplied during reconciliation is correct.",
                "Check provider transaction logs directly using the original referenceId.",
                "Verify whether an intermediary or payment gateway was involved.",
                "Escalate to the payments operations team if the transaction cannot be located."
            )
        ));

        // FAILED internal
        m.put("FAILED-SUCCESS", new Insight(
            "The internal ledger records this transaction as failed, but the external provider " +
            "reports it as successful. Possible causes include: an internal processing error after " +
            "the provider confirmed the transaction, or funds may have moved externally without a " +
            "corresponding ledger record.",
            List.of(
                "Verify whether ledger entries exist for this transaction.",
                "Check application logs for errors around the time this transaction was processed.",
                "Confirm whether a manual correction or reversal is needed.",
                "Escalate to the payments operations team — funds may have moved without a matching ledger record."
            )
        ));
        m.put("FAILED-PENDING", new Insight(
            "The internal ledger records this transaction as failed, but the external provider " +
            "reports it as pending. The provider may still be processing this transaction and has " +
            "not yet reached a terminal state.",
            List.of(
                "Wait for the provider to finalise the transaction status.",
                "Re-run reconciliation once the provider has reached a terminal state.",
                "Verify whether the provider processed this transaction independently."
            )
        ));
        m.put("FAILED-NOT_FOUND", new Insight(
            "The internal ledger records this transaction as failed and the external provider has " +
            "no record of it. This may be expected if the transaction was rejected before submission " +
            "to the provider, but unusual if an external reference ID was supplied.",
            List.of(
                "Confirm whether the transaction was submitted to the external provider.",
                "Verify the externalReferenceId supplied during reconciliation is correct.",
                "No ledger entry correction is likely needed, but confirm with the payments team."
            )
        ));

        // PENDING internal
        m.put("PENDING-SUCCESS", new Insight(
            "The internal ledger records this transaction as pending, but the external provider " +
            "reports it as successful. The provider may have settled before the internal confirmation " +
            "step was completed.",
            List.of(
                "Complete the pending transaction internally if the provider has confirmed settlement.",
                "Verify that the provider's reference maps to this specific transaction.",
                "Check whether a webhook or callback was missed that should have triggered completion.",
                "Review reconciliation timing relative to the provider's settlement window."
            )
        ));
        m.put("PENDING-FAILED", new Insight(
            "The internal ledger records this transaction as pending, but the external provider " +
            "reports it as failed. Consider failing this transaction internally to align with the " +
            "provider's status.",
            List.of(
                "Fail the internal transaction if the provider has confirmed failure.",
                "Confirm the provider's failure reason before taking action.",
                "Check whether a new transaction attempt is required."
            )
        ));
        m.put("PENDING-NOT_FOUND", new Insight(
            "The internal ledger records this transaction as pending and the external provider has " +
            "no record of it. Possible causes include: the transaction was not yet submitted to the " +
            "provider, or an incorrect external reference ID was used.",
            List.of(
                "Confirm whether this transaction was submitted to the external provider.",
                "Verify the externalReferenceId supplied during reconciliation is correct.",
                "Consider failing the internal transaction if no provider record can be found."
            )
        ));

        return Map.copyOf(m);
    }
}
