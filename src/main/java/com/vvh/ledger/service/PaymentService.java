package com.vvh.ledger.service;

import com.vvh.ledger.dto.TransactionRequest;
import com.vvh.ledger.dto.TransactionResponse;
import com.vvh.ledger.entity.Transaction;
import com.vvh.ledger.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Core payment service that processes ledger transactions with strong
 * idempotency and concurrency guarantees.
 *
 * <h2>Idempotency Protocol (two-layer)</h2>
 * <ol>
 *   <li><b>Optimistic fast-path:</b> Query the {@code transactions} table by
 *       {@code transactionId} BEFORE delegating to the executor. If a committed
 *       record already exists, return it immediately — no locks, no writes.</li>
 *   <li><b>Constraint safety-net:</b> The actual mutation runs inside
 *       {@link PaymentExecutor#execute}, which uses {@code REQUIRES_NEW}
 *       transaction propagation. If two concurrent threads both pass layer-1
 *       simultaneously, only one INSERT succeeds; the loser's inner transaction
 *       rolls back cleanly and propagates a {@link DataIntegrityViolationException}.
 *       This outer method catches that exception, re-fetches the committed
 *       record in a fresh context, and returns it — exact-once semantics preserved.</li>
 * </ol>
 *
 * <h2>Why a separate PaymentExecutor?</h2>
 * <p>Spring AOP proxies intercept {@code @Transactional} only on calls that
 * go through the proxy boundary (i.e., method calls from <em>other</em> beans).
 * A {@code this.method()} call within the same class bypasses the proxy.
 * Extracting the inner transaction into {@link PaymentExecutor} ensures that
 * the {@code REQUIRES_NEW} semantics are correctly honoured by the AOP proxy,
 * isolating constraint-violation rollbacks from the caller's context.
 *
 * <h2>Pessimistic Locking</h2>
 * <p>{@link PaymentExecutor} acquires a {@code SELECT … FOR UPDATE} row lock
 * on the wallet before any balance mutation, preventing negative-balance races.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final TransactionRepository transactionRepository;
    private final PaymentExecutor executor;

    public PaymentService(TransactionRepository transactionRepository,
                          PaymentExecutor executor) {
        this.transactionRepository = transactionRepository;
        this.executor = executor;
    }

    /**
     * Processes a DEBIT or CREDIT transaction for the specified wallet.
     *
     * <p>This method intentionally has <b>no</b> {@code @Transactional}
     * annotation so it runs without an enclosing transaction. All DB work is
     * delegated to {@link PaymentExecutor#execute} which opens its own
     * {@code REQUIRES_NEW} transaction. This design allows the outer method
     * to catch a constraint-violation rollback and re-query the DB in a
     * clean, committed state.
     *
     * @param request validated inbound payload
     * @return the transaction result (original or idempotent replay)
     */
    public TransactionResponse process(TransactionRequest request) {

        final String txId = request.getTransactionId();

        log.debug("[LEDGER] Processing transactionId={} userId={} type={} amount={}",
                txId, request.getUserId(), request.getType(), request.getAmount());

        // ----------------------------------------------------------------
        // LAYER 1: Idempotency fast-path (no transaction, no lock).
        // ----------------------------------------------------------------
        var existing = transactionRepository.findByTransactionId(txId);
        if (existing.isPresent()) {
            log.info("[LEDGER] Fast-path replay — transactionId={} already committed", txId);
            return executor.toResponse(existing.get());
        }

        // ----------------------------------------------------------------
        // LAYER 2: Delegate to executor (REQUIRES_NEW transaction).
        //          If two threads raced past layer-1 simultaneously, only
        //          one INSERT will succeed. The loser's REQUIRES_NEW tx
        //          rolls back, propagating DataIntegrityViolationException.
        //          We catch it here and do an idempotent re-fetch.
        // ----------------------------------------------------------------
        try {
            return executor.execute(request);
        } catch (DataIntegrityViolationException dive) {
            // The inner REQUIRES_NEW transaction rolled back cleanly.
            // The winning thread's record is now visible in the DB.
            log.warn("[LEDGER] Concurrent duplicate detected for transactionId={} — returning committed record", txId);
            Transaction committed = transactionRepository.findByTransactionId(txId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Unexpected: constraint violation but no committed record found for transactionId=" + txId));
            return executor.toResponse(committed);
        }
    }
}
