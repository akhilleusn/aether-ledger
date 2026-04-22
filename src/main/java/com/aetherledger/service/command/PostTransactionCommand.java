package com.aetherledger.service.command;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Immutable command object for posting a double-entry ledger transaction.
 *
 * <p>Structural validation (null checks, amount sign, same-account guard) is
 * performed inside {@code LedgerTransactionService} rather than via annotation
 * processors so that error messages are explicit, testable, and context-aware.
 *
 * @param debitAccountId   the account from which value flows out (will receive the debit entry)
 * @param creditAccountId  the account into which value flows in  (will receive the credit entry)
 * @param amount           the absolute monetary amount; must be strictly positive
 * @param referenceId      caller-supplied idempotency key; must be unique across all transactions
 */
public record PostTransactionCommand(
    UUID debitAccountId,
    UUID creditAccountId,
    BigDecimal amount,
    String referenceId
) {}
