package com.vvh.ledger.service;

import com.vvh.ledger.dto.TransactionRequest;
import com.vvh.ledger.dto.TransactionResponse;
import com.vvh.ledger.dto.TransactionType;
import com.vvh.ledger.entity.Transaction;
import com.vvh.ledger.entity.Wallet;
import com.vvh.ledger.exception.InsufficientFundsException;
import com.vvh.ledger.repository.TransactionRepository;
import com.vvh.ledger.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Executes the wallet mutation + ledger INSERT in its own independent
 * {@code REQUIRES_NEW} transaction, so that:
 *
 * <ol>
 *   <li>A {@code DataIntegrityViolationException} from the {@code UNIQUE}
 *       constraint on {@code transactionId} only rolls back THIS inner
 *       transaction — not the caller's transaction (if any).</li>
 *   <li>The Spring JPA {@code EntityManager} is NOT marked "rollback-only"
 *       in the calling thread's context, so the caller can freely perform
 *       a re-fetch SELECT after catching the exception.</li>
 * </ol>
 *
 * <p>This class is an internal implementation detail of
 * {@link PaymentService} and should not be called directly.
 */
@Service
public class PaymentExecutor {

    private static final Logger log = LoggerFactory.getLogger(PaymentExecutor.class);

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    public PaymentExecutor(WalletRepository walletRepository,
                           TransactionRepository transactionRepository) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Acquires a pessimistic lock on the wallet, applies the balance mutation,
     * and persists the ledger record — all inside a {@code REQUIRES_NEW}
     * transaction that is isolated from any existing transaction.
     *
     * <p>Callers must handle {@link org.springframework.dao.DataIntegrityViolationException}
     * to support idempotent duplicate detection.
     *
     * @param request the validated transaction request
     * @return the committed ledger record as a response DTO
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public TransactionResponse execute(TransactionRequest request) {

        final String txId   = request.getTransactionId();
        final String userId = request.getUserId();

        // Re-check inside the new transaction to handle the narrow TOCTOU window
        // where two threads both passed the fast-path check before either committed.
        var existing = transactionRepository.findByTransactionId(txId);
        if (existing.isPresent()) {
            log.info("[LEDGER] Idempotent replay (executor tx) — transactionId={}", txId);
            return toResponse(existing.get());
        }

        // Acquire PESSIMISTIC_WRITE lock → SELECT … FOR UPDATE
        UUID walletId = UUID.fromString(userId);
        Wallet wallet = walletRepository.findByIdWithLock(walletId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        "Wallet not found for userId=" + userId));

        log.debug("[LEDGER] Acquired PESSIMISTIC_WRITE lock on walletId={}, currentBalance={}",
                walletId, wallet.getBalance());

        // Apply business logic
        try {
            if (request.getType() == TransactionType.DEBIT) {
                wallet.debit(request.getAmount());
            } else {
                wallet.credit(request.getAmount());
            }
        } catch (IllegalStateException ex) {
            throw new InsufficientFundsException(ex.getMessage(), txId);
        }

        // Persist immutable ledger record.
        // If this fails with DataIntegrityViolationException (concurrent duplicate),
        // the exception propagates out of this REQUIRES_NEW transaction,
        // rolling it back (including the in-memory wallet mutation), and is caught
        // by PaymentService.process() which then performs an idempotent re-fetch.
        Transaction record = transactionRepository.saveAndFlush(new Transaction(
                txId,
                userId,
                request.getAmount(),
                request.getType(),
                wallet.getBalance(),
                "SUCCESS"
        ));

        log.info("[LEDGER] SUCCESS — transactionId={} type={} amount={} balanceAfter={}",
                txId, request.getType(), request.getAmount(), wallet.getBalance());

        return toResponse(record);
    }

    // ------------------------------------------------------------------ //
    //  Private helpers                                                    //
    // ------------------------------------------------------------------ //

    TransactionResponse toResponse(Transaction t) {
        return TransactionResponse.success(
                t.getTransactionId(),
                t.getUserId(),
                t.getAmount(),
                t.getType(),
                t.getBalanceAfter(),
                t.getCreatedAt()
        );
    }
}
