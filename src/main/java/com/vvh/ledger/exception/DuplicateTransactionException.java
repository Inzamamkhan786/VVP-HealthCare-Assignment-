package com.vvh.ledger.exception;

/**
 * Thrown when a transaction with the same {@code transactionId} has already
 * been committed and the caller explicitly expects a 409 response rather than
 * an idempotent 200 replay.
 *
 * <p>Note: In the current implementation the service returns an idempotent
 * 200 replay. This exception is reserved for future policies that prefer
 * explicit rejection of duplicates.
 */
public class DuplicateTransactionException extends RuntimeException {

    private final String transactionId;

    public DuplicateTransactionException(String transactionId) {
        super("Transaction already exists: " + transactionId);
        this.transactionId = transactionId;
    }

    public String getTransactionId() { return transactionId; }
}
