package com.aetherledger.api;

import com.aetherledger.api.dto.ErrorResponse;
import com.aetherledger.exception.AccountNotFoundException;
import com.aetherledger.exception.DuplicateAccountNameException;
import com.aetherledger.exception.DuplicateReferenceIdException;
import com.aetherledger.exception.InvalidTransactionRequestException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Translates domain and infrastructure exceptions into a uniform HTTP error
 * envelope ({@link ErrorResponse}).
 *
 * <p>Mapping table:
 * <pre>
 *   InvalidTransactionRequestException    →  400 BAD_REQUEST          INVALID_REQUEST
 *   MethodArgumentNotValidException       →  400 BAD_REQUEST          VALIDATION_FAILED
 *   AccountNotFoundException              →  404 NOT_FOUND            ACCOUNT_NOT_FOUND
 *   DuplicateReferenceIdException         →  409 CONFLICT             DUPLICATE_REFERENCE_ID
 *   HttpRequestMethodNotSupportedException→  405 METHOD_NOT_ALLOWED   METHOD_NOT_ALLOWED
 *   Exception (catch-all)                →  500 INTERNAL_SERVER_ERROR INTERNAL_ERROR
 * </pre>
 *
 * <p>The catch-all handler deliberately suppresses the internal exception
 * message in the response body to avoid leaking implementation details;
 * it is logged at ERROR level with the full stack trace.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidTransactionRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleInvalidRequest(
            InvalidTransactionRequestException ex,
            HttpServletRequest request) {

        log.warn("Invalid transaction request on {}: {}", request.getRequestURI(), ex.getMessage());
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", ex.getMessage(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpServletRequest request) {

        log.warn("Malformed request body on {}: {}", request.getRequestURI(), ex.getMessage());
        return error(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
            "Request body is malformed or contains an invalid value.", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidationFailure(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        String detail = ex.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .sorted()
            .collect(Collectors.joining("; "));

        log.warn("Bean validation failed on {}: {}", request.getRequestURI(), detail);
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail, request);
    }

    @ExceptionHandler(AccountNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleAccountNotFound(
            AccountNotFoundException ex,
            HttpServletRequest request) {

        log.warn("Account not found on {}: {}", request.getRequestURI(), ex.getMessage());
        return error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(DuplicateAccountNameException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleDuplicateAccountName(
            DuplicateAccountNameException ex,
            HttpServletRequest request) {

        log.warn("Duplicate account name on {}: {}", request.getRequestURI(), ex.getMessage());
        return error(HttpStatus.CONFLICT, "DUPLICATE_ACCOUNT_NAME", ex.getMessage(), request);
    }

    @ExceptionHandler(DuplicateReferenceIdException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleDuplicateReferenceId(
            DuplicateReferenceIdException ex,
            HttpServletRequest request) {

        log.warn("Duplicate referenceId on {}: {}", request.getRequestURI(), ex.getMessage());
        return error(HttpStatus.CONFLICT, "DUPLICATE_REFERENCE_ID", ex.getMessage(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ErrorResponse handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request) {

        log.warn("Method {} not supported on {}", ex.getMethod(), request.getRequestURI());
        return error(
            HttpStatus.METHOD_NOT_ALLOWED,
            "METHOD_NOT_ALLOWED",
            "Request method is not supported for this endpoint.",
            request
        );
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleUnexpected(
            Exception ex,
            HttpServletRequest request) {

        log.error("Unhandled exception on {}", request.getRequestURI(), ex);
        return error(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "An unexpected error occurred. Please contact support if the problem persists.",
            request
        );
    }

    // -------------------------------------------------------------------------
    // Builder helper
    // -------------------------------------------------------------------------

    private static ErrorResponse error(
            HttpStatus status,
            String errorCode,
            String message,
            HttpServletRequest request) {

        return new ErrorResponse(
            status.value(),
            errorCode,
            message,
            Instant.now(),
            request.getRequestURI()
        );
    }
}
