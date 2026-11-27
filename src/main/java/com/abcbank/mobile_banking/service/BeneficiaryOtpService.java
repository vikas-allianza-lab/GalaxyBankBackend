
package com.abcbank.mobile_banking.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.abcbank.mobile_banking.repository.BeneficiaryRepository;

@Service
public class BeneficiaryOtpService {

    private final BeneficiaryRepository repository;
    private final JavaMailSender mailSender;

    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${spring.mail.username}")
    private String senderEmail;

    public BeneficiaryOtpService(
            BeneficiaryRepository repository,
            JavaMailSender mailSender) {

        this.repository = repository;
        this.mailSender = mailSender;
    }

    // Generate OTP, save hash and send to customer's registered email
    @Transactional
    public void generateAndSendOtp(
            Long beneficiaryId,
            String customerNo,
            String customerRegisteredEmail) {

        if (customerRegisteredEmail == null
                || customerRegisteredEmail.isBlank()) {

            throw new IllegalArgumentException(
                    "Customer registered email address is required");
        }

        // Generate six-digit OTP
        String otp = String.format(
                "%06d",
                secureRandom.nextInt(1_000_000)
        );

        // Hash OTP before storing in database
        String otpHash = hashOtp(otp);

        // OTP validity: 5 minutes
        OffsetDateTime expiresAt =
                OffsetDateTime.now().plusMinutes(5);

        // Save OTP hash
        repository.saveOtp(
                beneficiaryId,
                customerNo,
                otpHash,
                expiresAt
        );

        // Send OTP to customer's registered email
        sendEmail(customerRegisteredEmail, otp);
    }

    // Verify OTP
    @Transactional
    public boolean verifyOtp(
            Long beneficiaryId,
            String customerNo,
            String enteredOtp) {

        if (enteredOtp == null
                || !enteredOtp.matches("\\d{6}")) {

            return false;
        }

        var otpRecord = repository.findLatestOtp(
                beneficiaryId,
                customerNo
        );

        if (otpRecord.isEmpty()) {
            return false;
        }

        var record = otpRecord.get();

        // Already verified
        if (record.verified()) {
            return false;
        }

        // Maximum attempts reached
        if (record.attempts() >= record.maxAttempts()) {
            return false;
        }

        // OTP expired
        if (!OffsetDateTime.now().isBefore(record.expiresAt())) {
            return false;
        }

        // Count this verification attempt
        repository.incrementOtpAttempts(record.id());

        String enteredHash = hashOtp(enteredOtp);

        boolean matches = MessageDigest.isEqual(
                enteredHash.getBytes(StandardCharsets.UTF_8),
                record.otpHash().getBytes(StandardCharsets.UTF_8)
        );

        if (!matches) {
            return false;
        }

        // Mark OTP verified
        repository.markOtpVerified(record.id());

        return true;
    }

    // SHA-256 OTP hashing
    private String hashOtp(String otp) {

        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] hash = digest.digest(
                    otp.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(hash);

        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Unable to hash OTP", ex
            );
        }
    }

    // Send OTP email to customer's registered email
    private void sendEmail(
            String customerRegisteredEmail,
            String otp) {

        SimpleMailMessage message = new SimpleMailMessage();

        message.setFrom(senderEmail);
        message.setTo(customerRegisteredEmail);

        message.setSubject(
                "ABC Bank - Beneficiary Verification OTP"
        );

        message.setText(
                "Dear Customer,\n\n"
                + "Your ABC Bank beneficiary verification OTP is: "
                + otp
                + "\n\nThis OTP is valid for 5 minutes."
                + "\nDo not share this OTP with anyone."
                + "\n\nIf you did not request this beneficiary addition, "
                + "please contact ABC Bank immediately."
                + "\n\nRegards,\nABC Bank"
        );

        mailSender.send(message);
    }
}
