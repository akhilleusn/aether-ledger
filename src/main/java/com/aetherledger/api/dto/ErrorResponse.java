package com.aetherledger.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Uniform error envelope returned by {@code GlobalExceptionHandler} for all
 * non-2xx responses.
 *
 * <p>The {@code errorCode} field uses a machine-readable SCREAMING_SNAKE_CASE
 * token so clients can branch on error type without parsing the human-readable
 * {@code message}.
 *
 * @param status    HTTP status code (mirrors the response status line)
 * @param errorCode machine-readable error identifier
 * @param message   human-readable description of the problem
 * @param timestamp UTC instant when the error was generated
 * @param path      request URI that triggered the error
 */
@Schema(description = "Uniform error envelope returned for all non-2xx responses")
public record ErrorResponse(
    @Schema(description = "HTTP status code", example = "404")
    int status,
    @Schema(description = "Machine-readable error code for client branching logic", example = "ACCOUNT_NOT_FOUND")
    String errorCode,
    @Schema(description = "Human-readable description of the problem", example = "Account not found: 3fa85f64-5717-4562-b3fc-2c963f66afa6")
    String message,
    @Schema(description = "UTC timestamp when the error was generated")
    Instant timestamp,
    @Schema(description = "Request URI that triggered the error", example = "/api/v1/accounts/3fa85f64-5717-4562-b3fc-2c963f66afa6")
    String path
) {}
