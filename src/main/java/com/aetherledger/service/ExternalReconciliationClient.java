package com.aetherledger.service;

/**
 * Port for fetching the external payment provider's view of a transaction.
 *
 * <p>In production this would call a real provider API.  The default
 * implementation ({@link FakeExternalReconciliationClient}) uses deterministic
 * local logic so the scheduler can run without network dependencies.
 */
public interface ExternalReconciliationClient {

    /**
     * Fetches the provider's record for the given internal {@code referenceId}.
     *
     * @param referenceId the caller-supplied idempotency key on the ledger transaction
     * @return the provider's external reference and reported status
     */
    ExternalResult fetch(String referenceId);
}
