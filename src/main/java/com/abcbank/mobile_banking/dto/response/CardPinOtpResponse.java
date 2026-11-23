package com.abcbank.mobile_banking.dto.response;

public record CardPinOtpResponse(String message, int otpExpiresInSec, int resendAfterSec) {}
