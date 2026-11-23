package com.abcbank.mobile_banking.dto.request;

import java.math.BigDecimal;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PaymentBillRequest(
    @NotNull Long billerId,
    @NotBlank @Size(max = 80) String consumerNo,
    @NotBlank String accountId,
    @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal amount,
    @NotBlank @Pattern(regexp = "^[0-9]{4}$") String mpin
) {}
