package com.aetherledger.exception;

public class IdempotencyKeyConflictException extends LedgerException {
    public IdempotencyKeyConflictException(String key) {
        super("Idempotency-Key '" + key + "' was already used with a different request body");
    }
}
