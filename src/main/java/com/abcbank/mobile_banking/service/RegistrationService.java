
package com.abcbank.mobile_banking.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.abcbank.mobile_banking.dto.request.RegistrationCompleteRequest;
import com.abcbank.mobile_banking.dto.request.RegistrationStartRequest;
import com.abcbank.mobile_banking.dto.request.VerifyRegistrationOtpRequest;
import com.abcbank.mobile_banking.dto.response.RegistrationCompleteResponse;
import com.abcbank.mobile_banking.dto.response.RegistrationStartResponse;
import com.abcbank.mobile_banking.dto.response.RegistrationTokenResponse;
import com.abcbank.mobile_banking.entity.CustomerRegistry;
import com.abcbank.mobile_banking.entity.MobileBankingUser;
import com.abcbank.mobile_banking.entity.MpinCredential;
import com.abcbank.mobile_banking.entity.RegistrationChallenge;
import com.abcbank.mobile_banking.repository.CustomerRegistryRepository;
import com.abcbank.mobile_banking.repository.MobileBankingUserRepository;
import com.abcbank.mobile_banking.repository.MpinCredentialRepository;
import com.abcbank.mobile_banking.repository.RegistrationChallengeRepository;
import com.abcbank.mobile_banking.security.JwtService;

@Service
public class RegistrationService {

    private static final int OTP_TTL_SECONDS = 300;
    private static final int MAX_ATTEMPTS = 5;

    private final CustomerRegistryRepository customers;
    private final RegistrationChallengeRepository challenges;
    private final MobileBankingUserRepository mobileUsers;
    private final MpinCredentialRepository mpinCredentials;
    private final PasswordEncoder encoder;
    private final OtpDeliveryService otpDelivery;
    private final JwtService jwt;
    private final JdbcTemplate jdbc;

    @Value("${app.otp.delivery-mode:CONSOLE}")
    private String otpDeliveryMode;

    private final SecureRandom random = new SecureRandom();

    public RegistrationService(
            CustomerRegistryRepository customers,
            RegistrationChallengeRepository challenges,
            MobileBankingUserRepository mobileUsers,
            MpinCredentialRepository mpinCredentials,
            PasswordEncoder encoder,
            OtpDeliveryService otpDelivery,
            JwtService jwt,
            JdbcTemplate jdbc) {

        this.customers = customers;
        this.challenges = challenges;
        this.mobileUsers = mobileUsers;
        this.mpinCredentials = mpinCredentials;
        this.encoder = encoder;
        this.otpDelivery = otpDelivery;
        this.jwt = jwt;
        this.jdbc = jdbc;
    }

    // STEP 1: Start registration
    @Transactional
    public RegistrationStartResponse start(
            RegistrationStartRequest request) {

        CustomerRegistry customer = customers.findById(
                request.customerId()
        ).orElseThrow(() ->
                new IllegalArgumentException(
                        "Customer details could not be verified."
                ));

        if (!customer.isEligibleForMobileBanking()
                || !constantTimeEquals(
                        normalize(customer.getRegisteredMobile()),
                        normalize(request.mobileNumber()))) {

            throw new IllegalArgumentException(
                    "Customer details could not be verified."
            );
        }

        if (mobileUsers.existsByCustomerId(request.customerId())) {
            throw new IllegalArgumentException(
                    "Mobile banking registration is already completed."
            );
        }

        String otp = String.format(
                "%06d", random.nextInt(1_000_000)
        );

        challenges
                .findTopByCustomerIdAndConsumedFalseOrderByCreatedAtDesc(
                        request.customerId()
                )
                .ifPresent(RegistrationChallenge::consume);

        challenges.save(
                new RegistrationChallenge(
                        request.customerId(),
                        encoder.encode(otp),
                        Instant.now().plusSeconds(OTP_TTL_SECONDS)
                )
        );

        // Email is read from the existing customer user-master table.
        String registeredEmail = getRegisteredEmail(request.customerId());

        // Both delivery methods receive the SAME OTP generated above.
        // CONSOLE mode simulates SMS locally; MSG91 mode sends a real SMS.
        otpDelivery.sendEmailOtp(registeredEmail, otp);
        otpDelivery.sendOtp(customer.getRegisteredMobile(), otp);

        String deliveryMessage = "MSG91".equalsIgnoreCase(otpDeliveryMode)
                ? "OTP sent to your registered email and mobile number."
                : "OTP sent to your registered email. SMS is simulated in the local console (development only).";

        return new RegistrationStartResponse(
                deliveryMessage,
                mask(customer.getRegisteredMobile()),
                maskEmail(registeredEmail),
                OTP_TTL_SECONDS
        );
    }

    // STEP 2: Verify OTP
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public RegistrationTokenResponse verify(
            VerifyRegistrationOtpRequest request) {

        RegistrationChallenge challenge = challenges
                .findTopByCustomerIdAndConsumedFalseOrderByCreatedAtDesc(
                        request.customerId()
                )
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "OTP is invalid or expired."
                        ));

        if (challenge.getAttempts() >= MAX_ATTEMPTS
                || challenge.getExpiresAt().isBefore(Instant.now())) {

            challenge.consume();

            throw new IllegalArgumentException(
                    "OTP is invalid or expired."
            );
        }

        challenge.incrementAttempts();

        if (!encoder.matches(
                request.otp(), challenge.getOtpHash())) {

            throw new IllegalArgumentException(
                    "OTP is invalid or expired."
            );
        }

        challenge.consume();

        return new RegistrationTokenResponse(
                "Mobile verified. Use this short-lived token only to complete customer registration.",
                jwt.createRegistrationToken(request.customerId()),
                "Bearer",
                jwt.getTtlSeconds(),
                request.customerId(),
                "CU"
        );
    }

    // STEP 3: Complete registration
    // MPIN hash is stored ONLY in MB001003.
    @Transactional
    public RegistrationCompleteResponse complete(
            String customerId,
            RegistrationCompleteRequest request) {

        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException(
                    "Invalid registration token."
            );
        }

        if (request == null
                || request.mpin() == null
                || request.confirmMpin() == null
                || !request.mpin().equals(request.confirmMpin())) {

            throw new IllegalArgumentException(
                    "MPIN and confirm MPIN do not match."
            );
        }

        CustomerRegistry customer = customers.findById(customerId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Customer details could not be verified."
                        ));

        if (!customer.isEligibleForMobileBanking()) {
            throw new IllegalArgumentException(
                    "Customer is not eligible for mobile banking."
            );
        }

        // Get User Master ID from Customer Registry.
        Long userId = customer.getUserId();

        if (userId == null) {
            throw new IllegalArgumentException(
                    "Customer is not linked to a User Master record."
            );
        }

        // Prevent duplicate registration.
        if (mobileUsers.existsByCustomerId(customerId)
                || mobileUsers.existsByUserId(userId)
                || mpinCredentials.existsByCustomerId(customerId)
                || mpinCredentials.existsByUserId(userId)) {

            throw new IllegalArgumentException(
                    "Mobile banking registration is already completed."
            );
        }

        // Encode MPIN using the configured PasswordEncoder.
        String hashedMpin = encoder.encode(request.mpin());

        // Save MPIN hash and both IDs in MB001003.
        MpinCredential credential =
                new MpinCredential(customerId, userId, hashedMpin);

        mpinCredentials.save(credential);

        // Save registration status and both IDs separately.
        MobileBankingUser user =
                new MobileBankingUser(customerId, userId);

        mobileUsers.save(user);

        return new RegistrationCompleteResponse(
                "Mobile banking registration completed successfully.",
                customerId,
                "ACTIVE"
        );
    }


    private String getRegisteredEmail(String customerId) {
        try {
            String email = jdbc.queryForObject("""
                    SELECT email
                      FROM public."MB001001"
                     WHERE customer_no = ?
                       AND user_type = 'CU'
                       AND email IS NOT NULL
                       AND TRIM(email) <> ''
                    """, String.class, customerId);
            if (email == null || email.isBlank()) {
                throw new IllegalArgumentException("Registered email address not found.");
            }
            return email.trim();
        } catch (EmptyResultDataAccessException ex) {
            throw new IllegalArgumentException("Registered email address not found.");
        }
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "****";
        String[] parts = email.split("@", 2);
        String local = parts[0];
        String masked = local.length() <= 2
                ? local.charAt(0) + "*"
                : local.substring(0, 2) + "****";
        return masked + "@" + parts[1];
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.replaceAll("\\s+", "");
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String mask(String mobile) {
        return "******" + mobile.substring(
                Math.max(0, mobile.length() - 4)
        );
    }
}
