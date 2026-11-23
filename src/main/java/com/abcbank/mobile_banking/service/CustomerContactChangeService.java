
package com.abcbank.mobile_banking.service;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.abcbank.mobile_banking.dto.request.ContactChangeRequests;
import com.abcbank.mobile_banking.dto.response.MessageResponse;

@Service
public class CustomerContactChangeService {

    private static final int OTP_TTL_SECONDS = 300;
    private static final int MAX_OTP_ATTEMPTS = 5;
    private static final int RESEND_COOLDOWN_SECONDS = 60;

    private static final String OTP_INVALID =
            "OTP is invalid or expired. Please request a new OTP.";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final OtpDeliveryService otpDelivery;

    private final SecureRandom random = new SecureRandom();

    public CustomerContactChangeService(
            JdbcTemplate jdbc,
            PasswordEncoder encoder,
            OtpDeliveryService otpDelivery) {

        this.jdbc = jdbc;
        this.encoder = encoder;
        this.otpDelivery = otpDelivery;
    }

    // ---------------------------------------------------------
    // PRO-02: REQUEST OTP FOR MOBILE CHANGE
    // ---------------------------------------------------------

    @Transactional
    public MessageResponse requestMobileOtp(
            String customerNo,
            ContactChangeRequests.RequestMobileOtp request) {

        String newMobile = request.newMobile().trim();

        verifyMpin(customerNo, request.mpin());

        if (isSameMobile(customerNo, newMobile)) {
            throw new IllegalArgumentException(
                    "This is already your registered mobile number.");
        }

        if (mobileAlreadyUsed(newMobile)) {
            throw new IllegalArgumentException(
                    "This mobile number is already registered.");
        }

        if (isCooldownActive(customerNo, "MOBILE")) {
            return new MessageResponse(
                    "An OTP was recently requested. Please try again after 60 seconds.");
        }

        consumePreviousChallenge(customerNo, "MOBILE");

        String otp = generateOtp();

        saveChallenge(customerNo, "MOBILE", newMobile, otp);

        otpDelivery.sendOtp(newMobile, otp);

        return new MessageResponse(
                "OTP sent to your new mobile number. It is valid for 5 minutes.");
    }

    // ---------------------------------------------------------
    // PRO-03: VERIFY OTP AND UPDATE MOBILE
    // ---------------------------------------------------------

    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public MessageResponse verifyMobileOtp(
            String customerNo,
            ContactChangeRequests.VerifyMobileOtp request) {

        String newMobile = request.newMobile().trim();

        verifyOtp(customerNo, "MOBILE", newMobile, request.otp());

        if (mobileAlreadyUsed(newMobile)) {
            throw new IllegalArgumentException(
                    "This mobile number is already registered.");
        }

        int registryUpdated = jdbc.update("""
            UPDATE public.mb001001cr
               SET registered_mobile = ?
             WHERE customer_id = ?
            """, newMobile, customerNo);

        if (registryUpdated == 0) {
            throw new IllegalArgumentException(
                    "Customer registry record not found.");
        }

        jdbc.update("""
            UPDATE public."MB001001"
               SET mobile = ?,
                   modified_by = user_name,
                   modified_on = CURRENT_TIMESTAMP
             WHERE customer_no = ?
               AND user_type = 'CU'
               AND status = 'ACTIVE'
            """, newMobile, customerNo);

        consumeLatestChallenge(customerNo, "MOBILE", newMobile);

        return new MessageResponse(
                "Mobile number updated successfully.");
    }

    // ---------------------------------------------------------
    // PRO-04: REQUEST OTP FOR EMAIL CHANGE
    // ---------------------------------------------------------

    @Transactional
    public MessageResponse requestEmailOtp(
            String customerNo,
            ContactChangeRequests.RequestEmailOtp request) {

        String newEmail = request.newEmail().trim().toLowerCase();

        verifyMpin(customerNo, request.mpin());

        if (isSameEmail(customerNo, newEmail)) {
            throw new IllegalArgumentException(
                    "This is already your registered email address.");
        }

        if (emailAlreadyUsed(newEmail)) {
            throw new IllegalArgumentException(
                    "This email address is already registered.");
        }

        if (isCooldownActive(customerNo, "EMAIL")) {
            return new MessageResponse(
                    "An OTP was recently requested. Please try again after 60 seconds.");
        }

        consumePreviousChallenge(customerNo, "EMAIL");

        String otp = generateOtp();

        saveChallenge(customerNo, "EMAIL", newEmail, otp);

        otpDelivery.sendEmailOtp(newEmail, otp);

        return new MessageResponse(
                "OTP sent to your new email address. It is valid for 5 minutes.");
    }

    // ---------------------------------------------------------
    // PRO-05: VERIFY OTP AND UPDATE EMAIL
    // ---------------------------------------------------------

    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public MessageResponse verifyEmailOtp(
            String customerNo,
            ContactChangeRequests.VerifyEmailOtp request) {

        String newEmail = request.newEmail().trim().toLowerCase();

        verifyOtp(customerNo, "EMAIL", newEmail, request.otp());

        if (emailAlreadyUsed(newEmail)) {
            throw new IllegalArgumentException(
                    "This email address is already registered.");
        }

        int updated = jdbc.update("""
            UPDATE public."MB001001"
               SET email = ?,
                   modified_by = user_name,
                   modified_on = CURRENT_TIMESTAMP
             WHERE customer_no = ?
               AND user_type = 'CU'
               AND status = 'ACTIVE'
            """, newEmail, customerNo);

        if (updated == 0) {
            throw new IllegalArgumentException(
                    "Active customer record not found.");
        }

        consumeLatestChallenge(customerNo, "EMAIL", newEmail);

        return new MessageResponse(
                "Email address updated successfully.");
    }

    // ---------------------------------------------------------
    // VERIFY CUSTOMER MPIN
    // ---------------------------------------------------------

    private void verifyMpin(String customerNo, String mpin) {

        try {
            Map<String, Object> user = jdbc.queryForMap("""
                SELECT p.mpin_hash
                  FROM public."MB001001" u
                  JOIN public."MB001003" p
                    ON p.user_id = u.user_id
                 WHERE u.customer_no = ?
                   AND u.user_type = 'CU'
                   AND u.status = 'ACTIVE'
                   AND p.status = 'ACTIVE'
                   AND (p.locked_until IS NULL
                        OR p.locked_until <= CURRENT_TIMESTAMP)
                """, customerNo);

            String mpinHash = user.get("mpin_hash").toString();

            if (!encoder.matches(mpin, mpinHash)) {
                throw new IllegalArgumentException(
                        "Invalid MPIN.");
            }

        } catch (EmptyResultDataAccessException ex) {
            throw new IllegalArgumentException(
                    "Customer or active MPIN record not found.");
        }
    }

    // ---------------------------------------------------------
    // SAVE OTP CHALLENGE
    // ---------------------------------------------------------

    private void saveChallenge(
            String customerNo,
            String contactType,
            String newValue,
            String otp) {

        jdbc.update("""
            INSERT INTO public.customer_contact_change_challenges
                (customer_id, contact_type, new_value, otp_hash,
                 expires_at, attempts, consumed, created_at)
            VALUES (?, ?, ?, ?, ?, 0, FALSE, CURRENT_TIMESTAMP)
            """,
            customerNo,
            contactType,
            newValue,
            encoder.encode(otp),
            Timestamp.from(Instant.now().plusSeconds(OTP_TTL_SECONDS)));
    }

    // ---------------------------------------------------------
    // VERIFY OTP
    // ---------------------------------------------------------

    private void verifyOtp(
            String customerNo,
            String contactType,
            String newValue,
            String otp) {

        Map<String, Object> challenge;

        try {
            challenge = jdbc.queryForMap("""
                SELECT id, otp_hash, expires_at, attempts
                  FROM public.customer_contact_change_challenges
                 WHERE customer_id = ?
                   AND contact_type = ?
                   AND new_value = ?
                   AND consumed = FALSE
                 ORDER BY created_at DESC
                 LIMIT 1
                 FOR UPDATE
                """, customerNo, contactType, newValue);

        } catch (EmptyResultDataAccessException ex) {
            throw new IllegalArgumentException(OTP_INVALID);
        }

        long id = ((Number) challenge.get("id")).longValue();
        int attempts = ((Number) challenge.get("attempts")).intValue();

        Timestamp expiry = (Timestamp) challenge.get("expires_at");

        if (attempts >= MAX_OTP_ATTEMPTS
                || expiry.toInstant().isBefore(Instant.now())) {

            consumeChallenge(id);
            throw new IllegalArgumentException(OTP_INVALID);
        }

        jdbc.update("""
            UPDATE public.customer_contact_change_challenges
               SET attempts = attempts + 1
             WHERE id = ?
            """, id);

        String otpHash = challenge.get("otp_hash").toString();

        if (!encoder.matches(otp, otpHash)) {

            if (attempts + 1 >= MAX_OTP_ATTEMPTS) {
                consumeChallenge(id);
            }

            throw new IllegalArgumentException(OTP_INVALID);
        }
    }

    // ---------------------------------------------------------
    // CHALLENGE HELPERS
    // ---------------------------------------------------------

    private boolean isCooldownActive(
            String customerNo,
            String contactType) {

        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM public.customer_contact_change_challenges
             WHERE customer_id = ?
               AND contact_type = ?
               AND consumed = FALSE
               AND created_at >
                   CURRENT_TIMESTAMP - (? * INTERVAL '1 second')
            """,
            Integer.class,
            customerNo,
            contactType,
            RESEND_COOLDOWN_SECONDS);

        return count != null && count > 0;
    }

    private void consumePreviousChallenge(
            String customerNo,
            String contactType) {

        jdbc.update("""
            UPDATE public.customer_contact_change_challenges
               SET consumed = TRUE
             WHERE customer_id = ?
               AND contact_type = ?
               AND consumed = FALSE
            """, customerNo, contactType);
    }

    private void consumeLatestChallenge(
            String customerNo,
            String contactType,
            String newValue) {

        jdbc.update("""
            UPDATE public.customer_contact_change_challenges
               SET consumed = TRUE
             WHERE customer_id = ?
               AND contact_type = ?
               AND new_value = ?
               AND consumed = FALSE
            """, customerNo, contactType, newValue);
    }

    private void consumeChallenge(long id) {

        jdbc.update("""
            UPDATE public.customer_contact_change_challenges
               SET consumed = TRUE
             WHERE id = ?
            """, id);
    }

    // ---------------------------------------------------------
    // DUPLICATE CHECKS
    // ---------------------------------------------------------

    private boolean isSameMobile(String customerNo, String mobile) {

        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM public.mb001001cr
             WHERE customer_id = ?
               AND registered_mobile = ?
            """, Integer.class, customerNo, mobile);

        return count != null && count > 0;
    }

    private boolean mobileAlreadyUsed(String mobile) {

        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM public.mb001001cr
             WHERE registered_mobile = ?
            """, Integer.class, mobile);

        return count != null && count > 0;
    }

    private boolean isSameEmail(String customerNo, String email) {

        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM public."MB001001"
             WHERE customer_no = ?
               AND LOWER(email) = ?
            """, Integer.class, customerNo, email);

        return count != null && count > 0;
    }

    private boolean emailAlreadyUsed(String email) {

        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM public."MB001001"
             WHERE LOWER(email) = ?
               AND status = 'ACTIVE'
            """, Integer.class, email);

        return count != null && count > 0;
    }

    // ---------------------------------------------------------
    // OTP GENERATOR
    // ---------------------------------------------------------

  
private String generateOtp() {
    return String.format("%04d", random.nextInt(10_000));
}

}
