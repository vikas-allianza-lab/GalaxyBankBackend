
package com.abcbank.mobile_banking.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.abcbank.mobile_banking.dto.request.ContactChangeRequests;
import com.abcbank.mobile_banking.dto.response.MessageResponse;
import com.abcbank.mobile_banking.service.CustomerContactChangeService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/customers/me")
public class CustomerContactChangeController {

    private final CustomerContactChangeService service;

    public CustomerContactChangeController(
            CustomerContactChangeService service) {
        this.service = service;
    }

    // PRO-02: Request OTP for changing mobile number
    @PostMapping("/mobile/request-otp")
    public ResponseEntity<MessageResponse> requestMobileOtp(
            @Valid @RequestBody ContactChangeRequests.RequestMobileOtp request,
            Authentication authentication) {

        return ResponseEntity.ok(
                service.requestMobileOtp(authentication.getName(), request)
        );
    }

    // PRO-03: Verify OTP and update mobile number
    @PostMapping("/mobile/verify")
    public ResponseEntity<MessageResponse> verifyMobileOtp(
            @Valid @RequestBody ContactChangeRequests.VerifyMobileOtp request,
            Authentication authentication) {

        return ResponseEntity.ok(
                service.verifyMobileOtp(authentication.getName(), request)
        );
    }

    // PRO-04: Request OTP for changing email
    @PostMapping("/email/request-otp")
    public ResponseEntity<MessageResponse> requestEmailOtp(
            @Valid @RequestBody ContactChangeRequests.RequestEmailOtp request,
            Authentication authentication) {

        return ResponseEntity.ok(
                service.requestEmailOtp(authentication.getName(), request)
        );
    }

    // PRO-05: Verify OTP and update email
    @PostMapping("/email/verify")
    public ResponseEntity<MessageResponse> verifyEmailOtp(
            @Valid @RequestBody ContactChangeRequests.VerifyEmailOtp request,
            Authentication authentication) {

        return ResponseEntity.ok(
                service.verifyEmailOtp(authentication.getName(), request)
        );
    }
}
