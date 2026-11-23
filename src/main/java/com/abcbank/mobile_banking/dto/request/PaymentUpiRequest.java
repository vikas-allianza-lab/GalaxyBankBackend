package com.abcbank.mobile_banking.dto.request;

import java.math.BigDecimal;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PaymentUpiRequest(
    @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{2,256}@[A-Za-z0-9.-]{2,64}$") String vpa,
    @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal amount,
    @NotBlank String accountId,
    @Size(max = 120) String remarks,
    @NotBlank @Pattern(regexp = "^[0-9]{4}$") String mpin
) {}
