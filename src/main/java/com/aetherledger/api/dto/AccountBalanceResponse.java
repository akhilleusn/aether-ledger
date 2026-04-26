package com.aetherledger.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Account balance breakdown including reserved funds")
public record AccountBalanceResponse(

    @Schema(description = "Account UUID")
    UUID accountId,

    @Schema(description = "Total balance from posted ledger entries. Positive = net credit; negative = net debit.")
    BigDecimal currentBalance,

    @Schema(description = "Sum of all ACTIVE hold amounts. These funds are reserved but not yet moved in the ledger.")
    BigDecimal heldBalance,

    @Schema(description = "Funds available for new transactions: currentBalance − heldBalance.")
    BigDecimal availableBalance

) {}
