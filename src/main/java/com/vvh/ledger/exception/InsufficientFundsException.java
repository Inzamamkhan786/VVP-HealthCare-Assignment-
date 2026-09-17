package com.vvh.ledger.exception;

/**
 * Thrown when a DEBIT request would reduce the wallet balance below zero.
 * Maps to HTTP 422 Unprocessable Entity via {@link GlobalExceptionHandler}.
 */
public class InsufficientFundsException extends RuntimeException {

    private final String transactionId;

    public InsufficientFundsException(String message, String transactionId) {
        super(message);
        this.transactionId = transactionId;
    }

    public String getTransactionId() { return transactionId; }
}
