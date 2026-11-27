package com.abcbank.mobile_banking.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class OtpEmailSender {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String senderEmail;

    public OtpEmailSender(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void send(String email, String otp) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Registered email address is missing.");
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(senderEmail);
        message.setTo(email);
        message.setSubject("ABC Bank - Registration OTP");
        message.setText("Dear Customer,\n\n"
                + "Your ABC Bank registration OTP is: " + otp + "\n\n"
                + "This OTP is valid for 5 minutes.\n"
                + "Do not share this OTP with anyone. ABC Bank will never ask you to disclose it.\n\n"
                + "Regards,\nABC Bank");
        mailSender.send(message);
    }
}
