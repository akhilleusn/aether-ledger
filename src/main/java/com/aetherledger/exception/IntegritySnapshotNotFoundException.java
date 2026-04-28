package com.aetherledger.exception;

import java.util.UUID;

public class IntegritySnapshotNotFoundException extends LedgerException {

    public IntegritySnapshotNotFoundException(UUID id) {
        super("No integrity snapshot found for id: " + id);
    }
}
