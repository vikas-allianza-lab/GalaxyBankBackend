
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
public class CustomerMeService {

    // Temporary hardcoded balance for development/testing.
    private static final BigDecimal DEFAULT_ACCOUNT_BALANCE =
            new BigDecimal("125000.00");

    private final JdbcTemplate jdbc;
    private final CustomerIdentityResolver identityResolver;

    public CustomerMeService(
            JdbcTemplate jdbc,
            CustomerIdentityResolver identityResolver) {
        this.jdbc = jdbc;
        this.identityResolver = identityResolver;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getMyDetails(String principal) {

        String customerNo = identityResolver.resolve(principal).customerNo();

        Map<String, Object> response = new LinkedHashMap<>();

        // --------------------------------------------------
        // 1. CUSTOMER MASTER AND REGISTERED MOBILE
        // --------------------------------------------------

        Map<String, Object> customer;

        try {
            customer = jdbc.queryForMap("""
                SELECT u.user_id AS "userId",
                       u.customer_no AS "customerNo",
                       u.title,
                       u.first_name AS "firstName",
                       u.middle_name AS "middleName",
                       u.last_name AS "lastName",
                       u.full_name AS "fullName",
                       u.disp_name AS "displayName",
                       u.dob AS "dateOfBirth",
                       u.email,
                       COALESCE(NULLIF(cr.registered_mobile, ''), u.mobile) AS mobile,
                       u.user_name AS "username",
                       u.user_type AS "userType",
                       u.user_role AS "userRole",
                       u.sys_role AS "systemRole",
                       u.status,
                       u.branch AS "homeBranch",
                       u.multi_branch AS "multiBranch",
                       u.idle_timeout AS "idleTimeout",
                       u.login_time AS "lastLoginAt",
                       u.logout_time AS "lastLogoutAt",
                       u.inop_date AS "inoperativeDate",
                       u.created_on AS "createdAt",
                       u.modified_on AS "modifiedAt",
                       cr.eligible_for_mobile_banking AS "eligibleForMobileBanking"
                  FROM public."MB001001" u
                  LEFT JOIN public.mb001001cr cr
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

        response.put("personalInformation", section(
                "title", customer.get("title"),
                "firstName", customer.get("firstName"),
                "middleName", customer.get("middleName"),
                "lastName", customer.get("lastName"),
                "fullName", customer.get("fullName"),
                "displayName", customer.get("displayName"),
                "dateOfBirth", customer.get("dateOfBirth")
        ));

        response.put("contactInformation", section(
                "registeredMobile", customer.get("mobile"),
                "email", customer.get("email")
        ));

        response.put("customerInformation", section(
                "userId", customer.get("userId"),
                "customerNo", customer.get("customerNo"),
                "username", customer.get("username"),
                "userType", customer.get("userType"),
                "userRole", customer.get("userRole"),
                "systemRole", customer.get("systemRole"),
                "status", customer.get("status"),
                "eligibleForMobileBanking",
                customer.get("eligibleForMobileBanking"),
                "multiBranch", customer.get("multiBranch"),
                "idleTimeout", customer.get("idleTimeout")
        ));

        response.put("loginInformation", section(
                "lastLoginAt", customer.get("lastLoginAt"),
                "lastLogoutAt", customer.get("lastLogoutAt"),
                "inoperativeDate", customer.get("inoperativeDate"),
                "createdAt", customer.get("createdAt"),
                "modifiedAt", customer.get("modifiedAt")
        ));

        // --------------------------------------------------
        // 2. HOME BRANCH
        // --------------------------------------------------

        List<Map<String, Object>> homeBranches = jdbc.queryForList("""
            SELECT b.branch_code AS "branchCode",
                   b.branch_name AS "branchName",
                   b.ifsc_code AS "ifscCode",
                   b.micr_code AS "micrCode",
                   b.br_add AS address,
                   b.br_add1 AS "addressLine1",
                   b.br_add2 AS "addressLine2",
                   b.br_add3 AS "addressLine3",
                   b.br_city AS city,
                   b.br_state AS state,
                   b.br_pincode AS pincode
              FROM public."MB002002" b
             WHERE b.branch_code = ?
            """, customer.get("homeBranch"));

        response.put(
                "homeBranch",
                homeBranches.isEmpty() ? null : homeBranches.get(0)
        );

        // --------------------------------------------------
        // 3. CUSTOMER ACCOUNTS
        // --------------------------------------------------

        List<Map<String, Object>> accounts = jdbc.queryForList("""
            SELECT a.account_no AS "accountNumber",
                   a.account_type AS "accountType",
                   a.account_status AS "accountStatus",
                   a.branch_code AS "branchCode",
                   b.branch_name AS "branchName",
                   b.ifsc_code AS "ifscCode",
                   COALESCE(
                       bal.available_balance,
                       CAST(125000.00 AS NUMERIC)
                   ) AS "availableBalance",
                   COALESCE(
                       bal.ledger_balance,
                       CAST(125000.00 AS NUMERIC)
                   ) AS "ledgerBalance",
                   COALESCE(bal.currency, 'INR') AS currency,
                   bal.as_of AS "balanceAsOf",
                   bal.synced_at AS "balanceSyncedAt",
                   a.created_on AS "openedOrCreatedAt",
                   a.modified_on AS "modifiedAt",
                   a.synced_on AS "coreBankingSyncedAt"
              FROM public."MB002003" a
              LEFT JOIN public."MB002002" b
                     ON b.branch_code = a.branch_code
              LEFT JOIN public.mobile_account_balances bal
                     ON bal.account_no = a.account_no
                    AND bal.customer_no = a.customer_no
             WHERE a.customer_no = ?
             ORDER BY a.created_on DESC
            """, customerNo);

        // Actual account numbers are returned.
        // Do not remove or mask accountNumber.
        response.put("accounts", accounts);

        response.put("accountSummary", section(
                "totalAccounts", accounts.size(),
                "accountsWithBalance",
                accounts.size()
        ));

        // --------------------------------------------------
        // 4. KYC VERIFICATION
        // --------------------------------------------------

        List<Map<String, Object>> kycRecords = jdbc.queryForList("""
            SELECT k.account_no AS "accountNumber",
                   k.current_step AS "currentStep",
                   k.upload_documents AS "documentsUploaded",
                   k.verify_details AS "detailsVerified",
                   k.review_completed AS "reviewCompleted",
                   k.final_approved AS "finalApproved",
                   k.kyc_status AS "kycStatus",
                   k.rejection_reason AS "rejectionReason",
                   k.submitted_on AS "submittedAt",
                   k.verified_on AS "verifiedAt",
                   k.created_on AS "createdAt",
                   k.modified_on AS "modifiedAt"
              FROM public."MB004016" k
             WHERE k.customer_no = ?
             ORDER BY k.modified_on DESC NULLS LAST,
                      k.created_on DESC
            """, customerNo);

        response.put("kyc", kycRecords);

        response.put(
                "kycStatus",
                kycRecords.isEmpty()
                        ? "NOT_STARTED"
                        : kycRecords.get(0).get("kycStatus")
        );

        // --------------------------------------------------
        // 5. KYC DOCUMENTS
        // --------------------------------------------------

        List<Map<String, Object>> kycDocuments = jdbc.queryForList("""
            SELECT d.account_no AS "accountNumber",
                   d.document_type AS "documentType",
                   d.document_name AS "documentName",
                   d.original_file_name AS "originalFileName",
                   d.file_extension AS "fileExtension",
                   d.file_size AS "fileSizeBytes",
                   d.upload_status AS "uploadStatus",
                   d.created_on AS "uploadedAt",
                   d.modified_on AS "modifiedAt"
              FROM public."MB004015" d
             WHERE d.customer_no = ?
             ORDER BY d.created_on DESC
            """, customerNo);

        response.put("kycDocuments", kycDocuments);

        // --------------------------------------------------
        // 6. NOMINEES
        // --------------------------------------------------

        List<Map<String, Object>> nominees = jdbc.queryForList("""
            SELECT n.account_no AS "accountNumber",
                   n.nominee_name AS "nomineeName",
                   n.relationship,
                   n.date_of_birth AS "dateOfBirth",
                   n.mobile_no AS "mobileNumber",
                   n.address,
                   n.allocation_percentage AS "allocationPercentage",
                   n.is_minor AS "isMinor",
                   n.guardian_name AS "guardianName",
                   n.req_status AS "requestStatus",
                   n.created_on AS "createdAt",
                   n.modified_on AS "modifiedAt"
              FROM public."MB004014" n
             WHERE n.customer_no = ?
             ORDER BY n.created_on DESC
            """, customerNo);

        response.put("nominees", nominees);

        // --------------------------------------------------
        // 7. FIXED DEPOSITS
        // --------------------------------------------------

        List<Map<String, Object>> fixedDeposits = jdbc.queryForList("""
            SELECT id,
                   account_no AS "linkedDebitAccountNumber",
                   prod_code AS "productCode",
                   prod_name AS "productName",
                   scheme_code AS "schemeCode",
                   scheme_name AS "schemeName",
                   asof_date AS "depositDate",
                   deposit_amount AS "principalAmount",
                   int_rate AS "interestRate",
                   tenure AS "tenureMonths",
                   auto_renewal AS "autoRenewal",
                   maturity_date AS "maturityDate",
                   maturity_value AS "maturityValue",
                   req_status AS "requestStatus",
                   created_on AS "createdAt"
              FROM public."MB004004"
             WHERE customer_no = ?
               AND req_status IN ('APPROVED', 'COMPLETED')
             ORDER BY created_on DESC
            """, customerNo);

        // --------------------------------------------------
        // 8. RECURRING DEPOSITS
        // --------------------------------------------------

        List<Map<String, Object>> recurringDeposits = jdbc.queryForList("""
            SELECT id,
                   account_no AS "linkedDebitAccountNumber",
                   prod_code AS "productCode",
                   prod_name AS "productName",
                   scheme_code AS "schemeCode",
                   scheme_name AS "schemeName",
                   asof_date AS "depositDate",
                   deposit_amount AS "monthlyInstallment",
                   int_rate AS "interestRate",
                   tenure AS "tenureMonths",
                   auto_renewal AS "autoRenewal",
                   maturity_date AS "maturityDate",
                   maturity_value AS "maturityValue",
                   req_status AS "requestStatus",
                   created_on AS "createdAt"
              FROM public."MB004005"
             WHERE customer_no = ?
               AND req_status IN ('APPROVED', 'COMPLETED')
             ORDER BY created_on DESC
            """, customerNo);

        // Account numbers remain unmasked.
        response.put("fixedDeposits", fixedDeposits);
        response.put("recurringDeposits", recurringDeposits);

        response.put("depositSummary", section(
                "fixedDepositCount", fixedDeposits.size(),
                "recurringDepositCount", recurringDeposits.size(),
                "totalDepositRequests",
                fixedDeposits.size() + recurringDeposits.size(),
                "actualDepositBalance", null,
                "balanceNote",
                "Actual FD/RD balances are not stored in the available deposit request tables."
        ));

        // --------------------------------------------------
        // 9. LOANS
        // --------------------------------------------------

        List<Map<String, Object>> loans = jdbc.queryForList("""
            SELECT DISTINCT ON (loan_account_no)
                   loan_account_no AS "loanAccountNumber",
                   loan_ac_status AS "loanAccountStatus",
                   loan_ac_name AS "loanAccountName",
                   emi_amount AS "emiAmount",
                   total_outstanding AS "totalOutstanding",
                   int_outstanding AS "interestOutstanding",
                   instal_amount AS "installmentAmount",
                   expiry_date AS "expiryDate",
                   req_status AS "requestStatus",
                   created_on AS "snapshotCreatedAt"
              FROM public."MB004006"
             WHERE customer_no = ?
               AND req_status IN ('APPROVED', 'COMPLETED')
             ORDER BY loan_account_no,
                      created_on DESC,
                      id DESC
            """, customerNo);

        BigDecimal totalOutstanding = BigDecimal.ZERO;
        boolean hasOutstanding = false;

        // Do not mask loan account numbers.
        for (Map<String, Object> loan : loans) {

            Object outstanding = loan.get("totalOutstanding");

            if (outstanding != null) {
                totalOutstanding = totalOutstanding.add(
                        new BigDecimal(outstanding.toString())
                );

                hasOutstanding = true;
            }
        }

        response.put("loans", loans);

        response.put("loanSummary", section(
                "loanCount", loans.size(),
                "totalOutstanding",
                hasOutstanding ? totalOutstanding : null,
                "overdueAmount", null,
                "overdueNote",
                "Overdue amount is not available in the existing loan snapshot table."
        ));

        // --------------------------------------------------
        // RESPONSE MESSAGE
        // --------------------------------------------------

        response.put(
                "message",
                "Complete logged-in customer details fetched successfully"
        );

        return response;
    }

    // --------------------------------------------------
    // COMMON RESPONSE SECTION
    // --------------------------------------------------

    private Map<String, Object> section(Object... keyValues) {

        Map<String, Object> result = new LinkedHashMap<>();

        for (int i = 0; i < keyValues.length; i += 2) {
            result.put(
                    String.valueOf(keyValues[i]),
                    keyValues[i + 1]
            );
        }

        return result;
    }
}
