package com.aetherledger.exception;

/**
 * Base class for all domain-level exceptions in AetherLedger.
 *
 * <p>Extending {@link RuntimeException} means callers are not forced to declare
 * checked exceptions, while still allowing a single catch at the API boundary.
 */
public abstract class LedgerException extends RuntimeException {

    protected LedgerException(String message) {
        super(message);
    }

    protected LedgerException(String message, Throwable cause) {
        super(message, cause);
    }
}
