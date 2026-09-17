package com.vvh.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Payment Ledger microservice.
 *
 * <p>The service exposes a single idempotent endpoint:
 * {@code POST /api/v1/transactions/process}
 */
@SpringBootApplication
public class PaymentLedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentLedgerApplication.class, args);
    }
}
