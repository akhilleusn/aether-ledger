package com.aetherledger.exception;

import com.aetherledger.domain.enums.TransactionStatus;

import java.util.UUID;

public class TransactionNotFailableException extends LedgerException {

    public TransactionNotFailableException(UUID transactionId, TransactionStatus status) {
        super("Transaction " + transactionId + " cannot be failed: status is " + status
            + " (only PENDING transactions may be failed)");
    }
}
