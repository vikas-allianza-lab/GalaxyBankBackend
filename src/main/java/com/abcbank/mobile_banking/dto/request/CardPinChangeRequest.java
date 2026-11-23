package com.abcbank.mobile_banking.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CardPinChangeRequest(
    @NotBlank @Pattern(regexp = "^(0[1-9]|1[0-2])/[0-9]{2}$") String expiry,
    @NotBlank @Pattern(regexp = "^[0-9]{3}$") String cvv,
    @NotBlank @Pattern(regexp = "^[0-9]{4}$") String newPin,
    @NotBlank @Pattern(regexp = "^[0-9]{6}$") String otp
) {}
