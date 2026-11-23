package com.abcbank.mobile_banking.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.abcbank.mobile_banking.dto.request.CustomerProfileUpdateRequest;
import com.abcbank.mobile_banking.service.CustomerProfileUpdateService;

import jakarta.validation.Valid;

/** Adds PUT /customers/me without changing the existing GET /customers/me controller. */
@RestController
@RequestMapping("/api/v1/customers/me")
public class CustomerProfileUpdateController {
    private final CustomerProfileUpdateService service;
    public CustomerProfileUpdateController(CustomerProfileUpdateService service) { this.service = service; }

    @PutMapping
    public ResponseEntity<Map<String,Object>> update(@Valid @RequestBody CustomerProfileUpdateRequest request,
                                                     Authentication authentication) {
        return ResponseEntity.ok(service.update(authentication.getName(), request));
    }
}
