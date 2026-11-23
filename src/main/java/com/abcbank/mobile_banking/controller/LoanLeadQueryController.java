
package com.abcbank.mobile_banking.controller;

import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.abcbank.mobile_banking.dto.request.LoanLeadRequest;
import com.abcbank.mobile_banking.service.LoanLeadQueryService;

@RestController
@RequestMapping("/api/v1/loans")
public class LoanLeadQueryController {

    private final LoanLeadQueryService service;

    public LoanLeadQueryController(LoanLeadQueryService service) {
        this.service = service;
    }

    // Validate logged-in customer
    private String getCustomerNo(Authentication authentication) {

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication.getName() == null
                || authentication.getName().isBlank()
                || "anonymousUser".equals(authentication.getName())) {

            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Customer login is required"
            );
        }

        return authentication.getName();
    }

    // LON-01: Get customer's loan accounts
    @GetMapping
    public ResponseEntity<Map<String, Object>> getLoans(
            Authentication authentication) {

        return ResponseEntity.ok(
                service.getLoanHistory(getCustomerNo(authentication))
        );
    }

    // EMI: Display loan and EMI details for Pay EMI screen
    @GetMapping("/emi-details")
    public ResponseEntity<Map<String, Object>> getEmiDetails(
            Authentication authentication) {

        return ResponseEntity.ok(
                service.getEmiDetails(getCustomerNo(authentication))
        );
    }

    // EMI: Display available debit accounts
    @GetMapping("/debit-accounts")
    public ResponseEntity<Map<String, Object>> getEmiDebitAccounts(
            Authentication authentication) {

        return ResponseEntity.ok(
                service.getEmiDebitAccounts(getCustomerNo(authentication))
        );
    }

    // Get previously used loan product codes
    @GetMapping("/product-codes")
    public ResponseEntity<Map<String, Object>> getProductCodes(
            Authentication authentication) {

        return ResponseEntity.ok(
                service.getProductCodes(getCustomerNo(authentication))
        );
    }

    // LON-07: Get all loan applications
    @GetMapping("/leads")
    public ResponseEntity<Map<String, Object>> getLoanApplications(
            Authentication authentication) {

        return ResponseEntity.ok(
                service.getLoanApplications(getCustomerNo(authentication))
        );
    }

    // Get particular loan application
    @GetMapping("/leads/{id}")
    public ResponseEntity<Map<String, Object>> getLoanApplication(
            Authentication authentication,
            @PathVariable Long id) {

        return ResponseEntity.ok(
                service.getLoanApplication(
                        getCustomerNo(authentication), id
                )
        );
    }

    // LON-08: Submit fresh loan application
    @PostMapping("/leads")
    public ResponseEntity<Map<String, Object>> submitLoanApplication(
            Authentication authentication,
            @Valid @RequestBody LoanLeadRequest request) {

        String authenticatedCustomerNo = getCustomerNo(authentication);

        // Ensure request customer matches logged-in customer
        if (request.customerNo() == null
                || !authenticatedCustomerNo.equalsIgnoreCase(
                        request.customerNo().trim())) {

            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Customer number does not match logged-in customer."
            );
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(
                service.submitLoanApplication(
                        authenticatedCustomerNo,
                        request
                )
        );
    }

    // Get existing loan history
    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> getLoanHistory(
            Authentication authentication) {

        return ResponseEntity.ok(
                service.getLoanHistory(getCustomerNo(authentication))
        );
    }

    // Get EMI payment request history
    @GetMapping("/history/{loanAccountNo}/emi-requests")
    public ResponseEntity<Map<String, Object>> getEmiRequests(
            Authentication authentication,
            @PathVariable String loanAccountNo) {

        return ResponseEntity.ok(
                service.getEmiRequests(
                        getCustomerNo(authentication),
                        loanAccountNo
                )
        );
    }
}
