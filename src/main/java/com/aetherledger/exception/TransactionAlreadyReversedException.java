package com.aetherledger.exception;

import java.util.UUID;

public class TransactionAlreadyReversedException extends LedgerException {

    public TransactionAlreadyReversedException(UUID transactionId) {
        super("Transaction " + transactionId + " has already been reversed");
    }
}
