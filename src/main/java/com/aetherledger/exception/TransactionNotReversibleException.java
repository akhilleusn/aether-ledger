package com.aetherledger.exception;

import com.aetherledger.domain.enums.TransactionStatus;

import java.util.UUID;

public class TransactionNotReversibleException extends LedgerException {

    public TransactionNotReversibleException(UUID transactionId, TransactionStatus status) {
        super("Transaction " + transactionId + " cannot be reversed: status is " + status
            + " (only SUCCESS transactions may be reversed)");
    }
}
