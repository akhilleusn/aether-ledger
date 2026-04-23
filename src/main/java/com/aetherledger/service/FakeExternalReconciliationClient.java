package com.aetherledger.service;

import com.aetherledger.domain.enums.ExternalStatus;
import org.springframework.stereotype.Component;

/**
 * Deterministic fake implementation of {@link ExternalReconciliationClient}.
 *
 * <p>Simulates an external payment provider without any network calls:
 * <ul>
 *   <li>If {@code referenceId} contains {@code "FAIL"} → reports {@code FAILED}</li>
 *   <li>Otherwise → reports {@code SUCCESS}</li>
 * </ul>
 *
 * <p>The generated {@code externalReferenceId} is {@code "EXT-" + referenceId},
 * which is stable and deterministic for use in tests.
 */
@Component
public class FakeExternalReconciliationClient implements ExternalReconciliationClient {

    @Override
    public ExternalResult fetch(String referenceId) {
        ExternalStatus status = referenceId.contains("FAIL")
            ? ExternalStatus.FAILED
            : ExternalStatus.SUCCESS;
        return new ExternalResult("EXT-" + referenceId, status);
    }
}
