package com.vvh.ledger.entity;

import com.vvh.ledger.dto.TransactionType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable ledger record for a single processed transaction.
 *
 * <p>The {@code transactionId} column carries a {@code UNIQUE} constraint
 * at the database level. This is the primary idempotency guard: only one
 * concurrent thread can successfully INSERT a row for a given
 * {@code transactionId}; all others will receive a
 * {@code DataIntegrityViolationException} which the service layer intercepts
 * to return the already-committed result.
 */
@Entity
@Table(
    name = "transactions",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_transactions_transaction_id",
        columnNames = "transaction_id"
    )
)
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Caller-supplied idempotency key. Must be globally unique per operation. */
    @Column(name = "transaction_id", nullable = false, updatable = false, length = 36)
    private String transactionId;

    @Column(name = "user_id", nullable = false, updatable = false, length = 36)
    private String userId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private TransactionType type;

    /** Balance of the wallet AFTER this transaction was applied. */
    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false, updatable = false, length = 20)
    private String status;

    /** JPA no-arg constructor. */
    protected Transaction() {}

    public Transaction(
            String transactionId,
            String userId,
            BigDecimal amount,
            TransactionType type,
            BigDecimal balanceAfter,
            String status) {
        this.transactionId = transactionId;
        this.userId = userId;
        this.amount = amount;
        this.type = type;
        this.balanceAfter = balanceAfter;
        this.createdAt = Instant.now();
        this.status = status;
    }

    // ------------------------------------------------------------------ //
    //  Accessors                                                          //
    // ------------------------------------------------------------------ //

    public UUID getId()                 { return id; }
    public String getTransactionId()    { return transactionId; }
    public String getUserId()           { return userId; }
    public BigDecimal getAmount()       { return amount; }
    public TransactionType getType()    { return type; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public Instant getCreatedAt()       { return createdAt; }
    public String getStatus()           { return status; }
}
