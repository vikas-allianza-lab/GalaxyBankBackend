
package com.abcbank.mobile_banking.service;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.abcbank.mobile_banking.dto.request.FundTransferRequest;
import com.abcbank.mobile_banking.dto.response.FundTransferResponse;
import com.abcbank.mobile_banking.dto.response.TransferStatusResponse;
import com.abcbank.mobile_banking.entity.FundTransferRequestEntity;
import com.abcbank.mobile_banking.repository.FundTransferRequestRepository;

@Service
public class FundTransferService {

    private final JdbcTemplate jdbc;
    private final FundTransferRequestRepository repository;

    public FundTransferService(
            JdbcTemplate jdbc,
            FundTransferRequestRepository repository) {
        this.jdbc = jdbc;
        this.repository = repository;
    }

    // =====================================================
    // SUBMIT NEFT / RTGS / IMPS TRANSFER REQUEST
    // =====================================================

    @Transactional
    public FundTransferResponse submit(
            String customerId,
            FundTransferRequest request) {

        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException(
                    "Invalid customer authentication.");
        }

        String beneficiaryAccount = request.accountNo().trim();
        String confirmAccount = request.reConfirmAccountNo().trim();

        if (!beneficiaryAccount.equals(confirmAccount)) {
            throw new IllegalArgumentException(
                    "Beneficiary account numbers do not match.");
        }

        String mode = request.paymentMode().trim()
                .toUpperCase(Locale.ROOT);

        String ifsc = request.ifscCode().trim()
                .toUpperCase(Locale.ROOT);

        // RTGS minimum amount validation
        if ("RTGS".equals(mode)
                && request.amount().compareTo(
                        new BigDecimal("200000.00")) < 0) {

            throw new IllegalArgumentException(
                    "RTGS minimum amount is Rs. 2,00,000. "
                    + "Please select NEFT or IMPS.");
        }

        // 1. Validate logged-in customer
        Long createdBy;

        try {
            createdBy = jdbc.queryForObject("""
                SELECT user_id
                FROM public."MB001001"
                WHERE customer_no = ?
                  AND user_type = 'CU'
                  AND status = 'ACTIVE'
                """,
                    Long.class,
                    customerId);

        } catch (EmptyResultDataAccessException ex) {
            throw new IllegalArgumentException(
                    "Active customer user record not found.");
        }

        // 2. Validate debit account ownership
        Map<String, Object> debit;

        try {
            debit = jdbc.queryForMap("""
                SELECT
                    account_no,
                    customer_no,
                    account_status,
                    account_type,
                    branch_code
                FROM public."MB002003"
                WHERE account_no = ?
                  AND customer_no = ?
                """,
                    request.debitAccountId().trim(),
                    customerId);

        } catch (EmptyResultDataAccessException ex) {
            throw new IllegalArgumentException(
                    "Debit account not found or does not belong to you.");
        }

        // 3. Check debit account status
        String accountStatus =
                String.valueOf(debit.get("account_status"));

        if (!"ACTIVE".equalsIgnoreCase(accountStatus)) {
            throw new IllegalArgumentException(
                    "Debit account is not active.");
        }

        // 4. Validate beneficiary IFSC
        Map<String, Object> beneficiary;

        try {
            beneficiary = jdbc.queryForMap("""
                SELECT
                    b.branch_code,
                    b.bank_code,
                    b.branch_name,
                    b.ifsc_code,
                    bank.bank_name
                FROM public."MB002002" b
                JOIN public."MB002001" bank
                  ON bank.bank_code = b.bank_code
                WHERE UPPER(b.ifsc_code) = ?
                """,
                    ifsc);

        } catch (EmptyResultDataAccessException ex) {
            throw new IllegalArgumentException(
                    "Invalid beneficiary IFSC code.");
        }

        String bankName =
                String.valueOf(beneficiary.get("bank_name"));

        // 5. Create transfer entity
        FundTransferRequestEntity entity =
                new FundTransferRequestEntity(
                        String.valueOf(debit.get("branch_code")),
                        customerId,
                        String.valueOf(debit.get("account_no")),
                        accountStatus,
                        String.valueOf(debit.get("account_type")),
                        mode,
                        request.beneficiaryName().trim(),
                        beneficiaryAccount,
                        bankName,
                        ifsc,
                        request.amount(),
                        request.remarks(),
                        createdBy
                );

        entity.setIsSys(true);

        // 6. Save transfer request
        FundTransferRequestEntity saved =
                repository.save(entity);

        // 7. Create customer notification
        createCustomerNotification(
                String.valueOf(debit.get("branch_code")),
                customerId,
                "Fund Transfer: Your " + mode
                        + " transfer request #"
                        + saved.getId()
                        + " has been submitted successfully. "
                        + "Awaiting bank processing.",
                "FUND_TRANSFER",
                String.valueOf(saved.getId())
        );

        Integer requestSource = Boolean.TRUE.equals(saved.getIsSys())
                ? 1 : 2;

        // 8. Return response
        return new FundTransferResponse(
                saved.getId(),
                saved.getReqStatus(),
                mode,
                request.amount(),
                saved.getCreatedOn(),
                requestSource,
                "Transfer request submitted successfully. "
                + "Awaiting bank processing."
        );
    }

    // =====================================================
    // GET TRANSFER STATUS
    // =====================================================

    public TransferStatusResponse getTransferStatus(
            String customerId,
            Long requestId) {

        if (customerId == null || customerId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Invalid customer authentication.");
        }

        FundTransferRequestEntity transfer = repository
                .findByIdAndCustomerNo(requestId, customerId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Transfer request not found."));

        String databaseStatus = transfer.getReqStatus();

        String apiStatus = switch (databaseStatus.toUpperCase()) {
            case "PENDING" -> "SUBMITTED";
            case "APPROVED" -> "PROCESSING";
            case "COMPLETED" -> "SUCCESS";
            case "FAILED", "REJECTED" -> "FAILED";
            case "CANCELLED" -> "REVERSED";
            default -> databaseStatus;
        };

        return new TransferStatusResponse(
                transfer.getId(),
                apiStatus,
                transfer.getAmount(),
                transfer.getPayMode(),
                null,
                transfer.getCreatedOn());
    }

    // =====================================================
    // CUSTOMER CONFIRMS TRANSFER REQUEST
    // =====================================================

    @Transactional
    public Map<String, Object> confirmTransfer(
            String customerId,
            Long requestId) {

        if (customerId == null || customerId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Invalid customer authentication.");
        }

        // 1. Find transfer request belonging to customer
        Map<String, Object> transfer;

        try {
            transfer = jdbc.queryForMap("""
                SELECT
                    id,
                    req_status,
                    customer_confirmed_on,
                    branch_code
                FROM public."MB004001"
                WHERE id = ?
                  AND customer_no = ?
                FOR UPDATE
                """,
                    requestId,
                    customerId);

        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Transfer request not found.");
        }

        String status =
                String.valueOf(transfer.get("req_status"));

        // 2. Validate transfer status
        if (!"PENDING".equalsIgnoreCase(status)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only pending transfer requests can be confirmed.");
        }

        if (transfer.get("customer_confirmed_on") != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Transfer request has already been confirmed.");
        }

        // 3. Get logged-in customer's internal user ID
        Long confirmedBy;

        try {
            confirmedBy = jdbc.queryForObject("""
                SELECT user_id
                FROM public."MB001001"
                WHERE customer_no = ?
                  AND user_type = 'CU'
                  AND status = 'ACTIVE'
                """,
                    Long.class,
                    customerId);

        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Active customer user record not found.");
        }

        // 4. Record customer confirmation
        // Transfer status remains PENDING
        int updated = jdbc.update("""
            UPDATE public."MB004001"
            SET customer_confirmed_by = ?,
                customer_confirmed_on = CURRENT_TIMESTAMP
            WHERE id = ?
              AND customer_no = ?
              AND req_status = 'PENDING'
              AND customer_confirmed_on IS NULL
            """,
                confirmedBy,
                requestId,
                customerId);

        if (updated != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Transfer request could not be confirmed. "
                    + "Please refresh and try again.");
        }

        // 5. Create customer notification
        createCustomerNotification(
                String.valueOf(transfer.get("branch_code")),
                customerId,
                "Fund Transfer: Your transfer request #"
                        + requestId
                        + " has been confirmed successfully. "
                        + "Awaiting bank processing.",
                "FUND_TRANSFER",
                String.valueOf(requestId)
        );

        // 6. Return confirmation response
        return Map.of(
                "success", true,
                "message", "Transfer request confirmed successfully.",
                "requestId", requestId,
                "status", "PENDING",
                "confirmed", true
        );
    }

    // =====================================================
    // CUSTOMER NOTIFICATION HELPER
    // =====================================================

    private void createCustomerNotification(
            String branchCode,
            String customerId,
            String message,
            String referenceType,
            String referenceId) {

        jdbc.update("""
            INSERT INTO public."MB003001" (
                branch_code,
                customer_no,
                notification,
                category,
                reference_type,
                reference_id,
                is_read,
                created_on
            )
            VALUES (
                ?, ?, ?, 'FUND_TRANSFER', ?, ?, FALSE, CURRENT_TIMESTAMP
            )
            """,
                branchCode,
                customerId,
                message,
                referenceType,
                referenceId);
    }
}
