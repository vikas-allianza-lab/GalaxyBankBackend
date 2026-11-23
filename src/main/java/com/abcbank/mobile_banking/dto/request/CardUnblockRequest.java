package com.abcbank.mobile_banking.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CardUnblockRequest(@NotBlank @Pattern(regexp = "^[0-9]{4}$") String mpin) {}
