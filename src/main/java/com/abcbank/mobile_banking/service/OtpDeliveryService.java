
package com.abcbank.mobile_banking.service;

public interface OtpDeliveryService {

    void sendOtp(String mobileNumber, String otp);

    void sendEmailOtp(String email, String otp);
}
