package com.aetherledger.exception;

public class DuplicateAccountNameException extends LedgerException {

    public DuplicateAccountNameException(String name) {
        super("An account with name '" + name + "' already exists");
    }
}
