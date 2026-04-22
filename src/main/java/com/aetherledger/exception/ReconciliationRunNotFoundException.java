package com.aetherledger.exception;

import java.util.UUID;

public class ReconciliationRunNotFoundException extends LedgerException {

    public ReconciliationRunNotFoundException(UUID runId) {
        super("No reconciliation run found for id: " + runId);
    }
}
