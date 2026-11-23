package com.abcbank.mobile_banking.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.abcbank.mobile_banking.dto.request.PaymentBillRequest;
import com.abcbank.mobile_banking.dto.request.PaymentRechargeRequest;
import com.abcbank.mobile_banking.dto.request.PaymentUpiRequest;
import com.abcbank.mobile_banking.service.PaymentService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
    private final PaymentService service;
    public PaymentController(PaymentService service) { this.service = service; }

    @GetMapping("/history")
    public Map<String,Object> history(@RequestParam(defaultValue="ALL") String type,
            @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size,
            Authentication auth) {
        return service.history(auth.getName(), type, page, size);
    }

    @GetMapping("/billers")
    public List<Map<String,Object>> billers(@RequestParam(required=false) String category, Authentication auth) {
        return service.billers(auth.getName(), category);
    }

    @PostMapping("/bills")
    public ResponseEntity<Map<String,Object>> payBill(@Valid @RequestBody PaymentBillRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey, Authentication auth) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.payBill(auth.getName(), request, idempotencyKey));
    }

    @PostMapping("/recharges")
    public ResponseEntity<Map<String,Object>> recharge(@Valid @RequestBody PaymentRechargeRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey, Authentication auth) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.recharge(auth.getName(), request, idempotencyKey));
    }

    @PostMapping("/upi/pay")
    public ResponseEntity<Map<String,Object>> payUpi(@Valid @RequestBody PaymentUpiRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey, Authentication auth) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.payUpi(auth.getName(), request, idempotencyKey));
    }
}
