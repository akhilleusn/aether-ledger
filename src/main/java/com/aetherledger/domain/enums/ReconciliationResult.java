package com.aetherledger.domain.enums;

/**
 * Derived reconciliation outcome for a {@link com.aetherledger.domain.entity.LedgerTransaction}.
 *
 * <p>This value is <strong>never stored</strong>; it is always computed on the fly
 * from the transaction's internal status and its external reconciliation fields.
 *
 * <ul>
 *   <li>{@code NOT_RECONCILED}             — no reconciliation attempt has been made yet</li>
 *   <li>{@code MISSING_EXTERNAL_REFERENCE} — reconcile was called but no external reference
 *                                            or external status was provided</li>
 *   <li>{@code MATCHED}                    — internal status and external status agree</li>
 *   <li>{@code STATUS_MISMATCH}            — internal and external statuses disagree;
 *                                            requires investigation</li>
 * </ul>
 */
public enum ReconciliationResult {
    NOT_RECONCILED,
    MISSING_EXTERNAL_REFERENCE,
    MATCHED,
    STATUS_MISMATCH
}
