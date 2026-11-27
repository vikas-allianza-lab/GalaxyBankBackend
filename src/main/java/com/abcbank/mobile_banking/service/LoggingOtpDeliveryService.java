package com.abcbank.mobile_banking.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Local-development SMS simulator. Never enable this mode in production. */
@Service
@ConditionalOnProperty(name = "app.otp.delivery-mode", havingValue = "CONSOLE", matchIfMissing = true)
public class LoggingOtpDeliveryService implements OtpDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(LoggingOtpDeliveryService.class);
    private final OtpEmailSender emailSender;

    public LoggingOtpDeliveryService(OtpEmailSender emailSender) {
        this.emailSender = emailSender;
    }

    @Override
    public void sendOtp(String mobileNumber, String otp) {
        String lastFour = mobileNumber == null || mobileNumber.length() < 4
                ? "****" : mobileNumber.substring(mobileNumber.length() - 4);
        // Local-only testing. This deliberately exposes the OTP in local console logs.
        log.warn("LOCAL DEVELOPMENT ONLY - SMS simulator OTP for mobile ending {}: {}", lastFour, otp);
    }

    @Override
    public void sendEmailOtp(String email, String otp) {
        emailSender.send(email, otp);
    }
}
