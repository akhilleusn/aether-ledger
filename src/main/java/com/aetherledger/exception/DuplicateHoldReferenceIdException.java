package com.aetherledger.exception;

public class DuplicateHoldReferenceIdException extends LedgerException {
    public DuplicateHoldReferenceIdException(String referenceId) {
        super("A hold with referenceId '" + referenceId + "' already exists");
    }
}
