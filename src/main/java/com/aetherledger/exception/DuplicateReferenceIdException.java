package com.aetherledger.exception;

/**
 * Thrown when a caller attempts to post a transaction whose {@code referenceId}
 * already exists in the ledger.
 *
 * <p>This exception is raised both by the pre-flight idempotency check and by
 * catching the {@code DataIntegrityViolationException} that results from the
 * UNIQUE database constraint — the latter guards against the race window between
 * the check and the INSERT.
 */
public class DuplicateReferenceIdException extends LedgerException {

    public DuplicateReferenceIdException(String referenceId) {
        super("A ledger transaction with referenceId '" + referenceId + "' already exists");
    }
}
