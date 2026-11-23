
package com.abcbank.mobile_banking.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LoggingOtpDeliveryService implements OtpDeliveryService {

    private static final Logger log =
            LoggerFactory.getLogger(LoggingOtpDeliveryService.class);

    @Override
    public void sendOtp(String mobileNumber, String otp) {

        log.warn("LOCAL TEST SMS OTP for mobile ending {}: {}",
                mobileNumber.substring(
                        Math.max(0, mobileNumber.length() - 4)),
                otp);
    }

    @Override
    public void sendEmailOtp(String email, String otp) {

        log.warn("LOCAL TEST EMAIL OTP for {}: {}", email, otp);
    }
}
