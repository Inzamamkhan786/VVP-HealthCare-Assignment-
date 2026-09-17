package com.vvh.ledger.exception;

import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Centralised exception → HTTP response mapping for the ledger service.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** HTTP 422 — wallet has insufficient funds for the requested DEBIT. */
    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientFunds(InsufficientFundsException ex) {
        log.warn("[LEDGER] Insufficient funds: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(errorBody(422, "INSUFFICIENT_FUNDS", ex.getMessage(), ex.getTransactionId()));
    }

    /** HTTP 409 — explicit duplicate rejection (reserved for 409-policy mode). */
    @ExceptionHandler(DuplicateTransactionException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicate(DuplicateTransactionException ex) {
        log.warn("[LEDGER] Duplicate transaction: {}", ex.getTransactionId());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(errorBody(409, "DUPLICATE_TRANSACTION", ex.getMessage(), ex.getTransactionId()));
    }

    /** HTTP 404 — wallet not found for the supplied userId. */
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(EntityNotFoundException ex) {
        log.warn("[LEDGER] Entity not found: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(errorBody(404, "NOT_FOUND", ex.getMessage(), null));
    }

    /** HTTP 400 — Bean Validation failures. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(errorBody(400, "VALIDATION_FAILED", errors, null));
    }

    /** HTTP 500 — catch-all for unexpected errors. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("[LEDGER] Unexpected error", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody(500, "INTERNAL_ERROR", "An unexpected error occurred.", null));
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                            //
    // ------------------------------------------------------------------ //

    private Map<String, Object> errorBody(int status, String code, String message, String transactionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status);
        body.put("error", code);
        body.put("message", message);
        if (transactionId != null) {
            body.put("transactionId", transactionId);
        }
        return body;
    }
}
