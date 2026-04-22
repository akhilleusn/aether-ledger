package com.aetherledger.service.command;

import java.util.UUID;

public record ReverseTransactionCommand(
    UUID originalTransactionId,
    String reversalReferenceId
) {}
