package com.vvh.ledger.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Inbound payload for {@code POST /api/v1/transactions/process}.
 *
 * <p>All fields are mandatory. The caller is responsible for generating
 * a stable UUID {@code transactionId} that acts as the idempotency key.
 */
public class TransactionRequest {

    /**
     * Caller-generated idempotency key (UUID string).
     * Submitting the same value twice guarantees exactly-once ledger mutation.
     */
    @NotBlank(message = "transactionId must not be blank")
    private String transactionId;

    /** The wallet owner. Must map to an existing {@code Wallet} row. */
    @NotBlank(message = "userId must not be blank")
    private String userId;

    /** Positive monetary amount to debit or credit. */
    @NotNull(message = "amount must not be null")
    @DecimalMin(value = "0.01", message = "amount must be greater than 0")
    private BigDecimal amount;

    /** Whether to debit (withdraw) or credit (deposit) the wallet. */
    @NotNull(message = "type must not be null (DEBIT or CREDIT)")
    private TransactionType type;

    // ------------------------------------------------------------------ //
    //  Getters & Setters (required by Jackson)                           //
    // ------------------------------------------------------------------ //

    public String getTransactionId()          { return transactionId; }
    public void setTransactionId(String v)    { this.transactionId = v; }

    public String getUserId()                  { return userId; }
    public void setUserId(String v)            { this.userId = v; }

    public BigDecimal getAmount()              { return amount; }
    public void setAmount(BigDecimal v)        { this.amount = v; }

    public TransactionType getType()           { return type; }
    public void setType(TransactionType v)     { this.type = v; }
}
