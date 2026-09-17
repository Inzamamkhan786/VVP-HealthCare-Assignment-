# DECISIONS.md — Payment Ledger Service

---

## Section 1: Concurrency & Database Locking Strategy

### 1.1 The Problem

A payment ledger under concurrent load faces two distinct failure modes:

| Failure Mode | Scenario | Consequence |
|---|---|---|
| **Lost Update / Negative Balance** | Thread A reads balance=500, Thread B reads balance=500, both debit $300 and write 200 — net balance goes to -100 | Funds manufactured from thin air |
| **Duplicate Execution** | Same `transactionId` arrives twice (network retry, client double-click) and both succeed | User charged twice |

Neither failure mode can be safely solved with JVM-level locks (`synchronized`, `ReentrantLock`, `Semaphore`). JVM locks are per-process and vanish on node restart or scale-out. A horizontally-scaled service needs the database itself to be the arbiter.

---

### 1.2 Pessimistic Write Locking (`SELECT … FOR UPDATE`)

The `WalletRepository` exposes:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT w FROM Wallet w WHERE w.id = :id")
Optional<Wallet> findByIdWithLock(@Param("id") UUID id);
```

At the SQL layer, Spring Data JPA translates this to:

```sql
SELECT * FROM wallets WHERE id = ? FOR UPDATE
```

#### How it works step-by-step

```
Thread A (txn-1)               Thread B (txn-2)
─────────────────────          ─────────────────────
BEGIN
SELECT … FOR UPDATE            BEGIN
  → acquires row lock
  balance = 500
                               SELECT … FOR UPDATE
                                 → BLOCKED (waiting for row lock)
debit(100) → balance = 400
UPDATE wallets SET balance=400
COMMIT / release lock
                                 → lock granted
                               balance = 400  ← sees committed value, NOT 500
                               debit(100) → balance = 300
                               COMMIT
```

#### Why not Optimistic Locking?

Optimistic locking (`@Version int version`) detects conflicts after the fact and throws `OptimisticLockingFailureException`. Under Test 3 (10 threads, 5 should succeed), optimistic locking would cause some of those 5 to fail with a lock exception rather than an insufficient-funds error, making the outcome non-deterministic. Pessimistic locking provides deterministic serialisation.

#### H2 Compatibility

H2 fully supports `SELECT … FOR UPDATE` in its default `LOCK_MODE=3` (READ_COMMITTED) setting. The `MODE=PostgreSQL` URL parameter aligns H2's SQL dialect with production behaviour.

---

### 1.3 Two-Layer Idempotency

#### Layer 1 — Optimistic fast-path (no lock)

Before acquiring any row lock, `PaymentService.process()` queries:

```java
transactionRepository.findByTransactionId(txId)
```

If a committed record exists, the original response is returned immediately — zero DB writes, zero lock contention.

#### Layer 2 — Database `UNIQUE` constraint safety net

```sql
ALTER TABLE transactions
  ADD CONSTRAINT uq_transactions_transaction_id UNIQUE (transaction_id);
```

In the narrow TOCTOU window where two threads both pass Layer 1 and attempt to INSERT, only one succeeds. The other receives a `DataIntegrityViolationException`. The service catches this, re-fetches the committed row, and returns the same response — exact-once semantics preserved.

#### Combined flow diagram

```
Incoming request (transactionId=X)
         │
         ▼
  findByTransactionId(X) ─── found? ──► return cached response (HTTP 200)
         │ not found
         ▼
  findByIdWithLock(walletId)   ← acquires PESSIMISTIC_WRITE lock
         │
         ▼
  Apply debit/credit
         │
         ▼
  saveAndFlush(Transaction) ─── DataIntegrityViolationException?
         │ no                           │ yes
         ▼                             ▼
  return new response         re-fetch + return cached response
```

---

### 1.4 Transaction Isolation Level

`@Transactional(isolation = Isolation.READ_COMMITTED)` is explicit. Combined with `PESSIMISTIC_WRITE` row lock, the effective isolation for locked rows is equivalent to SERIALIZABLE — without the overhead of full serialisable isolation that would cause table-level locks and deadlocks under high load.

---

## Section 2: AI Pitfall & Reflection Analysis

### 2.1 Using JVM-Level Locks Instead of DB Locks

**The failure:** Many AI solutions synchronise on wallet ID using `ConcurrentHashMap<UUID, ReentrantLock>`. This fails on horizontal scaling (each pod has its own HashMap) and on application restart (locks are lost).

**The correct approach:** `@Lock(LockModeType.PESSIMISTIC_WRITE)` pushes serialisation to the database — the only shared state across all instances.

---

### 2.2 Misunderstanding H2 Transaction Isolation

**The failure:** Using `jdbc:h2:mem:testdb` without `DB_CLOSE_DELAY=-1` causes the in-memory DB to be destroyed when the first connection closes, producing a fresh empty schema — breaking tests that rely on persisted seed data.

**The correct approach:** `jdbc:h2:mem:ledger;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL`

---

### 2.3 Incorrect CountDownLatch Usage in Tests

**The failure:** A common AI mistake is using only one latch for completion-detection without a *start* latch. Threads submitted to an `ExecutorService` start immediately upon `submit()`, so some threads run sequentially before others are even submitted — no real concurrency, no race conditions tested.

**The correct approach:** Two latches:
1. `startLatch(1)` — all workers call `startLatch.await()`, main releases with `startLatch.countDown()` to fire all threads simultaneously.
2. `doneLatch(N)` — each worker calls `doneLatch.countDown()` on completion; main awaits `doneLatch.await()`.

---

### 2.4 Forgetting `@DirtiesContext` Between Tests

**The failure:** Without `@DirtiesContext`, the H2 DB persists across test methods. A wallet debited in Test 1 bleeds into Test 2 and Test 3, causing spurious failures.

**The correct approach:** `@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)` recreates the context (and H2 schema) before each test. `@Transactional` rollback alone cannot handle concurrent threads because each worker thread has its own JPA transaction that the test's transaction manager cannot roll back.

---

### 2.5 Idempotency via `ConcurrentHashMap` Instead of DB Constraint

**The failure:** In-memory caches for idempotency fail on restart, are not shared across pods, and have no durability guarantee.

**The correct approach:** `UNIQUE` constraint on `transactions.transaction_id` is durable, shared, and ACID. Application-level check is a performance optimisation, not the safety guarantee.

---

### 2.6 TOCTOU Race Condition in Idempotency Check

**The failure:** A naive `if (exists) return; else save()` allows two threads to both evaluate `exists = false` simultaneously and both INSERT — charging the user twice.

**The correct approach:** Always pair the application-level existence check with a DB `UNIQUE` constraint and catch `DataIntegrityViolationException` as the final guard.

---

*Payment Ledger Service v1.0.0 — Architectural Decision Record*
