
package com.abcbank.mobile_banking.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.abcbank.mobile_banking.dto.request.LoanLeadRequest;

@Service
public class LoanLeadQueryService {

    private final JdbcTemplate jdbc;

    public LoanLeadQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // LON-07: GET PREVIOUSLY USED PRODUCT CODES
    public Map<String, Object> getProductCodes(String customerNo) {

        List<Map<String, Object>> products = jdbc.queryForList("""
            SELECT DISTINCT product_code
            FROM public."MB004010"
            WHERE customer_no = ?
              AND product_code IS NOT NULL
            ORDER BY product_code
            """, customerNo);

        Map<String, Object> response = new LinkedHashMap<>();

        response.put("customerNo", customerNo);
        response.put("productCodes", products);
        response.put("count", products.size());
        response.put("message",
                "Previously used loan product codes retrieved successfully");

        return response;
    }

    // LON-08: GET ALL LOAN APPLICATIONS
    public Map<String, Object> getLoanApplications(String customerNo) {

        List<Map<String, Object>> applications = jdbc.queryForList("""
            SELECT
                id AS request_id,
                customer_no,
                branch_code,
                mobile,
                email,
                product_code,
                loan_amount,
                tenure_months,
                purpose,
                security_value,
                total_income,
                total_exp,
                eligibility,
                req_status,
                created_on,
                modified_on
            FROM public."MB004010"
            WHERE customer_no = ?
            ORDER BY created_on DESC
            """, customerNo);

        Map<String, Object> response = new LinkedHashMap<>();

        response.put("customerNo", customerNo);
        response.put("applications", applications);
        response.put("count", applications.size());
        response.put("message",
                "Loan applications retrieved successfully");

        return response;
    }

    // LON-09: GET LOAN APPLICATION BY ID
    public Map<String, Object> getLoanApplication(
            String customerNo, Long id) {

        try {

            Map<String, Object> application = jdbc.queryForMap("""
                SELECT
                    id AS request_id,
                    customer_no,
                    branch_code,
                    mobile,
                    email,
                    product_code,
                    loan_amount,
                    tenure_months,
                    purpose,
                    security_value,
                    total_income,
                    total_exp,
                    eligibility,
                    req_status,
                    created_on,
                    modified_on
                FROM public."MB004010"
                WHERE id = ?
                  AND customer_no = ?
                """, id, customerNo);

            Map<String, Object> response = new LinkedHashMap<>();

            response.put("application", application);
            response.put("message",
                    "Loan application retrieved successfully");

            return response;

        } catch (EmptyResultDataAccessException ex) {

            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Loan application not found"
            );
        }
    }

    // LON-08: POST FRESH LOAN APPLICATION
    @Transactional
    public Map<String, Object> submitLoanApplication(
            String customerNo,
            LoanLeadRequest request) {

        // 1. Validate customer number
        if (customerNo == null || customerNo.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Customer login is required"
            );
        }

        // 2. Get logged-in customer details
        Map<String, Object> customer;

        try {

            customer = jdbc.queryForMap("""
                SELECT
                    user_id,
                    customer_no,
                    branch,
                    mobile,
                    email
                FROM public."MB001001"
                WHERE customer_no = ?
                  AND status = 'ACTIVE'
                """, customerNo);

        } catch (EmptyResultDataAccessException ex) {

            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Active customer record not found"
            );
        }

        Long createdBy =
                ((Number) customer.get("user_id")).longValue();

        String customerBranch =
                String.valueOf(customer.get("branch"));

        // 3. Map existing customer branch to branch master code
        String branchCode;

        if ("PUNE001".equalsIgnoreCase(customerBranch)) {
            branchCode = "BR001";
        } else if ("BR001".equalsIgnoreCase(customerBranch)
                || "BR002".equalsIgnoreCase(customerBranch)
                || "BR003".equalsIgnoreCase(customerBranch)) {
            branchCode = customerBranch.toUpperCase();
        } else {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Customer branch has no configured branch master mapping: "
                            + customerBranch
            );
        }

        // 4. Validate branch foreign key
        Integer branchCount = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM public."MB002002"
            WHERE branch_code = ?
            """,
            Integer.class,
            branchCode
        );

        if (branchCount == null || branchCount == 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid branch code: " + branchCode
            );
        }

        // 5. Validate loan product
        String productCode = request.productType()
                .trim()
                .toUpperCase();

        // 6. Insert fresh loan application
        Map<String, Object> application = jdbc.queryForMap("""
            INSERT INTO public."MB004010" (
                branch_code,
                customer_no,
                mobile,
                email,
                product_code,
                loan_amount,
                tenure_months,
                purpose,
                security_value,
                total_income,
                total_exp,
                created_by
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING
                id AS request_id,
                customer_no,
                branch_code,
                product_code,
                loan_amount,
                tenure_months,
                purpose,
                security_value,
                total_income,
                total_exp,
                req_status,
                created_on
            """,

            branchCode,
            customerNo,
            customer.get("mobile"),
            customer.get("email"),
            productCode,
            request.amount(),
            request.tenureMonths(),
            request.purpose().trim(),
            request.securityValue(),
            request.totalIncome(),
            request.householdExpense(),
            createdBy
        );

        // 7. Prepare response
        Map<String, Object> response = new LinkedHashMap<>();

        response.put("customerNo", customerNo);
        response.put("application", application);
        response.put("message",
                "Fresh loan application submitted successfully");

        return response;
    }

    // LON-10: GET EXISTING LOAN HISTORY
    public Map<String, Object> getLoanHistory(String customerNo) {

        List<Map<String, Object>> loans = jdbc.queryForList("""
            SELECT
                a.account_no AS loan_account_no,
                a.customer_no,
                a.branch_code,
                a.account_type,
                a.account_status,
                a.created_on,

                e.emi_amount AS last_emi_payment_request_amount,
                e.req_status AS last_emi_request_status,
                e.created_on AS last_emi_request_date

            FROM public."MB002003" a

            LEFT JOIN LATERAL (
                SELECT
                    p.emi_amount,
                    p.req_status,
                    p.created_on
                FROM public."MB004006" p
                WHERE p.customer_no = a.customer_no
                  AND p.loan_account_no = a.account_no
                ORDER BY p.created_on DESC
                LIMIT 1
            ) e ON TRUE

            WHERE a.customer_no = ?
              AND UPPER(a.account_type) = 'LOAN'

            ORDER BY a.created_on DESC
            """, customerNo);

        Map<String, Object> response = new LinkedHashMap<>();

        response.put("customerNo", customerNo);
        response.put("loanAccounts", loans);
        response.put("count", loans.size());
        response.put("message",
                "Existing loan accounts retrieved successfully");

        return response;
    }

    // LON-02: GET PARTICULAR LOAN ACCOUNT DETAILS
    public Map<String, Object> getLoanDetails(
            String customerNo,
            String loanId) {

        try {

            Map<String, Object> loan = jdbc.queryForMap("""
                SELECT
                    account_no AS loan_account_no,
                    customer_no,
                    branch_code,
                    account_type,
                    account_status,
                    created_on,
                    modified_on,
                    synced_on
                FROM public."MB002003"
                WHERE account_no = ?
                  AND customer_no = ?
                  AND UPPER(account_type) = 'LOAN'
                """,
                loanId,
                customerNo
            );

            Map<String, Object> response = new LinkedHashMap<>();

            response.put("loan", loan);
            response.put("message",
                    "Loan details retrieved successfully");

            return response;

        } catch (EmptyResultDataAccessException ex) {

            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Loan account not found for logged-in customer"
            );
        }
    }

    // LON-11: GET EMI PAYMENT REQUEST HISTORY
    public Map<String, Object> getEmiRequests(
            String customerNo,
            String loanAccountNo) {

        Integer accountCount = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM public."MB002003"
            WHERE account_no = ?
              AND customer_no = ?
              AND UPPER(account_type) = 'LOAN'
            """,
            Integer.class,
            loanAccountNo,
            customerNo
        );

        if (accountCount == null || accountCount == 0) {

            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Loan account not found for logged-in customer"
            );
        }

        List<Map<String, Object>> requests = jdbc.queryForList("""
            SELECT
                id AS request_id,
                loan_account_no,
                emi_amount,
                req_status,
                created_on
            FROM public."MB004006"
            WHERE customer_no = ?
              AND loan_account_no = ?
            ORDER BY created_on DESC
            """,
            customerNo,
            loanAccountNo
        );

        Map<String, Object> response = new LinkedHashMap<>();

        response.put("loanAccountNo", loanAccountNo);
        response.put("emiPaymentRequests", requests);
        response.put("count", requests.size());
        response.put("message",
                "EMI payment request history retrieved successfully");

        return response;
    }

    // EMI-01: GET LOAN DETAILS FOR PAY EMI SCREEN
    public Map<String, Object> getEmiDetails(String customerNo) {

        List<Map<String, Object>> loans = jdbc.queryForList("""
            SELECT
                a.account_no AS loan_account_no,
                a.customer_no,
                a.branch_code,
                a.account_type,
                a.account_status,
                a.created_on,

                e.emi_amount AS last_emi_request_amount,
                e.req_status AS last_emi_request_status,
                e.created_on AS last_emi_request_date

            FROM public."MB002003" a

            LEFT JOIN LATERAL (
                SELECT
                    p.emi_amount,
                    p.req_status,
                    p.created_on
                FROM public."MB004006" p
                WHERE p.customer_no = a.customer_no
                  AND p.loan_account_no = a.account_no
                ORDER BY p.created_on DESC
                LIMIT 1
            ) e ON TRUE

            WHERE a.customer_no = ?
              AND UPPER(a.account_type) = 'LOAN'
              AND UPPER(a.account_status) = 'ACTIVE'

            ORDER BY a.created_on DESC
            """, customerNo);

        Map<String, Object> response = new LinkedHashMap<>();

        response.put("customerNo", customerNo);
        response.put("loanAccounts", loans);
        response.put("count", loans.size());
        response.put("message",
                "Active loan accounts retrieved successfully");

        return response;
    }

    // EMI-02: GET SAVINGS/CURRENT DEBIT ACCOUNTS
    public Map<String, Object> getEmiDebitAccounts(String customerNo) {

        List<Map<String, Object>> accounts = jdbc.queryForList("""
            SELECT
                account_no,
                customer_no,
                branch_code,
                account_type,
                account_status

            FROM public."MB002003"

            WHERE customer_no = ?
              AND UPPER(account_type) IN ('SAVINGS', 'CURRENT')
              AND UPPER(account_status) = 'ACTIVE'

            ORDER BY account_type, account_no
            """, customerNo);

        Map<String, Object> response = new LinkedHashMap<>();

        response.put("customerNo", customerNo);
        response.put("debitAccounts", accounts);
        response.put("count", accounts.size());
        response.put("message",
                "Active debit accounts retrieved successfully");

        return response;
    }
}
