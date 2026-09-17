package com.vvh.ledger.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Outbound payload returned by {@code POST /api/v1/transactions/process}.
 *
 * <p>On duplicate submissions (idempotent replay), the original response
 * is reconstructed from the persisted {@link com.vvh.ledger.entity.Transaction}
 * row and returned with HTTP 200 — the caller cannot distinguish a first call
 * from a replay.
 */
public class TransactionResponse {

    private String transactionId;
    private String userId;
    private BigDecimal amount;
    private TransactionType type;
    private BigDecimal balanceAfter;
    private String status;
    private Instant processedAt;
    private String message;

    // ------------------------------------------------------------------ //
    //  Factory helpers                                                    //
    // ------------------------------------------------------------------ //

    public static TransactionResponse success(
            String transactionId,
            String userId,
            BigDecimal amount,
            TransactionType type,
            BigDecimal balanceAfter,
            Instant processedAt) {
        TransactionResponse r = new TransactionResponse();
        r.transactionId = transactionId;
        r.userId        = userId;
        r.amount        = amount;
        r.type          = type;
        r.balanceAfter  = balanceAfter;
        r.status        = "SUCCESS";
        r.processedAt   = processedAt;
        r.message       = "Transaction processed successfully.";
        return r;
    }

    // ------------------------------------------------------------------ //
    //  Getters (Jackson serialisation)                                   //
    // ------------------------------------------------------------------ //

    public String getTransactionId()    { return transactionId; }
    public String getUserId()           { return userId; }
    public BigDecimal getAmount()       { return amount; }
    public TransactionType getType()    { return type; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public String getStatus()           { return status; }
    public Instant getProcessedAt()     { return processedAt; }
    public String getMessage()          { return message; }
}
