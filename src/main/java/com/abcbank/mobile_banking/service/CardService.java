package com.abcbank.mobile_banking.service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.abcbank.mobile_banking.dto.request.CardBlockRequest;
import com.abcbank.mobile_banking.dto.request.CardLimitsUpdateRequest;
import com.abcbank.mobile_banking.dto.request.CardPinChangeRequest;
import com.abcbank.mobile_banking.dto.request.CardRequestCreateRequest;
import com.abcbank.mobile_banking.dto.request.CardUnblockRequest;
import com.abcbank.mobile_banking.dto.response.CardPinOtpResponse;
import com.abcbank.mobile_banking.integration.CardProcessorClient;

@Service
public class CardService {
    private static final int OTP_TTL_SECONDS = 300;
    private static final int OTP_RESEND_SECONDS = 60;
    private static final int MAX_OTP_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter.ofPattern("MM/yy");

    private final JdbcTemplate jdbc;
    private final CustomerIdentityResolver identities;
    private final SensitiveActionMpinVerifier mpinVerifier;
    private final CardProcessorClient processor;
    private final PasswordEncoder passwordEncoder;
    private final OtpDeliveryService otpDelivery;

    public CardService(JdbcTemplate jdbc, CustomerIdentityResolver identities,
                       SensitiveActionMpinVerifier mpinVerifier, CardProcessorClient processor,
                       PasswordEncoder passwordEncoder, OtpDeliveryService otpDelivery) {
        this.jdbc = jdbc;
        this.identities = identities;
        this.mpinVerifier = mpinVerifier;
        this.processor = processor;
        this.passwordEncoder = passwordEncoder;
        this.otpDelivery = otpDelivery;
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> list(String principal) {
        String customer = identities.resolve(principal).customerNo();
        return jdbc.queryForList("""
            SELECT card_id AS \"cardId\", card_type AS type, masked_number AS \"maskedNumber\",
                   status, network
              FROM public.cards WHERE customer_no = ? ORDER BY created_on DESC
            """, customer);
    }

    @Transactional(readOnly = true)
    public Map<String,Object> details(String principal, String cardId) {
        Map<String,Object> card = ownedCard(identities.resolve(principal).customerNo(), cardId);
        return Map.of("cardId", card.get("card_id"), "type", card.get("type"),
                      "maskedNumber", card.get("maskedNumber"), "status", card.get("status"),
                      "network", card.get("network") == null ? "" : card.get("network"));
    }

    @Transactional
    public Map<String,Object> block(String principal, String cardId, CardBlockRequest request) {
        var identity = identities.resolve(principal);
        mpinVerifier.verify(principal, request.mpin());
        Map<String,Object> card = ownedCard(identity.customerNo(), cardId);
        if (!"ACTIVE".equalsIgnoreCase(String.valueOf(card.get("status")))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an active card can be blocked.");
        }
        String type = request.blockType().toUpperCase();
        String reason = request.reason().toLowerCase();
        processor.block(String.valueOf(card.get("processor_card_ref")), type, reason);
        jdbc.update("UPDATE public.cards SET status='BLOCKED', block_type=?, modified_on=now() WHERE card_id=CAST(? AS uuid)", type, cardId);
        event(cardId, identity.customerNo(), identity.userId(), "BLOCK", reason, type, "{}");
        notifyCustomer(identity.customerNo(), "Your card ending " + lastFour(String.valueOf(card.get("maskedNumber"))) + " has been blocked.");
        return Map.of("cardId", cardId, "status", "BLOCKED", "blockType", type,
                      "referenceNo", reference("CB"));
    }

    @Transactional
    public Map<String,Object> unblock(String principal, String cardId, CardUnblockRequest request) {
        var identity = identities.resolve(principal);
        mpinVerifier.verify(principal, request.mpin());
        Map<String,Object> card = ownedCard(identity.customerNo(), cardId);
        if (!"BLOCKED".equalsIgnoreCase(String.valueOf(card.get("status")))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Card is not blocked.");
        }
        if (!"TEMPORARY".equalsIgnoreCase(String.valueOf(card.get("block_type")))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A permanently blocked card cannot be unblocked.");
        }
        processor.unblock(String.valueOf(card.get("processor_card_ref")));
        jdbc.update("UPDATE public.cards SET status='ACTIVE', block_type=NULL, modified_on=now() WHERE card_id=CAST(? AS uuid)", cardId);
        event(cardId, identity.customerNo(), identity.userId(), "UNBLOCK", null, null, "{}");
        notifyCustomer(identity.customerNo(), "Your card ending " + lastFour(String.valueOf(card.get("maskedNumber"))) + " has been unblocked.");
        return Map.of("cardId", cardId, "status", "ACTIVE", "referenceNo", reference("CU"));
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> limits(String principal, String cardId) {
        Map<String,Object> card = ownedCard(identities.resolve(principal).customerNo(), cardId);
        return jdbc.queryForList("""
            SELECT limit_key AS key, min_value AS min, max_value AS max, step_value AS step,
                   current_value AS current, default_value AS \"default\"
              FROM public.card_limits WHERE card_id=CAST(? AS uuid) ORDER BY limit_key
            """, cardId);
    }

    @Transactional
    public Map<String,Object> updateLimits(String principal, String cardId, CardLimitsUpdateRequest request) {
        var identity = identities.resolve(principal);
        mpinVerifier.verify(principal, request.mpin());
        Map<String,Object> card = ownedCard(identity.customerNo(), cardId);
        Map<String,BigDecimal> changes = new LinkedHashMap<>();
        changes.put("ATM", request.atm()); changes.put("POS", request.pos());
        changes.put("ONLINE", request.online()); changes.put("INTERNATIONAL", request.intl());
        for (var entry : changes.entrySet()) {
            Map<String,Object> rule;
            try {
                rule = jdbc.queryForMap("SELECT min_value,max_value,step_value,per_transaction_limit FROM public.card_limits WHERE card_id=CAST(? AS uuid) AND limit_key=?", cardId, entry.getKey());
            } catch (EmptyResultDataAccessException ex) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Limit configuration is missing for " + entry.getKey());
            }
            BigDecimal value = entry.getValue();
            BigDecimal min = (BigDecimal) rule.get("min_value");
            BigDecimal max = (BigDecimal) rule.get("max_value");
            BigDecimal step = (BigDecimal) rule.get("step_value");
            BigDecimal perTransaction = (BigDecimal) rule.get("per_transaction_limit");
            if (value.compareTo(perTransaction) < 0 || value.compareTo(min) < 0 || value.compareTo(max) > 0
                    || value.subtract(min).remainder(step).compareTo(BigDecimal.ZERO) != 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    entry.getKey() + " limit must be within the configured range and step.");
            }
        }
        processor.updateLimits(String.valueOf(card.get("processor_card_ref")), changes);
        changes.forEach((key,value) -> jdbc.update("UPDATE public.card_limits SET current_value=?, modified_on=now() WHERE card_id=CAST(? AS uuid) AND limit_key=?", value, cardId, key));
        String changesJson = "{\"ATM\":" + changes.get("ATM") + ",\"POS\":" + changes.get("POS")
            + ",\"ONLINE\":" + changes.get("ONLINE") + ",\"INTERNATIONAL\":" + changes.get("INTERNATIONAL") + "}";
        event(cardId, identity.customerNo(), identity.userId(), "LIMIT_CHANGE", null, null, changesJson);
        notifyCustomer(identity.customerNo(), "Your card limits have been updated.");
        return Map.of("cardId", cardId, "status", "UPDATED", "limits", changes);
    }

    @Transactional
    public CardPinOtpResponse sendPinOtp(String principal, String cardId) {
        String customer = identities.resolve(principal).customerNo();
        ownedCard(customer, cardId);
        String mobile = jdbc.queryForObject("SELECT registered_mobile FROM public.mb001001cr WHERE customer_id=?", String.class, customer);
        Integer recent = jdbc.queryForObject("SELECT count(*) FROM public.card_pin_otps WHERE customer_no=? AND card_id=CAST(? AS uuid) AND consumed_at IS NULL AND sent_at > now() - (? * interval '1 second')", Integer.class, customer, cardId, OTP_RESEND_SECONDS);
        if (recent != null && recent > 0) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Please wait before requesting another OTP.");
        }
        jdbc.update("UPDATE public.card_pin_otps SET consumed_at=now() WHERE customer_no=? AND card_id=CAST(? AS uuid) AND consumed_at IS NULL", customer, cardId);
        String otp = String.format("%06d", RANDOM.nextInt(1_000_000));
        jdbc.update("INSERT INTO public.card_pin_otps(card_id,customer_no,otp_hash,expires_at) VALUES (CAST(? AS uuid),?,?,now() + (? * interval '1 second'))", cardId, customer, passwordEncoder.encode(otp), OTP_TTL_SECONDS);
        otpDelivery.sendOtp(mobile, otp);
        return new CardPinOtpResponse("OTP sent to your registered mobile number.", OTP_TTL_SECONDS, OTP_RESEND_SECONDS);
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Map<String,Object> changePin(String principal, String cardId, CardPinChangeRequest request) {
        String customer = identities.resolve(principal).customerNo();
        Map<String,Object> card = ownedCard(customer, cardId);
        if (allSame(request.newPin()) || sequential(request.newPin())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a PIN that is not repeated or sequential.");
        }
        YearMonth expiry;
        try { expiry = YearMonth.parse(request.expiry(), EXPIRY_FORMAT); }
        catch (Exception ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid card expiry date."); }
        if (expiry.isBefore(YearMonth.now())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Card expiry date has passed.");

        Map<String,Object> challenge;
        try {
            challenge = jdbc.queryForMap("""
                SELECT otp_id,otp_hash,attempts,(expires_at > now()) AS is_valid FROM public.card_pin_otps
                 WHERE customer_no=? AND card_id=CAST(? AS uuid) AND consumed_at IS NULL
                 ORDER BY sent_at DESC LIMIT 1 FOR UPDATE
                """, customer, cardId);
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Request a new OTP first.");
        }
        int attempts = ((Number)challenge.get("attempts")).intValue();
        if (attempts >= MAX_OTP_ATTEMPTS || !Boolean.TRUE.equals(challenge.get("is_valid"))) {
            jdbc.update("UPDATE public.card_pin_otps SET consumed_at=now() WHERE otp_id=?", challenge.get("otp_id"));
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "OTP is invalid or expired. Request a new OTP.");
        }
        jdbc.update("UPDATE public.card_pin_otps SET attempts=attempts+1 WHERE otp_id=?", challenge.get("otp_id"));
        if (!passwordEncoder.matches(request.otp(), String.valueOf(challenge.get("otp_hash")))) {
            if (attempts + 1 >= MAX_OTP_ATTEMPTS) jdbc.update("UPDATE public.card_pin_otps SET consumed_at=now() WHERE otp_id=?", challenge.get("otp_id"));
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid OTP.");
        }
        // Sensitive values are passed directly to the processor adapter and are never persisted or logged here.
        processor.changePin(String.valueOf(card.get("processor_card_ref")), request.expiry(), request.cvv(), request.newPin());
        jdbc.update("UPDATE public.card_pin_otps SET consumed_at=now() WHERE otp_id=?", challenge.get("otp_id"));
        var identity = identities.resolve(principal);
        event(cardId, customer, identity.userId(), "PIN_CHANGE", null, null, "{}");
        notifyCustomer(customer, "Your card PIN was changed successfully.");
        return Map.of("cardId", cardId, "status", "PIN_CHANGED");
    }

    @Transactional
    public Map<String,Object> requestCard(String principal, CardRequestCreateRequest request) {
        var identity = identities.resolve(principal);
        mpinVerifier.verify(principal, request.mpin());
        if (!Boolean.TRUE.equals(request.termsAccepted())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Terms must be accepted.");
        String accountNo = ownedAccountNo(identity.customerNo(), request.accountId());
        String preference = request.deliveryPreference().toUpperCase();
        if ("BRANCH".equals(preference) && (request.branchCode() == null || request.branchCode().isBlank()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch code is required for branch delivery.");
        if (!"BRANCH".equals(preference) && request.branchCode() != null && !request.branchCode().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch code is allowed only for branch delivery.");
        if (("DEFAULT".equals(preference) || "PRIMARY".equals(preference)) && request.address() != null && request.address().length() > 500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Address is too long.");
        Long id = jdbc.queryForObject("""
            INSERT INTO public.card_requests(customer_no,account_no,name_on_card,delivery_preference,delivery_address,branch_code,terms_accepted,created_by)
            VALUES (?,?,?,?,?,?,TRUE,?) RETURNING request_id
            """, Long.class, identity.customerNo(), accountNo, request.nameOnCard().trim(), preference,
            request.address(), request.branchCode(), identity.userId());
        notifyCustomer(identity.customerNo(), "Your card request has been submitted. Reference: " + id);
        return Map.of("requestId", id, "status", "PENDING");
    }

    @Transactional(readOnly = true)
    public Map<String,Object> transactions(String principal, String cardId, LocalDate fromDate, LocalDate toDate, int page, int size) {
        String customer = identities.resolve(principal).customerNo();
        ownedCard(customer, cardId);
        if (page < 0 || size < 1 || size > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination values.");
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fromDate must be on or before toDate.");
        List<Map<String,Object>> items = jdbc.queryForList("""
            SELECT title, merchant AS subtitle, amount, direction, status, txn_at AS date
              FROM public.card_transactions
             WHERE card_id=CAST(? AS uuid)
               AND (CAST(? AS date) IS NULL OR txn_at::date >= CAST(? AS date))
               AND (CAST(? AS date) IS NULL OR txn_at::date <= CAST(? AS date))
             ORDER BY txn_at DESC, transaction_id DESC LIMIT ? OFFSET ?
            """, cardId, fromDate, fromDate, toDate, toDate, size, page*size);
        Long total = jdbc.queryForObject("""
            SELECT count(*) FROM public.card_transactions
             WHERE card_id=CAST(? AS uuid)
               AND (CAST(? AS date) IS NULL OR txn_at::date >= CAST(? AS date))
               AND (CAST(? AS date) IS NULL OR txn_at::date <= CAST(? AS date))
            """, Long.class, cardId, fromDate, fromDate, toDate, toDate);
        return Map.of("items", items, "page", page, "size", size, "totalElements", total == null ? 0 : total);
    }

    private Map<String,Object> ownedCard(String customer, String cardId) {
        try {
            UUID.fromString(cardId);
            return jdbc.queryForMap("""
                SELECT card_id,processor_card_ref,customer_no,account_no,card_type AS type,
                       masked_number AS \"maskedNumber\",status,network,block_type
                  FROM public.cards WHERE card_id=CAST(? AS uuid) AND customer_no=?
                """, cardId, customer);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid card ID.");
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Card not found for this customer.");
        }
    }

    private String ownedAccountNo(String customer, String accountId) {
        try {
            return jdbc.queryForObject("SELECT account_no FROM public.\"MB002003\" WHERE account_id=CAST(? AS uuid) AND customer_no=?", String.class, accountId, customer);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found for this customer.");
        }
    }

    private void event(String cardId, String customer, Long userId, String type, String reason, String blockType, String json) {
        jdbc.update("INSERT INTO public.card_events(card_id,customer_no,event_type,reason,block_type,event_data,created_by) VALUES (CAST(? AS uuid),?,?,?,?,CAST(? AS jsonb),?)",
            cardId, customer, type, reason, blockType, json, userId);
    }

    private void notifyCustomer(String customer, String message) {
        jdbc.update("INSERT INTO public.\"MB003001\"(customer_no,notification) VALUES (?,?)", customer, message);
    }

    private static String lastFour(String masked) { return masked.substring(Math.max(0, masked.length()-4)); }
    private static String reference(String prefix) { return prefix + System.currentTimeMillis() + String.format("%04d", RANDOM.nextInt(10_000)); }
    private static boolean allSame(String pin) { return pin.chars().distinct().count() == 1; }
    private static boolean sequential(String pin) {
        int d = pin.charAt(1)-pin.charAt(0);
        if (Math.abs(d) != 1) return false;
        for (int i=2;i<pin.length();i++) if (pin.charAt(i)-pin.charAt(i-1) != d) return false;
        return true;
    }
}
