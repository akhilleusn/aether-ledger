package com.aetherledger.domain.enums;

/**
 * The reconciliation status as reported by an external payment provider.
 *
 * <p>{@code NOT_FOUND} means the provider has no record of the transaction,
 * which is always a {@code STATUS_MISMATCH} against any internal state.
 */
public enum ExternalStatus {
    PENDING,
    SUCCESS,
    FAILED,
    NOT_FOUND
}
