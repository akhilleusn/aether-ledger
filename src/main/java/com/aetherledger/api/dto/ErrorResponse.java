package com.aetherledger.api.dto;

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
public record ErrorResponse(
    int status,
    String errorCode,
    String message,
    Instant timestamp,
    String path
) {}
