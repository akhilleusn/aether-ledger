package com.aetherledger.exception;

/**
 * Thrown when the input to a ledger operation is semantically invalid —
 * for example, a non-positive amount or a self-transfer between the same account.
 */
public class InvalidTransactionRequestException extends LedgerException {

    public InvalidTransactionRequestException(String message) {
        super(message);
    }
}
