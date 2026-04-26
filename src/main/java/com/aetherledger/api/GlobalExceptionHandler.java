package com.aetherledger.api;

import com.aetherledger.api.dto.ErrorResponse;
import com.aetherledger.exception.AccountNotFoundException;
import com.aetherledger.exception.DuplicateAccountNameException;
import com.aetherledger.exception.DuplicateHoldReferenceIdException;
import com.aetherledger.exception.DuplicateReferenceIdException;
import com.aetherledger.exception.HoldNotCapturableException;
import com.aetherledger.exception.HoldNotFoundException;
import com.aetherledger.exception.HoldNotReleasableException;
import com.aetherledger.exception.InvalidTransactionRequestException;
import com.aetherledger.exception.LedgerTransactionNotFoundException;
import com.aetherledger.exception.TransactionAlreadyReversedException;
import com.aetherledger.exception.TransactionNotCompletableException;
import com.aetherledger.exception.TransactionNotFailableException;
import com.aetherledger.exception.ReconciliationRunNotFoundException;
import com.aetherledger.exception.TransactionNotReversibleException;
import com.aetherledger.exception.WebhookSubscriptionNotFoundException;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Translates domain and infrastructure exceptions into a uniform HTTP error
 * envelope ({@link ErrorResponse}).
 *
 * <p>Mapping table:
 * <pre>
 *   InvalidTransactionRequestException      →  400 BAD_REQUEST          INVALID_REQUEST
 *   MethodArgumentNotValidException         →  400 BAD_REQUEST          VALIDATION_FAILED
 *   MethodArgumentTypeMismatchException     →  400 BAD_REQUEST          INVALID_PATH_VARIABLE
 *   AccountNotFoundException                →  404 NOT_FOUND            ACCOUNT_NOT_FOUND
 *   DuplicateReferenceIdException           →  409 CONFLICT             DUPLICATE_REFERENCE_ID
 *   ObjectOptimisticLockingFailureException →  409 CONFLICT             CONCURRENT_MODIFICATION
 *   OptimisticLockException                 →  409 CONFLICT             CONCURRENT_MODIFICATION
 *   HttpRequestMethodNotSupportedException  →  405 METHOD_NOT_ALLOWED   METHOD_NOT_ALLOWED
 *   Exception (catch-all)                   →  500 INTERNAL_SERVER_ERROR INTERNAL_ERROR
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

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleConstraintViolation(
            ConstraintViolationException ex,
            HttpServletRequest request) {

        String detail = ex.getConstraintViolations().stream()
            .map(cv -> {
                String path = cv.getPropertyPath().toString();
                // Strip method-name prefix (e.g. "list.size" → "size")
                int dot = path.lastIndexOf('.');
                String param = dot >= 0 ? path.substring(dot + 1) : path;
                return param + ": " + cv.getMessage();
            })
            .sorted()
            .collect(Collectors.joining("; "));

        log.warn("Constraint violation on {}: {}", request.getRequestURI(), detail);
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail, request);
    }

    @ExceptionHandler(LedgerTransactionNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleLedgerTransactionNotFound(
            LedgerTransactionNotFoundException ex,
            HttpServletRequest request) {

        log.warn("Ledger transaction not found on {}: {}", request.getRequestURI(), ex.getMessage());
        return error(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", ex.getMessage(), request);
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

    @ExceptionHandler(TransactionNotCompletableException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorResponse handleTransactionNotCompletable(
            TransactionNotCompletableException ex,
            HttpServletRequest request) {

        log.warn("Transaction not completable on {}: {}", request.getRequestURI(), ex.getMessage());
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "TRANSACTION_NOT_COMPLETABLE", ex.getMessage(), request);
    }

    @ExceptionHandler(TransactionNotFailableException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorResponse handleTransactionNotFailable(
            TransactionNotFailableException ex,
            HttpServletRequest request) {

        log.warn("Transaction not failable on {}: {}", request.getRequestURI(), ex.getMessage());
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "TRANSACTION_NOT_FAILABLE", ex.getMessage(), request);
    }

    @ExceptionHandler(TransactionAlreadyReversedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleTransactionAlreadyReversed(
            TransactionAlreadyReversedException ex,
            HttpServletRequest request) {

        log.warn("Transaction already reversed on {}: {}", request.getRequestURI(), ex.getMessage());
        return error(HttpStatus.CONFLICT, "TRANSACTION_ALREADY_REVERSED", ex.getMessage(), request);
    }

    @ExceptionHandler(TransactionNotReversibleException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorResponse handleTransactionNotReversible(
            TransactionNotReversibleException ex,
            HttpServletRequest request) {

        log.warn("Transaction not reversible on {}: {}", request.getRequestURI(), ex.getMessage());
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "TRANSACTION_NOT_REVERSIBLE", ex.getMessage(), request);
    }

    @ExceptionHandler(ReconciliationRunNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleReconciliationRunNotFound(
            ReconciliationRunNotFoundException ex,
            HttpServletRequest request) {

        log.warn("Reconciliation run not found on {}: {}", request.getRequestURI(), ex.getMessage());
        return error(HttpStatus.NOT_FOUND, "RECONCILIATION_RUN_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(WebhookSubscriptionNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleWebhookSubscriptionNotFound(
            WebhookSubscriptionNotFoundException ex,
            HttpServletRequest request) {

        log.warn("Webhook subscription not found on {}: {}", request.getRequestURI(), ex.getMessage());
        return error(HttpStatus.NOT_FOUND, "WEBHOOK_SUBSCRIPTION_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(HoldNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleHoldNotFound(
            HoldNotFoundException ex,
            HttpServletRequest request) {

        log.warn("Hold not found on {}: {}", request.getRequestURI(), ex.getMessage());
        return error(HttpStatus.NOT_FOUND, "HOLD_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(DuplicateHoldReferenceIdException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleDuplicateHoldReferenceId(
            DuplicateHoldReferenceIdException ex,
            HttpServletRequest request) {

        log.warn("Duplicate hold referenceId on {}: {}", request.getRequestURI(), ex.getMessage());
        return error(HttpStatus.CONFLICT, "DUPLICATE_HOLD_REFERENCE_ID", ex.getMessage(), request);
    }

    @ExceptionHandler(HoldNotCapturableException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorResponse handleHoldNotCapturable(
            HoldNotCapturableException ex,
            HttpServletRequest request) {

        log.warn("Hold not capturable on {}: {}", request.getRequestURI(), ex.getMessage());
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "HOLD_NOT_CAPTURABLE", ex.getMessage(), request);
    }

    @ExceptionHandler(HoldNotReleasableException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorResponse handleHoldNotReleasable(
            HoldNotReleasableException ex,
            HttpServletRequest request) {

        log.warn("Hold not releasable on {}: {}", request.getRequestURI(), ex.getMessage());
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "HOLD_NOT_RELEASABLE", ex.getMessage(), request);
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

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {

        log.warn("Path variable type mismatch on {}: {}", request.getRequestURI(), ex.getMessage());
        return error(HttpStatus.BAD_REQUEST, "INVALID_PATH_VARIABLE",
            "Request path contains an invalid value.", request);
    }

    /**
     * Fired when two concurrent requests both read the same entity version and
     * the second writer's UPDATE matches zero rows (stale version).  Spring
     * translates the underlying {@link OptimisticLockException} from the JPA
     * provider to {@link ObjectOptimisticLockingFailureException} at the
     * repository boundary; we handle both to cover all propagation paths.
     *
     * <p>Callers should retry the operation after a short back-off.
     */
    @ExceptionHandler({
        ObjectOptimisticLockingFailureException.class,
        OptimisticLockException.class
    })
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleConcurrentModification(
            Exception ex,
            HttpServletRequest request) {

        log.warn("Optimistic lock conflict on {}: {}", request.getRequestURI(), ex.getMessage());
        return error(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
            "The resource was modified concurrently. Please retry.", request);
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
