package com.abcbank.mobile_banking.dto.response;

public record CustomerMeResponse(
        String customerId, String name, String maskedMobile, String maskedEmail,
        String kycStatus, String homeBranch, String lastLoginAt) {}
