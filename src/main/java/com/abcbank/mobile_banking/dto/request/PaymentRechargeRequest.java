package com.abcbank.mobile_banking.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PaymentRechargeRequest(
    @NotBlank @Pattern(regexp = "^[6-9][0-9]{9}$") String mobileNo,
    @NotBlank @Size(max = 80) String operator,
    @NotBlank @Size(max = 100) String planId,
    @NotBlank String accountId,
    @NotBlank @Pattern(regexp = "^[0-9]{4}$") String mpin
) {}
