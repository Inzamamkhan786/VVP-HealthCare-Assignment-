package com.vvh.ledger.controller;

import com.vvh.ledger.dto.TransactionRequest;
import com.vvh.ledger.dto.TransactionResponse;
import com.vvh.ledger.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for the ledger transaction endpoint.
 *
 * <p>Exposes a single, idempotent endpoint:
 * <pre>POST /api/v1/transactions/process</pre>
 *
 * <p>The controller is intentionally thin — all business logic lives in
 * {@link PaymentService}.
 */
@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final PaymentService paymentService;

    public TransactionController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * Processes a DEBIT or CREDIT transaction for the specified wallet.
     *
     * <p>Identical requests (same {@code transactionId}) are idempotent:
     * the original response is returned without re-executing the ledger mutation.
     *
     * @param request validated transaction payload
     * @return HTTP 200 with the transaction result
     */
    @PostMapping("/process")
    public ResponseEntity<TransactionResponse> process(@Valid @RequestBody TransactionRequest request) {
        TransactionResponse response = paymentService.process(request);
        return ResponseEntity.ok(response);
    }
}
