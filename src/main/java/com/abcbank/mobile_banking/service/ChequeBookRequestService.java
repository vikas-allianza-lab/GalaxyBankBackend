
package com.abcbank.mobile_banking.service;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.abcbank.mobile_banking.dto.request.ChequeBookRequest;
import com.abcbank.mobile_banking.dto.response.ChequeBookResponse;

@Service
public class ChequeBookRequestService {

    private final JdbcTemplate jdbc;

    public ChequeBookRequestService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public ChequeBookResponse submit(
            String customerId,
            ChequeBookRequest request) {

        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException(
                    "Invalid customer authentication."
            );
        }

        if (request == null) {
            throw new IllegalArgumentException(
                    "Cheque book request cannot be empty."
            );
        }

        // 1. Validate logged-in customer
        Long createdBy;

        try {
            createdBy = jdbc.queryForObject("""
                SELECT user_id
                FROM public."MB001001"
                WHERE customer_no = ?
                  AND user_role = 'CU'
                  AND status = 'ACTIVE'
                """,
                Long.class,
                customerId.trim()
            );

        } catch (EmptyResultDataAccessException ex) {
            throw new IllegalArgumentException(
                    "Active customer user record not found."
            );
        }

        // 2. Validate debit account ownership
        Map<String, Object> account;

        try {
            account = jdbc.queryForMap("""
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
                request.accountNo().trim(),
                customerId.trim()
            );

        } catch (EmptyResultDataAccessException ex) {
            throw new IllegalArgumentException(
                    "Account not found or does not belong to you."
            );
        }

        // 3. Validate account status
        String accountStatus =
                String.valueOf(account.get("account_status"));

        if (!"ACTIVE".equalsIgnoreCase(accountStatus)) {
            throw new IllegalArgumentException(
                    "Account is not active."
            );
        }

        // 4. Validate cheque book type and determine leaves
        if (request.bookType() == null
                || request.bookType().isBlank()) {
            throw new IllegalArgumentException(
                    "Cheque book type is required."
            );
        }

        String bookType = request.bookType()
                .trim()
                .toUpperCase(Locale.ROOT);

        int noOfLeaves;

        if ("STANDARD".equals(bookType)) {
            noOfLeaves = 25;

        } else if ("PREMIUM".equals(bookType)) {
            noOfLeaves = 50;

        } else {
            throw new IllegalArgumentException(
                    "Book type must be STANDARD or PREMIUM."
            );
        }

        // 5. One cheque book per request
        int noOfBooks = 1;

        // 6. Validate delivery option
        if (request.delivery() == null
                || request.delivery().isBlank()) {
            throw new IllegalArgumentException(
                    "Delivery option is required."
            );
        }

        String delivery = request.delivery()
                .trim()
                .toUpperCase(Locale.ROOT);

        // 7. Insert cheque book request
        return jdbc.queryForObject("""
            INSERT INTO public."MB004002" (
                branch_code,
                customer_no,
                account_no,
                account_status,
                account_type,
                book_type,
                no_of_leaves,
                delivery,
                created_by
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING
                id,
                req_status,
                account_no,
                no_of_leaves,
                delivery,
                created_on
            """,

            (rs, rowNum) -> new ChequeBookResponse(
                    rs.getLong("id"),
                    rs.getString("req_status"),
                    rs.getString("account_no"),
                    rs.getInt("no_of_leaves"),
                    rs.getString("delivery"),
                    rs.getObject(
                            "created_on",
                            OffsetDateTime.class
                    ),
                    "Cheque book request submitted successfully."
            ),

            account.get("branch_code"),
            customerId.trim(),
            account.get("account_no"),
            account.get("account_status"),
            account.get("account_type"),
            bookType,
            noOfLeaves,
            delivery,
            createdBy
        );
    }
}
