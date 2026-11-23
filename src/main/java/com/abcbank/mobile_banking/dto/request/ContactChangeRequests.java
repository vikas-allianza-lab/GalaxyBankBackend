
package com.abcbank.mobile_banking.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class ContactChangeRequests {

    public record RequestMobileOtp(

            @NotBlank(message = "New mobile number is required")
            @Pattern(
                regexp = "^[6-9][0-9]{9}$",
                message = "Enter a valid 10-digit mobile number"
            )
            String newMobile,

            @NotBlank(message = "MPIN is required")
            @Pattern(
                regexp = "^[0-9]{4}$",
                message = "MPIN must contain exactly 4 digits"
            )
            String mpin
    ) {}

    public record VerifyMobileOtp(

            @NotBlank(message = "New mobile number is required")
            @Pattern(
                regexp = "^[6-9][0-9]{9}$",
                message = "Enter a valid 10-digit mobile number"
            )
            String newMobile,

            @NotBlank(message = "OTP is required")
            @Pattern(
                regexp = "^[0-9]{6}$",
                message = "OTP must contain exactly 6 digits"
            )
            String otp
    ) {}

    public record RequestEmailOtp(

            @NotBlank(message = "New email is required")
            @Email(message = "Enter a valid email address")
            String newEmail,

            @NotBlank(message = "MPIN is required")
            @Pattern(
                regexp = "^[0-9]{4}$",
                message = "MPIN must contain exactly 4 digits"
            )
            String mpin
    ) {}

    public record VerifyEmailOtp(

            @NotBlank(message = "New email is required")
            @Email(message = "Enter a valid email address")
            String newEmail,

            @NotBlank(message = "OTP is required")
            @Pattern(
                regexp = "^[0-9]{4}$",
                message = "OTP must contain exactly 6 digits"
            )
            String otp
    ) {}
}
