package com.abcbank.mobile_banking.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import com.abcbank.mobile_banking.service.CustomerMeService;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerMeController {

    private final CustomerMeService customerMeService;

    public CustomerMeController(CustomerMeService customerMeService) {
        this.customerMeService = customerMeService;
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getMyDetails(Authentication authentication) {
        if (authentication == null || authentication.getName() == null
                || authentication.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer login is required");
        }

        return ResponseEntity.ok(customerMeService.getMyDetails(authentication.getName()));
    }
}
    