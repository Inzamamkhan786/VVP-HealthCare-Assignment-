package com.vvh.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vvh.ledger.dto.TransactionRequest;
import com.vvh.ledger.dto.TransactionType;
import com.vvh.ledger.entity.Wallet;
import com.vvh.ledger.repository.TransactionRepository;
import com.vvh.ledger.repository.WalletRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =============================================================================
 * PaymentLedgerIntegrationTest
 * =============================================================================
 *
 * Full-stack Spring Boot integration tests executed against an in-memory H2
 * database.  No external infrastructure required.
 *
 * Three scenarios are covered:
 *   1. Single valid debit — baseline happy path.
 *   2. Concurrent idempotency — 3 identical transactionIds in parallel.
 *   3. Race condition under load — 10 concurrent $100 debits on a $500 wallet.
 *
 * Each test class execution gets a fresh application context via
 * {@code @DirtiesContext}, ensuring complete DB isolation between test methods.
 * =============================================================================
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PaymentLedgerIntegrationTest {

    private static final String ENDPOINT = "/api/v1/transactions/process";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private WalletRepository walletRepository;
    @Autowired private TransactionRepository transactionRepository;

    // ====================================================================== //
    //  Scenario 1                                                             //
    // ====================================================================== //

    @Test
    @Order(1)
    @DisplayName("Processes a single valid debit transaction successfully.")
    void singleValidDebitTransaction() throws Exception {

        // ── Arrange ───────────────────────────────────────────────────────────
        UUID walletId     = UUID.randomUUID();
        BigDecimal start  = new BigDecimal("1000.00");
        BigDecimal debit  = new BigDecimal("250.00");
        BigDecimal expect = new BigDecimal("750.00");

        walletRepository.saveAndFlush(new Wallet(walletId, start));

        TransactionRequest req = buildRequest(UUID.randomUUID().toString(), walletId, debit, TransactionType.DEBIT);

        System.out.println("\n══════════════════════════════════════════════════");
        System.out.println("TEST 1 ▶ Single valid debit transaction");
        System.out.println("  walletId      = " + walletId);
        System.out.println("  startBalance  = " + start);
        System.out.println("  debitAmount   = " + debit);
        System.out.println("  expectBalance = " + expect);
        System.out.println("══════════════════════════════════════════════════");

        // ── Act ───────────────────────────────────────────────────────────────
        MvcResult result = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        System.out.println("  RESPONSE: " + body);

        // ── Assert ────────────────────────────────────────────────────────────
        Wallet wallet = walletRepository.findById(walletId).orElseThrow();

        assertThat(wallet.getBalance().compareTo(expect))
                .as("Balance should be %s after a $250 debit on a $1000 wallet", expect)
                .isEqualTo(0);

        assertThat(transactionRepository.count()).isEqualTo(1);

        System.out.println("  ✅ PASS — final balance: " + wallet.getBalance());
        System.out.println("══════════════════════════════════════════════════\n");
    }

    // ====================================================================== //
    //  Scenario 2                                                             //
    // ====================================================================== //

    @Test
    @Order(2)
    @DisplayName("Sends 3 identical transactionIDs simultaneously. Ensures the balance is only deducted once.")
    void concurrentDuplicateTransactionIdsAreIdempotent() throws Exception {

        // ── Arrange ───────────────────────────────────────────────────────────
        UUID   walletId      = UUID.randomUUID();
        String transactionId = UUID.randomUUID().toString();
        BigDecimal start     = new BigDecimal("500.00");
        BigDecimal debit     = new BigDecimal("100.00");
        BigDecimal expect    = new BigDecimal("400.00");
        int threads          = 3;

        walletRepository.saveAndFlush(new Wallet(walletId, start));

        System.out.println("\n══════════════════════════════════════════════════");
        System.out.println("TEST 2 ▶ Concurrent idempotency (3 identical transactionIds)");
        System.out.println("  walletId      = " + walletId);
        System.out.println("  transactionId = " + transactionId);
        System.out.println("  startBalance  = " + start);
        System.out.println("  threads       = " + threads);
        System.out.println("  expectBalance = " + expect + " (deducted exactly once)");
        System.out.println("══════════════════════════════════════════════════");

        TransactionRequest req = buildRequest(transactionId, walletId, debit, TransactionType.DEBIT);
        String payload = objectMapper.writeValueAsString(req);

        // ── Act — fire all 3 threads at the same instant ──────────────────────
        ExecutorService executor    = Executors.newFixedThreadPool(threads);
        CountDownLatch  startLatch  = new CountDownLatch(1);   // synchronise start
        CountDownLatch  doneLatch   = new CountDownLatch(threads);

        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                startLatch.await();   // all threads wait here until main releases
                try {
                    MvcResult res = mockMvc.perform(post(ENDPOINT)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payload))
                            .andReturn();
                    int statusCode = res.getResponse().getStatus();
                    System.out.printf("  Thread-%d → HTTP %d%n", idx, statusCode);
                    return statusCode;
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        startLatch.countDown();           // release all threads simultaneously
        doneLatch.await();                // wait for all to complete
        executor.shutdown();

        // ── Assert ────────────────────────────────────────────────────────────
        // Collect statuses
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : futures) {
            statuses.add(f.get());
        }
        System.out.println("  All statuses: " + statuses);

        // All 3 responses must be HTTP 200 (idempotent replay for duplicates)
        long successCount = statuses.stream().filter(s -> s == 200).count();
        assertThat(successCount)
                .as("All 3 identical transactionId submissions should receive HTTP 200 (idempotent replay)")
                .isEqualTo(3);

        // Exactly ONE ledger record should exist
        long txCount = transactionRepository.count();
        assertThat(txCount)
                .as("Only 1 transaction record should be persisted despite 3 concurrent submissions")
                .isEqualTo(1);

        // Balance deducted exactly once
        Wallet wallet = walletRepository.findById(walletId).orElseThrow();
        assertThat(wallet.getBalance().compareTo(expect))
                .as("Balance should be %s (deducted only once despite 3 concurrent requests)", expect)
                .isEqualTo(0);

        System.out.println("  ✅ PASS — ledger records: " + txCount + ", final balance: " + wallet.getBalance());
        System.out.println("══════════════════════════════════════════════════\n");
    }

    // ====================================================================== //
    //  Scenario 3                                                             //
    // ====================================================================== //

    @Test
    @Order(3)
    @DisplayName("Sends 10 concurrent debit requests of $100 for a wallet with a $500 balance. Ensures the final balance is exactly $0 and 5 requests fail with insufficient funds.")
    void tenConcurrentDebitsOnFiveHundredDollarWallet() throws Exception {

        // ── Arrange ───────────────────────────────────────────────────────────
        UUID   walletId  = UUID.randomUUID();
        BigDecimal start = new BigDecimal("500.00");
        int threads      = 10;
        int debitAmount  = 100;

        walletRepository.saveAndFlush(new Wallet(walletId, start));

        System.out.println("\n══════════════════════════════════════════════════");
        System.out.println("TEST 3 ▶ 10 concurrent $100 debits on $500 wallet");
        System.out.println("  walletId     = " + walletId);
        System.out.println("  startBalance = " + start);
        System.out.println("  threads      = " + threads + " × $" + debitAmount);
        System.out.println("  expectedOK   = 5 (exhaust balance)");
        System.out.println("  expectedFail = 5 (insufficient funds)");
        System.out.println("══════════════════════════════════════════════════");

        ExecutorService executor   = Executors.newFixedThreadPool(threads);
        CountDownLatch  startLatch = new CountDownLatch(1);
        CountDownLatch  doneLatch  = new CountDownLatch(threads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount    = new AtomicInteger(0);

        List<Future<Void>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                // Each thread uses a DISTINCT transactionId — these are genuine
                // independent transactions, not idempotent duplicates.
                String uniqueTxId = UUID.randomUUID().toString();
                TransactionRequest req = buildRequest(
                        uniqueTxId, walletId,
                        new BigDecimal(debitAmount), TransactionType.DEBIT);
                String payload = objectMapper.writeValueAsString(req);

                startLatch.await();   // synchronised start
                try {
                    MvcResult res = mockMvc.perform(post(ENDPOINT)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payload))
                            .andReturn();
                    int status = res.getResponse().getStatus();
                    if (status == 200) {
                        successCount.incrementAndGet();
                        System.out.printf("  Thread-%02d → HTTP %d ✅ (debit applied)%n", idx, status);
                    } else {
                        failCount.incrementAndGet();
                        System.out.printf("  Thread-%02d → HTTP %d ❌ (insufficient funds)%n", idx, status);
                    }
                } finally {
                    doneLatch.countDown();
                }
                return null;
            }));
        }

        startLatch.countDown();   // fire all 10 threads simultaneously
        doneLatch.await();
        executor.shutdown();

        // Wait for all futures (re-throw any exceptions from worker threads)
        for (Future<Void> f : futures) {
            f.get();
        }

        // ── Assert ────────────────────────────────────────────────────────────
        Wallet wallet = walletRepository.findById(walletId).orElseThrow();

        System.out.println("\n  ── Results ────────────────────────────────────");
        System.out.println("  Successful debits : " + successCount.get());
        System.out.println("  Failed debits     : " + failCount.get());
        System.out.println("  Final balance     : " + wallet.getBalance());
        System.out.println("  ───────────────────────────────────────────────");

        assertThat(successCount.get())
                .as("Exactly 5 of 10 concurrent $100 debits should succeed on a $500 wallet")
                .isEqualTo(5);

        assertThat(failCount.get())
                .as("Exactly 5 of 10 concurrent $100 debits should fail with insufficient funds")
                .isEqualTo(5);

        assertThat(wallet.getBalance().compareTo(BigDecimal.ZERO))
                .as("Final wallet balance must be exactly $0.00 after 5 successful $100 debits")
                .isEqualTo(0);

        System.out.println("  ✅ PASS — 5 success, 5 fail, balance = " + wallet.getBalance());
        System.out.println("══════════════════════════════════════════════════\n");
    }

    // ====================================================================== //
    //  Helpers                                                               //
    // ====================================================================== //

    /**
     * Builds a {@link TransactionRequest} with the supplied parameters.
     */
    private TransactionRequest buildRequest(String transactionId,
                                            UUID walletId,
                                            BigDecimal amount,
                                            TransactionType type) {
        TransactionRequest req = new TransactionRequest();
        req.setTransactionId(transactionId);
        req.setUserId(walletId.toString());
        req.setAmount(amount);
        req.setType(type);
        return req;
    }
}
