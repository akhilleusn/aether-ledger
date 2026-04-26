package com.aetherledger.api;

import com.aetherledger.api.dto.AccountBalanceResponse;
import com.aetherledger.api.dto.AccountCreatedResponse;
import com.aetherledger.api.dto.AccountLedgerEntryResponse;
import com.aetherledger.api.dto.AccountResponse;
import com.aetherledger.api.dto.CreateAccountRequest;
import com.aetherledger.api.dto.ErrorResponse;
import com.aetherledger.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Tag(name = "Accounts", description = "Create and query ledger accounts. Accounts are the named participants in double-entry transactions.")
public class AccountController {

    private final AccountService accountService;

    @Operation(
        summary = "Create an account",
        description = "Registers a new ledger account. The account name must be unique across all account types."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Account created successfully"),
        @ApiResponse(responseCode = "400", description = "Request body is missing required fields or fails validation",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "An account with this name already exists",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountCreatedResponse create(@RequestBody @Valid CreateAccountRequest request) {
        log.debug("Creating account: name={} type={}", request.name(), request.type());
        return AccountCreatedResponse.from(accountService.create(request.name(), request.type()));
    }

    @Operation(
        summary = "Get account by ID",
        description = "Returns the account details including the current balance, which is derived from all posted ledger entries."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Account found"),
        @ApiResponse(responseCode = "400", description = "Path variable is not a valid UUID",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Account not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}")
    public AccountResponse getById(
            @Parameter(description = "Account UUID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
            @PathVariable UUID id) {
        AccountService.AccountWithBalance awb = accountService.getById(id);
        return AccountResponse.from(awb.account(), awb.currentBalance());
    }

    @Operation(
        summary = "List all accounts",
        description = "Returns all accounts with their current balances. Results are unordered."
    )
    @ApiResponse(responseCode = "200", description = "List of accounts (may be empty)")
    @GetMapping
    public List<AccountResponse> list() {
        return accountService.list().stream()
            .map(awb -> AccountResponse.from(awb.account(), awb.currentBalance()))
            .toList();
    }

    @Operation(
        summary = "Get balance breakdown for an account",
        description = "Returns currentBalance (ledger sum), heldBalance (sum of ACTIVE holds), and availableBalance (current − held)."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Balance retrieved"),
        @ApiResponse(responseCode = "400", description = "Path variable is not a valid UUID",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Account not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}/balance")
    public AccountBalanceResponse getBalance(
            @Parameter(description = "Account UUID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
            @PathVariable UUID id) {
        AccountService.AccountBalance b = accountService.getBalance(id);
        return new AccountBalanceResponse(b.accountId(), b.currentBalance(), b.heldBalance(), b.availableBalance());
    }

    @Operation(
        summary = "Get ledger entry history for an account",
        description = "Returns all ledger entries that have been posted to this account, in no guaranteed order. " +
            "Positive amounts represent credits; negative amounts represent debits."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Entry list (may be empty if no transactions have been posted)"),
        @ApiResponse(responseCode = "400", description = "Path variable is not a valid UUID",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Account not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}/entries")
    public List<AccountLedgerEntryResponse> getLedgerHistory(
            @Parameter(description = "Account UUID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
            @PathVariable UUID id) {
        return accountService.getLedgerHistory(id).stream()
            .map(AccountLedgerEntryResponse::from)
            .toList();
    }
}
