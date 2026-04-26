package com.aetherledger.api;

import com.aetherledger.api.dto.CreateHoldRequest;
import com.aetherledger.api.dto.ErrorResponse;
import com.aetherledger.api.dto.HoldResponse;
import com.aetherledger.service.HoldService;
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

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/holds")
@RequiredArgsConstructor
@Tag(name = "Holds", description = "Place, capture, and release balance holds (reserved funds) on accounts.")
public class HoldController {

    private final HoldService holdService;

    @Operation(
        summary = "Place a hold",
        description = "Reserves funds on an account. No ledger entries are written; availableBalance decreases immediately."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Hold placed successfully"),
        @ApiResponse(responseCode = "400", description = "Request body fails validation",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Account not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "A hold with this referenceId already exists",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResponse create(@RequestBody @Valid CreateHoldRequest request) {
        log.debug("Creating hold: accountId={} amount={} referenceId='{}'",
            request.accountId(), request.amount(), request.referenceId());
        return HoldResponse.from(
            holdService.create(request.accountId(), request.amount(), request.referenceId()));
    }

    @Operation(summary = "Get hold by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Hold found"),
        @ApiResponse(responseCode = "400", description = "Path variable is not a valid UUID",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Hold not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}")
    public HoldResponse getById(
            @Parameter(description = "Hold UUID")
            @PathVariable UUID id) {
        return HoldResponse.from(holdService.getById(id));
    }

    @Operation(
        summary = "Capture a hold",
        description = "Moves funds from the account to the Settlement Clearing account via a real ledger transaction. Hold transitions to CAPTURED."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Hold captured"),
        @ApiResponse(responseCode = "400", description = "Path variable is not a valid UUID",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Hold not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "422", description = "Hold is not in ACTIVE status",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/capture")
    public HoldResponse capture(
            @Parameter(description = "Hold UUID")
            @PathVariable UUID id) {
        log.debug("Capturing hold: id={}", id);
        return HoldResponse.from(holdService.capture(id));
    }

    @Operation(
        summary = "Release a hold",
        description = "Cancels the fund reservation. No ledger movement. availableBalance is restored. Hold transitions to RELEASED."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Hold released"),
        @ApiResponse(responseCode = "400", description = "Path variable is not a valid UUID",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Hold not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "422", description = "Hold is not in ACTIVE status",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/release")
    public HoldResponse release(
            @Parameter(description = "Hold UUID")
            @PathVariable UUID id) {
        log.debug("Releasing hold: id={}", id);
        return HoldResponse.from(holdService.release(id));
    }
}
