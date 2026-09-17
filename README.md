# 💳 Payment Ledger Service

> **Production-grade, idempotent payment ledger microservice** built with Spring Boot 3.x, Java 17+, and H2 In-Memory Database.  
> Handles concurrent debit/credit transactions with database-level pessimistic locking and exact-once idempotency guarantees.

---

## 📋 Table of Contents

1. [Project Overview](#-project-overview)
2. [Tech Stack](#-tech-stack)
3. [Architecture Overview](#-architecture-overview)
4. [Project Structure](#-project-structure)
5. [Core Concepts](#-core-concepts)
   - [Idempotency](#idempotency-guarantee)
   - [Pessimistic Locking](#pessimistic-locking)
   - [Transaction Flow](#transaction-flow)
6. [API Reference](#-api-reference)
7. [How to Run](#-how-to-run)
8. [Running the Tests](#-running-the-tests)
9. [Test Suite Explained](#-test-suite-explained)
10. [Error Responses](#-error-responses)
11. [Configuration](#-configuration)
12. [Architectural Decisions](#-architectural-decisions)

---

## 🧭 Project Overview

This service solves a critical real-world problem in payment systems:

> **Problem:** A payment gateway sends duplicate webhook payloads concurrently due to network retries. The ledger must guarantee that a wallet is debited or credited **exactly once**, even when 3 identical requests arrive within milliseconds of each other — and must also prevent **negative balances** when 10 concurrent debit threads race against a wallet with insufficient funds.

### Key Guarantees

| Guarantee | Mechanism |
|---|---|
| ✅ **Exactly-once execution** | DB `UNIQUE` constraint + application fast-path check |
| ✅ **No negative balances** | `SELECT … FOR UPDATE` (pessimistic DB lock) |
| ✅ **No JVM-level locks** | Locking at DB layer — survives horizontal scale-out |
| ✅ **Zero external config** | H2 in-memory DB, runs instantly in IntelliJ |
| ✅ **All 3 JUnit 5 tests pass** | `BUILD SUCCESS` verified locally |

---

## 🛠 Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 22 (compatible with Java 17+) |
| Framework | Spring Boot 3.2.5 |
| Persistence | Spring Data JPA + Hibernate |
| Database | H2 In-Memory (PostgreSQL compatibility mode) |
| Validation | Jakarta Bean Validation (`spring-boot-starter-validation`) |
| Testing | JUnit 5, Spring Boot Test, MockMvc |
| Build | Apache Maven 3.x |

---

## 🏗 Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                      HTTP Layer                             │
│   POST /api/v1/transactions/process                         │
│         TransactionController  (@RestController)            │
└────────────────────────┬────────────────────────────────────┘
                         │ @Valid TransactionRequest
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                   Service Layer                             │
│                                                             │
│   PaymentService  (no @Transactional — no outer tx)        │
│   ┌─────────────────────────────────────────────────────┐   │
│   │  LAYER 1: Fast-path idempotency check               │   │
│   │  findByTransactionId() → if found, return cached    │   │
│   └──────────────────────┬──────────────────────────────┘   │
│                          │ not found                        │
│   ┌──────────────────────▼──────────────────────────────┐   │
│   │  PaymentExecutor  (@Transactional REQUIRES_NEW)     │   │
│   │  ├─ LAYER 2: Re-check inside new transaction        │   │
│   │  ├─ findByIdWithLock() → SELECT … FOR UPDATE        │   │
│   │  ├─ wallet.debit() / wallet.credit()                │   │
│   │  └─ transactionRepository.saveAndFlush()            │   │
│   └──────────────────────┬──────────────────────────────┘   │
│                          │ DataIntegrityViolationException?  │
│   ┌──────────────────────▼──────────────────────────────┐   │
│   │  LAYER 3: Catch constraint violation                 │   │
│   │  Re-fetch committed record → idempotent HTTP 200    │   │
│   └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                  Database Layer (H2)                        │
│                                                             │
│   wallets table                transactions table           │
│   ┌──────────────────┐          ┌─────────────────────────┐ │
│   │ id (PK, UUID)    │          │ id (PK, UUID)           │ │
│   │ balance NUMERIC  │          │ transaction_id (UNIQUE) │ │
│   └──────────────────┘          │ user_id                 │ │
│                                 │ amount                  │ │
│   SELECT … FOR UPDATE           │ type (DEBIT/CREDIT)     │ │
│   serialises all writes         │ balance_after           │ │
│   to the same wallet row        │ status                  │ │
│                                 │ created_at              │ │
│                                 └─────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

---

## 📁 Project Structure

```
payment-ledger/
├── .gitignore
├── DECISIONS.md                         ← Architectural decisions & AI pitfall analysis
├── README.md                            ← This file
├── pom.xml                              ← Maven dependencies (Spring Boot 3.2.5)
│
└── src/
    ├── main/
    │   ├── java/com/vvh/ledger/
    │   │   ├── PaymentLedgerApplication.java        ← Spring Boot entry point
    │   │   │
    │   │   ├── controller/
    │   │   │   └── TransactionController.java       ← REST endpoint
    │   │   │
    │   │   ├── dto/
    │   │   │   ├── TransactionRequest.java          ← Inbound payload + validation
    │   │   │   ├── TransactionResponse.java         ← Outbound response DTO
    │   │   │   └── TransactionType.java             ← DEBIT | CREDIT enum
    │   │   │
    │   │   ├── entity/
    │   │   │   ├── Wallet.java                      ← Balance entity with debit/credit guards
    │   │   │   └── Transaction.java                 ← Ledger record (UNIQUE transactionId)
    │   │   │
    │   │   ├── exception/
    │   │   │   ├── InsufficientFundsException.java  ← Thrown on negative-balance debit
    │   │   │   ├── DuplicateTransactionException.java ← Reserved for 409 policy
    │   │   │   └── GlobalExceptionHandler.java      ← Maps exceptions → HTTP responses
    │   │   │
    │   │   ├── repository/
    │   │   │   ├── WalletRepository.java            ← findByIdWithLock() PESSIMISTIC_WRITE
    │   │   │   └── TransactionRepository.java       ← findByTransactionId() for idempotency
    │   │   │
    │   │   └── service/
    │   │       ├── PaymentService.java              ← Orchestrator: idempotency logic
    │   │       └── PaymentExecutor.java             ← REQUIRES_NEW tx: lock + mutate + insert
    │   │
    │   └── resources/
    │       └── application.properties              ← H2 datasource + JPA config
    │
    └── test/
        └── java/com/vvh/ledger/
            └── PaymentLedgerIntegrationTest.java   ← 3 JUnit 5 integration tests
```

---

## 🔑 Core Concepts

### Idempotency Guarantee

**What it means:** Submitting the same `transactionId` 2 or 200 times produces exactly the same result as submitting it once. The wallet balance is changed only once.

**How it's implemented (3 layers):**

```
Layer 1 — Fast-path SELECT (PaymentService)
  ↓ findByTransactionId(txId)
  ↓ If found → return cached response immediately (no lock, no write)

Layer 2 — Re-check inside REQUIRES_NEW tx (PaymentExecutor)
  ↓ Handles the narrow TOCTOU window where 2 threads both passed Layer 1
  ↓ Re-queries before acquiring the wallet lock

Layer 3 — UNIQUE constraint catch (PaymentService)
  ↓ If two threads both attempted INSERT, the DB rejects the duplicate
  ↓ DataIntegrityViolationException → inner tx rolls back cleanly
  ↓ Outer method re-fetches the committed record → HTTP 200 (idempotent replay)
```

---

### Pessimistic Locking

The `WalletRepository` uses a `PESSIMISTIC_WRITE` JPA lock:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT w FROM Wallet w WHERE w.id = :id")
Optional<Wallet> findByIdWithLock(@Param("id") UUID id);
```

This translates to SQL:
```sql
SELECT id, balance FROM wallets WHERE id = ? FOR UPDATE
```

**Effect under concurrency:**

```
Thread A                          Thread B
────────────────────              ────────────────────
BEGIN
SELECT … FOR UPDATE               BEGIN
  → lock acquired
  balance = 500                   SELECT … FOR UPDATE
                                    → BLOCKED ⏳
debit(100) → balance = 400
UPDATE wallets SET balance = 400
COMMIT → lock released
                                    → lock acquired
                                  balance = 400  ← committed value!
                                  debit(100) → balance = 300
                                  COMMIT
```

> ⚠️ Without `FOR UPDATE`, both threads read `500` and both write `400` — a classic **lost update** that enables negative balances.

---

### Transaction Flow

```
Client sends:
POST /api/v1/transactions/process
{
  "transactionId": "abc-123",
  "userId":        "wallet-uuid",
  "amount":        100.00,
  "type":          "DEBIT"
}

         ┌─── Already seen? ───────────────────────────────────┐
         │    findByTransactionId("abc-123") → found           │
         │    Return original response  HTTP 200 ✅            │
         └─────────────────────────────────────────────────────┘

         ┌─── New transaction ─────────────────────────────────┐
         │    Open REQUIRES_NEW transaction                    │
         │    SELECT * FROM wallets WHERE id=? FOR UPDATE      │
         │    wallet.debit(100)  [throws if balance < amount]  │
         │    INSERT INTO transactions (transactionId=abc-123) │
         │    COMMIT  HTTP 200 ✅                              │
         └─────────────────────────────────────────────────────┘

         ┌─── Concurrent duplicate ────────────────────────────┐
         │    Two threads both pass the fast-path check        │
         │    Thread A wins → INSERT succeeds                  │
         │    Thread B → UNIQUE constraint violation           │
         │    Inner tx rolls back (no wallet change committed) │
         │    Outer method re-fetches Thread A's record        │
         │    Return idempotent response  HTTP 200 ✅          │
         └─────────────────────────────────────────────────────┘

         ┌─── Insufficient funds ──────────────────────────────┐
         │    wallet.debit(100) throws IllegalStateException   │
         │    → InsufficientFundsException                     │
         │    HTTP 422 Unprocessable Entity ❌                 │
         └─────────────────────────────────────────────────────┘
```

---

## 📡 API Reference

### `POST /api/v1/transactions/process`

Process a debit or credit transaction for a wallet.

#### Request Body

```json
{
  "transactionId": "550e8400-e29b-41d4-a716-446655440000",
  "userId":        "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "amount":        250.00,
  "type":          "DEBIT"
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `transactionId` | UUID string | ✅ | Idempotency key — caller generates and retains this |
| `userId` | UUID string | ✅ | Identifies the wallet (must exist in DB) |
| `amount` | Decimal (> 0) | ✅ | Monetary amount to debit or credit |
| `type` | `DEBIT` \| `CREDIT` | ✅ | Direction of the ledger entry |

#### Success Response — `HTTP 200 OK`

```json
{
  "transactionId": "550e8400-e29b-41d4-a716-446655440000",
  "userId":        "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "amount":        250.00,
  "type":          "DEBIT",
  "balanceAfter":  750.00,
  "status":        "SUCCESS",
  "processedAt":   "2026-09-17T14:45:32.123Z",
  "message":       "Transaction processed successfully."
}
```

> **Idempotent replay:** Submitting the same `transactionId` again returns this exact same response with `HTTP 200` — no duplicate debit/credit applied.

---

## 🚀 How to Run

### Prerequisites

| Tool | Version |
|---|---|
| Java JDK | 17+ (tested on Java 22) |
| Apache Maven | 3.6+ |

> No database setup needed — H2 runs entirely in-memory.

### Start the Application

```bash
cd payment-ledger
mvn spring-boot:run
```

The service starts on **port 8080** by default.

### Test the Endpoint (cURL)

**1. Create a wallet first** (via H2 console or seed data):

Open the H2 console at `http://localhost:8080/h2-console`  
JDBC URL: `jdbc:h2:mem:ledger`  
Username: `sa` | Password: *(empty)*

```sql
INSERT INTO wallets (id, balance) VALUES ('6ba7b810-9dad-11d1-80b4-00c04fd430c8', 1000.00);
```

**2. Process a debit:**

```bash
curl -X POST http://localhost:8080/api/v1/transactions/process \
  -H "Content-Type: application/json" \
  -d '{
    "transactionId": "550e8400-e29b-41d4-a716-446655440000",
    "userId":        "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
    "amount":        250.00,
    "type":          "DEBIT"
  }'
```

**3. Replay the same request (idempotency):**

```bash
# Same transactionId → same response, balance unchanged
curl -X POST http://localhost:8080/api/v1/transactions/process \
  -H "Content-Type: application/json" \
  -d '{
    "transactionId": "550e8400-e29b-41d4-a716-446655440000",
    "userId":        "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
    "amount":        250.00,
    "type":          "DEBIT"
  }'
```

---

## 🧪 Running the Tests

> **No external setup required.** Tests use H2 in-memory DB — just run and they pass.

```bash
cd payment-ledger
mvn clean test
```

### Expected Output

```
══════════════════════════════════════════════════
TEST 1 ▶ Single valid debit transaction
  startBalance  = 1000.00  |  debitAmount = 250.00
  ✅ PASS — final balance: 750.0000
══════════════════════════════════════════════════

══════════════════════════════════════════════════
TEST 2 ▶ Concurrent idempotency (3 identical transactionIds)
  All statuses: [200, 200, 200]
  ✅ PASS — ledger records: 1, final balance: 400.0000
══════════════════════════════════════════════════

══════════════════════════════════════════════════
TEST 3 ▶ 10 concurrent $100 debits on $500 wallet
  Successful debits : 5
  Failed debits     : 5
  Final balance     : 0.0000
  ✅ PASS — 5 success, 5 fail, balance = 0.0000
══════════════════════════════════════════════════

[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Running in IntelliJ IDEA

1. Open the `payment-ledger/` folder as a Maven project
2. Let IntelliJ import dependencies automatically
3. Right-click `PaymentLedgerIntegrationTest` → **Run**
4. All 3 tests appear green in the JUnit runner

---

## 🔬 Test Suite Explained

### Test 1 — Happy Path
```java
@DisplayName("Processes a single valid debit transaction successfully.")
```
- Creates a wallet with `$1000`
- Sends a `DEBIT` of `$250`
- Asserts: HTTP 200, balance = `$750`, exactly 1 DB record

---

### Test 2 — Concurrent Idempotency
```java
@DisplayName("Sends 3 identical transactionIDs simultaneously. Ensures the balance is only deducted once.")
```
- Creates a wallet with `$500`
- Fires **3 threads simultaneously** using `ExecutorService` + `CountDownLatch` — all with the **same `transactionId`**
- Asserts:
  - All 3 receive `HTTP 200` (idempotent replay, not errors)
  - Only **1** ledger record in the DB
  - Balance = `$400` (deducted exactly once)

**CountDownLatch Pattern Used:**
```java
CountDownLatch startLatch = new CountDownLatch(1);  // synchronise launch
CountDownLatch doneLatch  = new CountDownLatch(3);  // wait for completion

// All 3 threads block here until startLatch.countDown() fires
startLatch.await();

// Main thread releases all 3 simultaneously
startLatch.countDown();

// Main thread waits for all 3 to complete
doneLatch.await();
```

---

### Test 3 — Race Condition Under Load
```java
@DisplayName("Sends 10 concurrent debit requests of $100 for a wallet with a $500 balance. Ensures the final balance is exactly $0 and 5 requests fail with insufficient funds.")
```
- Creates a wallet with `$500`
- Fires **10 threads simultaneously**, each debiting `$100` with a **unique `transactionId`**
- Asserts:
  - Exactly **5 succeed** (HTTP 200)
  - Exactly **5 fail** (HTTP 422 — Insufficient Funds)
  - Final balance = `$0.00` — no negative balance possible

---

## ❌ Error Responses

| HTTP Status | Error Code | When |
|---|---|---|
| `400 Bad Request` | `VALIDATION_FAILED` | Missing/invalid request fields |
| `404 Not Found` | `NOT_FOUND` | Wallet not found for given `userId` |
| `409 Conflict` | `DUPLICATE_TRANSACTION` | Reserved for explicit duplicate rejection mode |
| `422 Unprocessable Entity` | `INSUFFICIENT_FUNDS` | DEBIT amount exceeds wallet balance |
| `500 Internal Server Error` | `INTERNAL_ERROR` | Unexpected server error |

#### Example — Insufficient Funds (`422`)

```json
{
  "timestamp":     "2026-09-17T14:45:32.123Z",
  "status":        422,
  "error":         "INSUFFICIENT_FUNDS",
  "message":       "Insufficient funds: balance=0.00, requested=100.00",
  "transactionId": "550e8400-e29b-41d4-a716-446655440000"
}
```

#### Example — Validation Failure (`400`)

```json
{
  "timestamp": "2026-09-17T14:45:32.123Z",
  "status":    400,
  "error":     "VALIDATION_FAILED",
  "message":   "amount: must be greater than 0; type: must not be null"
}
```

---

## ⚙️ Configuration

All config lives in [`src/main/resources/application.properties`](src/main/resources/application.properties):

```properties
# H2 In-Memory DataSource
spring.datasource.url=jdbc:h2:mem:ledger;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=

# JPA / Hibernate
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.jpa.hibernate.ddl-auto=create-drop
spring.jpa.show-sql=true

# Lock timeout (ms) — guards against lock starvation
spring.jpa.properties.jakarta.persistence.lock.timeout=5000

# H2 Console (development)
spring.h2.console.enabled=true
spring.h2.console.path=/h2-console
```

| Key Config | Value | Why |
|---|---|---|
| `DB_CLOSE_DELAY=-1` | Keep DB alive | Prevents H2 shutdown between connection borrows |
| `MODE=PostgreSQL` | PostgreSQL syntax | Aligns H2 DDL/DML behaviour with production |
| `ddl-auto=create-drop` | Fresh schema | Ensures clean state on every test run |
| `lock.timeout=5000` | 5 seconds | Prevents indefinite blocking under lock starvation |

---

## 📐 Architectural Decisions

> See [`DECISIONS.md`](DECISIONS.md) for the full detailed analysis.

### Why `PESSIMISTIC_WRITE` and not `@Version` (Optimistic Locking)?

Optimistic locking detects conflicts **after** the fact via a `version` column. Under the Test 3 scenario (10 threads, exactly 5 should succeed), optimistic locking causes some of the 5 valid threads to fail with `OptimisticLockingFailureException` rather than `InsufficientFundsException`, making success/failure counts non-deterministic. `PESSIMISTIC_WRITE` serialises writes deterministically.

### Why `REQUIRES_NEW` in `PaymentExecutor`?

When a `DataIntegrityViolationException` is caught inside a `@Transactional` method, Spring marks the transaction `rollback-only` — any subsequent operation in that context is impossible to commit. By running the mutation in a separate `REQUIRES_NEW` transaction (`PaymentExecutor`), the constraint violation rolls back **only the inner transaction**, leaving the outer context clean for a re-fetch SELECT.

### Why not `synchronized` or `ReentrantLock`?

JVM-level locks are per-process. In a horizontally scaled deployment (multiple pods), each instance has its own lock map — locks from Pod A are invisible to Pod B. Two requests hitting different pods would both acquire their local lock and cause a duplicate execution. Database-level locks are the only shared state across all instances.

---

## 👤 Author

**Inzamamkhan786**  
GitHub: [https://github.com/Inzamamkhan786](https://github.com/Inzamamkhan786)

---

## 📄 License

This project is submitted as part of a technical assignment. All rights reserved.
