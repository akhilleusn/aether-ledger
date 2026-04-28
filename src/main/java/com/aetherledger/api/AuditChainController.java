package com.aetherledger.api;

import com.aetherledger.api.dto.AuditChainEntryResponse;
import com.aetherledger.api.dto.AuditChainVerificationResponse;
import com.aetherledger.api.dto.ErrorResponse;
import com.aetherledger.api.dto.LedgerAuditChainLatestResponse;
import com.aetherledger.api.dto.PageResponse;
import com.aetherledger.service.AuditChainService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/ops/ledger-integrity/chain")
@RequiredArgsConstructor
@Tag(
    name = "Ledger Audit Chain",
    description = "Tamper-evident hash chain of ledger events. " +
        "Every significant ledger event produces an entry whose SHA-256 hash covers the " +
        "previous entry's hash plus the current event fields, forming an append-only chain. " +
        "Use POST /verify to detect any post-hoc modification of chain data.")
public class AuditChainController {

    private final AuditChainService auditChainService;

    // -------------------------------------------------------------------------
    // Chain tip
    // -------------------------------------------------------------------------

    @Operation(
        summary = "Get the latest audit chain entry",
        description = "Returns the most recently appended entry — the current tip of the hash chain. " +
            "The `currentHash` of this entry is the expected `previousHash` for the next append."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Latest chain entry"),
        @ApiResponse(responseCode = "404", description = "No entries have been written yet",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/latest")
    public LedgerAuditChainLatestResponse getLatest() {
        log.debug("Audit chain latest entry requested");
        return LedgerAuditChainLatestResponse.from(auditChainService.getLatest());
    }

    // -------------------------------------------------------------------------
    // Verification
    // -------------------------------------------------------------------------

    @Operation(
        summary = "Verify the audit chain",
        description = "Walks every entry in sequence-number order and re-derives each SHA-256 hash " +
            "from its stored fields. Returns `valid: true` only when every entry's hash matches and " +
            "every `previousHash` correctly references the preceding entry's `currentHash`. " +
            "A `valid: false` result with `brokenAtSequenceNumber` set pinpoints the first " +
            "tampered record. Note: this performs a full table scan."
    )
    @ApiResponse(responseCode = "200", description = "Verification result — check the `valid` field")
    @PostMapping("/verify")
    public AuditChainVerificationResponse verifyChain() {
        log.info("Audit chain verification requested");
        return auditChainService.verifyChain();
    }

    // -------------------------------------------------------------------------
    // Event lookup
    // -------------------------------------------------------------------------

    @Operation(
        summary = "List chain events for a specific entity",
        description = "Returns all audit chain entries for the given domain entity UUID " +
            "(a ledger transaction or a hold), newest first."
    )
    @ApiResponse(responseCode = "200", description = "Events for the entity (may be empty)")
    @GetMapping("/events/{entityId}")
    public List<AuditChainEntryResponse> getByEntityId(
            @Parameter(description = "UUID of the domain entity (LedgerTransaction or Hold)")
            @PathVariable UUID entityId) {
        log.debug("Audit chain events requested for entityId={}", entityId);
        return auditChainService.getByEntityId(entityId).stream()
            .map(AuditChainEntryResponse::from)
            .toList();
    }

    // -------------------------------------------------------------------------
    // Paginated listing
    // -------------------------------------------------------------------------

    @Operation(
        summary = "List all audit chain entries (paginated)",
        description = "Returns paginated audit chain entries, newest first (by sequenceNumber DESC). " +
            "Default page size is 20, maximum is 100."
    )
    @ApiResponse(responseCode = "200", description = "Page of chain entries")
    @GetMapping
    public PageResponse<AuditChainEntryResponse> listEntries(
            @Parameter(description = "Zero-based page number")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (1–100)")
            @RequestParam(defaultValue = "20") int size) {

        int safeSize = Math.min(Math.max(size, 1), 100);
        log.debug("Listing audit chain entries: page={} size={}", page, safeSize);
        return PageResponse.from(
            auditChainService.listEntries(PageRequest.of(page, safeSize)),
            AuditChainEntryResponse::from
        );
    }
}
