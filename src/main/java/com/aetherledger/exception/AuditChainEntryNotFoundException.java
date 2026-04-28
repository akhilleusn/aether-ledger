package com.aetherledger.exception;

public class AuditChainEntryNotFoundException extends LedgerException {

    public AuditChainEntryNotFoundException(long sequenceNumber) {
        super("Audit chain entry not found: sequenceNumber=" + sequenceNumber);
    }

    public AuditChainEntryNotFoundException() {
        super("Audit chain is empty — no entries have been recorded yet");
    }
}
