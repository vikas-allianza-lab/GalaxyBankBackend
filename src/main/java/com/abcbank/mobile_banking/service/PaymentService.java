package com.abcbank.mobile_banking.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.abcbank.mobile_banking.dto.request.PaymentBillRequest;
import com.abcbank.mobile_banking.dto.request.PaymentRechargeRequest;
import com.abcbank.mobile_banking.dto.request.PaymentUpiRequest;
import com.abcbank.mobile_banking.integration.PaymentProviderClient;

@Service
public class PaymentService {
    private final JdbcTemplate jdbc;
    private final CustomerIdentityResolver identities;
    private final SensitiveActionMpinVerifier mpinVerifier;
    private final PaymentProviderClient provider;

    public PaymentService(JdbcTemplate jdbc, CustomerIdentityResolver identities,
                          SensitiveActionMpinVerifier mpinVerifier, PaymentProviderClient provider) {
        this.jdbc = jdbc; this.identities = identities; this.mpinVerifier = mpinVerifier; this.provider = provider;
    }

    @Transactional(readOnly = true)
    public Map<String,Object> history(String principal, String type, int page, int size) {
        String customer = identities.resolve(principal).customerNo();
        String filter = type == null ? "ALL" : type.toUpperCase();
        if (!List.of("ALL","SENT","RECEIVED","BILLS").contains(filter))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type must be ALL, SENT, RECEIVED or BILLS.");
        validatePage(page,size);
        List<Map<String,Object>> items = jdbc.queryForList("""
            WITH combined AS (
              SELECT txn_id::text AS item_id, title,
                     COALESCE(category, mode, 'TRANSFER') AS subtitle,
                     amount, CASE WHEN kind='DEBIT' THEN 'SENT' ELSE 'RECEIVED' END AS direction,
                     status, txn_at AS date,
                     CASE WHEN category='BILL_PAYMENT' THEN 'BILLS'
                          WHEN kind='DEBIT' THEN 'SENT' ELSE 'RECEIVED' END AS filter_type
                FROM public.mobile_account_transactions
               WHERE customer_no=?
              UNION ALL
              SELECT pr.request_id::text AS item_id,
                     CASE pr.payment_type WHEN 'BILL' THEN 'Bill payment'
                          WHEN 'RECHARGE' THEN 'Mobile recharge' ELSE 'UPI payment' END AS title,
                     pr.payment_type AS subtitle, pr.amount, 'SENT' AS direction,
                     pr.status, pr.created_on AS date,
                     CASE WHEN pr.payment_type='BILL' THEN 'BILLS' ELSE 'SENT' END AS filter_type
                FROM public.mobile_payment_requests pr
               WHERE pr.customer_no=?
                 AND NOT EXISTS (
                   SELECT 1 FROM public.mobile_account_transactions tx
                    WHERE tx.customer_no=pr.customer_no
                      AND tx.source_txn_id=pr.partner_reference
                      AND pr.partner_reference IS NOT NULL
                 )
            )
            SELECT title,subtitle,amount,direction,status,date
              FROM combined
             WHERE (?='ALL' OR filter_type=?)
             ORDER BY date DESC,item_id DESC LIMIT ? OFFSET ?
            """, customer, customer, filter, filter, size, page*size);
        Long total = jdbc.queryForObject("""
            WITH combined AS (
              SELECT CASE WHEN category='BILL_PAYMENT' THEN 'BILLS'
                          WHEN kind='DEBIT' THEN 'SENT' ELSE 'RECEIVED' END AS filter_type
                FROM public.mobile_account_transactions WHERE customer_no=?
              UNION ALL
              SELECT CASE WHEN pr.payment_type='BILL' THEN 'BILLS' ELSE 'SENT' END AS filter_type
                FROM public.mobile_payment_requests pr
               WHERE pr.customer_no=?
                 AND NOT EXISTS (
                   SELECT 1 FROM public.mobile_account_transactions tx
                    WHERE tx.customer_no=pr.customer_no
                      AND tx.source_txn_id=pr.partner_reference
                      AND pr.partner_reference IS NOT NULL
                 )
            ) SELECT count(*) FROM combined WHERE (?='ALL' OR filter_type=?)
            """, Long.class, customer, customer, filter, filter);
        return Map.of("items", items, "page", page, "size", size, "totalElements", total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> billers(String principal, String category) {
        identities.resolve(principal);
        return jdbc.queryForList("""
            SELECT biller_id AS \"billerId\", category, biller_name AS name
              FROM public.mobile_billers
             WHERE status='ACTIVE' AND (CAST(? AS varchar) IS NULL OR UPPER(category)=UPPER(CAST(? AS varchar)))
             ORDER BY category,biller_name
            """, category, category);
    }

    @Transactional
    public Map<String,Object> payBill(String principal, PaymentBillRequest request, String idempotencyKey) {
        var identity = identities.resolve(principal);
        mpinVerifier.verify(principal, request.mpin());
        String accountNo = ownedAccountNo(identity.customerNo(), request.accountId());
        Map<String,Object> biller;
        try { biller = jdbc.queryForMap("SELECT partner_biller_code,biller_name FROM public.mobile_billers WHERE biller_id=? AND status='ACTIVE'", request.billerId()); }
        catch (EmptyResultDataAccessException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active biller not found."); }
        String fingerprint = fingerprint("BILL|"+request.billerId()+"|"+request.consumerNo()+"|"+accountNo+"|"+request.amount());
        Map<String,Object> prior = existing(identity.customerNo(), idempotencyKey, fingerprint);
        if (prior != null) return prior;
        PaymentProviderClient.PaymentResult result = provider.payBill(String.valueOf(biller.get("partner_biller_code")), request.consumerNo(), accountNo, request.amount(), idempotencyKey);
        return savePayment(identity.customerNo(), accountNo, "BILL", idempotencyKey, fingerprint, result);
    }

    @Transactional
    public Map<String,Object> recharge(String principal, PaymentRechargeRequest request, String idempotencyKey) {
        var identity = identities.resolve(principal);
        mpinVerifier.verify(principal, request.mpin());
        String accountNo = ownedAccountNo(identity.customerNo(), request.accountId());
        String fingerprint = fingerprint("RECHARGE|"+request.mobileNo()+"|"+request.operator()+"|"+request.planId()+"|"+accountNo);
        Map<String,Object> prior = existing(identity.customerNo(), idempotencyKey, fingerprint);
        if (prior != null) return prior;
        PaymentProviderClient.PaymentResult result = provider.recharge(request.mobileNo(), request.operator(), request.planId(), accountNo, idempotencyKey);
        return savePayment(identity.customerNo(), accountNo, "RECHARGE", idempotencyKey, fingerprint, result);
    }

    @Transactional
    public Map<String,Object> payUpi(String principal, PaymentUpiRequest request, String idempotencyKey) {
        var identity = identities.resolve(principal);
        mpinVerifier.verify(principal, request.mpin());
        String accountNo = ownedAccountNo(identity.customerNo(), request.accountId());
        String fingerprint = fingerprint("UPI|"+request.vpa()+"|"+request.amount()+"|"+accountNo+"|"+request.remarks());
        Map<String,Object> prior = existing(identity.customerNo(), idempotencyKey, fingerprint);
        if (prior != null) return prior;
        // UPI PIN is never collected or verified by this backend. It belongs in the NPCI/PSP UPI SDK flow.
        PaymentProviderClient.PaymentResult result = provider.payUpi(request.vpa(), request.amount(), accountNo, request.remarks(), idempotencyKey);
        return savePayment(identity.customerNo(), accountNo, "UPI", idempotencyKey, fingerprint, result);
    }

    private Map<String,Object> existing(String customer, String key, String fingerprint) {
        validateIdempotencyKey(key);
        try {
            Map<String,Object> row = jdbc.queryForMap("""
                SELECT request_id AS \"requestId\",status,partner_reference AS \"referenceNo\",request_fingerprint
                  FROM public.mobile_payment_requests WHERE customer_no=? AND idempotency_key=?
                """, customer, key);
            if (!fingerprint.equals(String.valueOf(row.get("request_fingerprint"))))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key was already used for a different payment request.");
            return Map.of("requestId", row.get("requestId"), "status", row.get("status"),
                          "referenceNo", row.get("referenceNo") == null ? "" : row.get("referenceNo"));
        } catch (EmptyResultDataAccessException ex) { return null; }
    }

    private Map<String,Object> savePayment(String customer, String accountNo, String type, String key,
                                            String fingerprint, PaymentProviderClient.PaymentResult result) {
        String status = result.status() == null ? "PENDING" : result.status().toUpperCase();
        if (!List.of("PENDING","SUCCESS","FAILED").contains(status) || result.amount() == null || result.amount().compareTo(BigDecimal.ZERO) <= 0)
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Payment partner returned an invalid response.");
        UUID id = jdbc.queryForObject("""
            INSERT INTO public.mobile_payment_requests(customer_no,account_no,payment_type,idempotency_key,request_fingerprint,partner_reference,amount,status)
            VALUES (?,?,?,?,?,?,?,?) RETURNING request_id
            """, UUID.class, customer, accountNo, type, key, fingerprint, result.partnerReference(), result.amount(), status);
        return Map.of("requestId", id, "status", status,
                      "referenceNo", result.partnerReference() == null ? "" : result.partnerReference());
    }

    private String ownedAccountNo(String customer, String accountId) {
        try { return jdbc.queryForObject("SELECT account_no FROM public.\"MB002003\" WHERE account_id=CAST(? AS uuid) AND customer_no=?", String.class, accountId, customer); }
        catch (Exception ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found for this customer."); }
    }
    private static void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required (maximum 100 characters).");
    }
    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be >= 0 and size must be between 1 and 100.");
    }
    private static String fingerprint(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("Unable to fingerprint payment request.", ex); }
    }
}
