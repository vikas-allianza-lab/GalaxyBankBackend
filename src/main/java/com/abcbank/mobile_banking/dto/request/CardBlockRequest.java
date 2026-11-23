package com.abcbank.mobile_banking.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CardBlockRequest(
    @NotBlank @Pattern(regexp = "(?i)lost|stolen|suspicious transactions|damaged|other") String reason,
    @NotBlank @Pattern(regexp = "(?i)TEMPORARY|PERMANENT") String blockType,
    @NotBlank @Pattern(regexp = "^[0-9]{4}$") String mpin
) {}
