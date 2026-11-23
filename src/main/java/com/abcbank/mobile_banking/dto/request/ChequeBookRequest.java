package com.abcbank.mobile_banking.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChequeBookRequest(

    @NotBlank(message = "Account number is required")
    @Size(max = 30)
    String accountNo,

    @NotBlank(message = "Book type is required")
    @Pattern(
        regexp = "(?i)STANDARD|PREMIUM",
        message = "Book type must be STANDARD or PREMIUM"
    )
    String bookType,

    @NotBlank(message = "Delivery is required")
    @Pattern(
        regexp = "(?i)BRANCH|PRIMARY_ADDRESS|DEFAULT_ADDRESS",
        message = "Invalid delivery option"
    )
    String delivery

) {}