package com.aetherledger.exception;

import java.util.UUID;

public class HoldNotFoundException extends LedgerException {
    public HoldNotFoundException(UUID id) {
        super("Hold not found: id=" + id);
    }
}
