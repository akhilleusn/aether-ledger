package com.aetherledger.exception;

public class LedgerTransactionNotFoundException extends LedgerException {

    public LedgerTransactionNotFoundException(String field, String value) {
        super("No ledger transaction found for " + field + ": " + value);
    }
}
