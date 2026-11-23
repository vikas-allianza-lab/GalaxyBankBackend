package com.abcbank.mobile_banking.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CardRequestCreateRequest(
    @NotBlank String accountId,
    @NotBlank @Size(max = 26) @Pattern(regexp = "^[A-Za-z .]+$") String nameOnCard,
    @NotBlank @Pattern(regexp = "(?i)DEFAULT|PRIMARY|BRANCH") String deliveryPreference,
    @Size(max = 500) String address,
    @Size(max = 20) String branchCode,
    @NotNull Boolean termsAccepted,
    @NotBlank @Pattern(regexp = "^[0-9]{4}$") String mpin
) {}
