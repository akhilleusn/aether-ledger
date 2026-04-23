package com.aetherledger.service;

import com.aetherledger.domain.enums.ExternalStatus;

/**
 * The result returned by {@link ExternalReconciliationClient#fetch}.
 *
 * @param externalReferenceId the provider's own reference for the transaction
 * @param externalStatus      the status as reported by the provider
 */
public record ExternalResult(
    String externalReferenceId,
    ExternalStatus externalStatus
) {}
