package com.aetherledger.exception;

import com.aetherledger.domain.enums.TransactionStatus;

import java.util.UUID;

public class TransactionNotCompletableException extends LedgerException {

    public TransactionNotCompletableException(UUID transactionId, TransactionStatus status) {
        super("Transaction " + transactionId + " cannot be completed: status is " + status
            + " (only PENDING transactions may be completed)");
    }
}
