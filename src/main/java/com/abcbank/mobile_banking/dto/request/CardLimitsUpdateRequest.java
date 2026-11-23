package com.abcbank.mobile_banking.dto.request;

import java.math.BigDecimal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

public record CardLimitsUpdateRequest(
    @NotNull @PositiveOrZero BigDecimal atm,
    @NotNull @PositiveOrZero BigDecimal pos,
    @NotNull @PositiveOrZero BigDecimal online,
    @NotNull @PositiveOrZero BigDecimal intl,
    @NotBlank @Pattern(regexp = "^[0-9]{4}$") String mpin
) {}
