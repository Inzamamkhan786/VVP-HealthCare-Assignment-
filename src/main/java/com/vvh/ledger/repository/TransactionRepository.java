package com.vvh.ledger.repository;

import com.vvh.ledger.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Transaction} ledger records.
 *
 * <p>The {@code transactionId} column has a DB-level {@code UNIQUE} constraint
 * (declared on the entity). This provides a second line of idempotency defence:
 * if two threads both pass the initial existence check simultaneously, only one
 * INSERT will succeed; the other triggers a {@code DataIntegrityViolationException}
 * which the service layer catches and converts to an idempotent replay.
 */
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    /**
     * Looks up an already-committed transaction by its idempotency key.
     *
     * @param transactionId the caller-supplied idempotency UUID string
     * @return the existing transaction, or empty if this is a first-time call
     */
    Optional<Transaction> findByTransactionId(String transactionId);
}
