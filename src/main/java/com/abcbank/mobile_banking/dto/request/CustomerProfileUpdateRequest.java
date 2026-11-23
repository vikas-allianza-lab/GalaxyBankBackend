package com.abcbank.mobile_banking.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CustomerProfileUpdateRequest(
    @NotBlank @Pattern(regexp = "(?i)Female|Male|Other") String gender,
    @NotBlank @Size(max = 300) String address,
    @NotBlank @Size(max = 100) String city,
    @NotBlank @Size(max = 80) String state,
    @NotBlank @Pattern(regexp = "^[0-9]{6}$") String pincode,
    @NotBlank @Pattern(regexp = "^[0-9]{4}$") String mpin
) {}
