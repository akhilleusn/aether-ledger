package com.aetherledger.exception;

import java.util.UUID;

/**
 * Thrown when a required account cannot be found in the ledger.
 */
public class AccountNotFoundException extends LedgerException {

    public AccountNotFoundException(UUID accountId, String field) {
        super(String.format("No account found for %s: %s", field, accountId));
    }
}
