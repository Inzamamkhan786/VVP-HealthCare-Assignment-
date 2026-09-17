package com.vvh.ledger.repository;

import com.vvh.ledger.entity.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Wallet} entities.
 *
 * <h2>Pessimistic Locking Strategy</h2>
 * <p>{@link #findByIdWithLock(UUID)} acquires a {@code PESSIMISTIC_WRITE} lock
 * which translates to {@code SELECT … FOR UPDATE} at the SQL layer.
 * This serialises all concurrent write operations against the same wallet row,
 * preventing race conditions that would otherwise allow negative-balance debits.
 *
 * <p>Key properties of this lock:
 * <ul>
 *   <li>Only one transaction may hold the lock for a given wallet row at a time.</li>
 *   <li>Competing transactions are <em>blocked</em> (not rejected) until the holder commits.</li>
 *   <li>The lock is automatically released when the enclosing {@code @Transactional}
 *       method completes (commit or rollback).</li>
 *   <li>H2 fully supports {@code SELECT … FOR UPDATE}, so behaviour is consistent
 *       between test (H2) and production (PostgreSQL / MySQL).</li>
 * </ul>
 */
public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    /**
     * Loads a wallet and immediately acquires an exclusive row-level lock.
     *
     * <p><b>Must be called inside a {@code @Transactional} context.</b>
     * Spring Data JPA will throw {@code IllegalTransactionStateException}
     * if no active transaction exists.
     *
     * @param id the wallet UUID
     * @return the locked wallet, or empty if not found
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.id = :id")
    Optional<Wallet> findByIdWithLock(@Param("id") UUID id);
}
