
package com.abcbank.mobile_banking.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class Customer360Service {

    private final JdbcTemplate jdbc;
    private final CustomerIdentityResolver identityResolver;

    public Customer360Service(JdbcTemplate jdbc, CustomerIdentityResolver identityResolver) {
        this.jdbc = jdbc;
        this.identityResolver = identityResolver;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getCustomer360(String principal) {
        String customerNo = identityResolver.resolve(principal).customerNo();
        Map<String, Object> response = new LinkedHashMap<>();

        // 1. Customer information
        Map<String, Object> customer;

        try {
            customer = jdbc.queryForMap("""
                SELECT
                    u.customer_no,
                    u.full_name,
                    u.email,
                    cr.registered_mobile AS mobile,
                    u.status
                FROM public."MB001001" u
                JOIN public.mb001001cr cr
                    ON cr.customer_id = u.customer_no
                WHERE u.customer_no = ?
                  AND u.user_type = 'CU'
                  AND u.status = 'ACTIVE'
                """, customerNo);

        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Active customer not found");
        }

        response.put("customerName", customer.get("full_name"));
        response.put("accounts", jdbc.queryForList("""
            SELECT a.account_id AS "accountId", a.account_type AS type,
                   (CASE WHEN length(a.account_no)>4 THEN repeat('X',length(a.account_no)-4)||right(a.account_no,4) ELSE a.account_no END) AS "maskedNumber",
                   a.account_status AS status, a.branch_code AS "branchCode",
                   b.branch_name AS branch, b.ifsc_code AS ifsc, bal.available_balance AS "availableBalance", bal.ledger_balance AS "ledgerBalance", bal.currency
              FROM public."MB002003" a
              LEFT JOIN public."MB002002" b ON b.branch_code=a.branch_code
              LEFT JOIN public.mobile_account_balances bal ON bal.account_no=a.account_no AND bal.customer_no=a.customer_no
             WHERE a.customer_no=? ORDER BY a.created_on DESC
            """, customerNo));

        response.put("customerNo", maskCustomerNo(customerNo));
        response.put("email", customer.get("email"));
        response.put("mobile", customer.get("mobile"));

        // 2. FD and RD opening records
        // account_no here is the linked debit account,
        // not a separately generated FD/RD account number.

        List<Map<String, Object>> deposits = jdbc.queryForList("""
            SELECT
                'FD' AS deposit_type,
                account_no AS linked_debit_account_no,
                deposit_amount,
                maturity_date,
                maturity_value,
                req_status,
                created_on
            FROM public."MB004004"
            WHERE customer_no = ?
              AND req_status IN ('APPROVED', 'COMPLETED')

            UNION ALL

            SELECT
                'RD' AS deposit_type,
                account_no AS linked_debit_account_no,
                deposit_amount,
                maturity_date,
                maturity_value,
                req_status,
                created_on
            FROM public."MB004005"
            WHERE customer_no = ?
              AND req_status IN ('APPROVED', 'COMPLETED')

            ORDER BY created_on DESC
            """, customerNo, customerNo);

        response.put("deposits", deposits);
        long fdCount = deposits.stream().filter(d -> "FD".equals(String.valueOf(d.get("deposit_type")))).count();
        long rdCount = deposits.stream().filter(d -> "RD".equals(String.valueOf(d.get("deposit_type")))).count();
        Map<String, Object> depositsSummary = new LinkedHashMap<>();
        depositsSummary.put("fdCount", fdCount);
        depositsSummary.put("rdCount", rdCount);
        depositsSummary.put("totalCount", deposits.size());
        depositsSummary.put("totalBalance", null);
        depositsSummary.put("message", "Deposit balance requires core-banking deposit account balances.");
        response.put("depositsSummary", depositsSummary);

        // The FD amount is a principal amount.
        // The RD amount is a monthly instalment.
        // Therefore, these values must not be added together
        // and represented as the actual total deposit balance.

        response.put("totalDeposits", null);
        response.put("totalDepositsMessage",
                "Actual deposit balance is not available in the uploaded schema.");

        // 3. Latest EMI outstanding snapshot for each loan account
        List<Map<String, Object>> advances = jdbc.queryForList("""
            SELECT DISTINCT ON (loan_account_no)
                loan_account_no,
                total_outstanding,
                int_outstanding,
                instal_amount,
                expiry_date,
                req_status,
                created_on
            FROM public."MB004006"
            WHERE customer_no = ?
              AND req_status IN ('APPROVED', 'COMPLETED')
            ORDER BY loan_account_no, created_on DESC, id DESC
            """, customerNo);

        response.put("advances", advances);
        response.put("loans", advances);

        // Sum the latest outstanding snapshot for each loan.
        BigDecimal totalAdvances = BigDecimal.ZERO;

        for (Map<String, Object> advance : advances) {
            Object amount = advance.get("total_outstanding");

            if (amount instanceof BigDecimal) {
                totalAdvances = totalAdvances.add((BigDecimal) amount);
            } else if (amount != null) {
                totalAdvances = totalAdvances.add(
                        new BigDecimal(amount.toString()));
            }
        }

        response.put("totalAdvances", totalAdvances);

        // No overdue amount or due-date information is stored
        // in MB004006, so do not invent this value.
        response.put("overdueAmount", null);
        response.put("overdueMessage",
                "Overdue amount is not available in the uploaded schema.");

        response.put("message", "Customer 360 details fetched successfully");

        return response;
    }

    private String maskCustomerNo(String customerNo) {

        if (customerNo == null || customerNo.length() <= 4) {
            return customerNo;
        }

        return "XXXXXXX" +
                customerNo.substring(customerNo.length() - 4);
    }
}
