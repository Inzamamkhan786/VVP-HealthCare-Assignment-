package com.vvh.ledger.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Represents a user's wallet that holds a monetary balance.
 *
 * <p><b>Concurrency note:</b> All balance mutations must be performed while
 * holding a {@code PESSIMISTIC_WRITE} lock on this entity row (via
 * {@code WalletRepository#findByIdWithLock}). This prevents race conditions
 * that would otherwise allow negative-balance debits under concurrent load.
 */
@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    /**
     * Current wallet balance. Stored with exact decimal precision.
     * Never modified without holding the pessimistic DB lock.
     */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    /** JPA no-arg constructor. */
    protected Wallet() {}

    public Wallet(UUID id, BigDecimal balance) {
        this.id = id;
        this.balance = balance;
    }

    // ------------------------------------------------------------------ //
    //  Business methods                                                   //
    // ------------------------------------------------------------------ //

    /**
     * Debits the given amount from this wallet.
     *
     * @param amount positive value to debit
     * @throws IllegalStateException if the debit would cause a negative balance
     */
    public void debit(BigDecimal amount) {
        if (balance.compareTo(amount) < 0) {
            throw new IllegalStateException(
                    String.format("Insufficient funds: balance=%.2f, requested=%.2f", balance, amount));
        }
        this.balance = balance.subtract(amount);
    }

    /**
     * Credits the given amount to this wallet.
     *
     * @param amount positive value to credit
     */
    public void credit(BigDecimal amount) {
        this.balance = balance.add(amount);
    }

    // ------------------------------------------------------------------ //
    //  Accessors                                                          //
    // ------------------------------------------------------------------ //

    public UUID getId() { return id; }

    public BigDecimal getBalance() { return balance; }
}
