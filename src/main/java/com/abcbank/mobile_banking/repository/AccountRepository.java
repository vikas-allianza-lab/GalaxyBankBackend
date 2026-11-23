
package com.abcbank.mobile_banking.repository;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ACC-03: List customer accounts
    public List<Map<String, Object>> list(
            String customer,
            String status) {

        String sql = """
                SELECT
                    a.account_id,
                    a.account_no,
                    a.account_type,
                    a.account_status,
                    a.branch_code,
                    b.branch_name,
                    b.ifsc_code
                FROM public."MB002003" a
                LEFT JOIN public."MB002002" b
                    ON b.branch_code = a.branch_code
                WHERE a.customer_no = ?
                  AND (
                      CAST(? AS varchar) IS NULL
                      OR UPPER(a.account_status) =
                         UPPER(CAST(? AS varchar))
                  )
                ORDER BY a.created_on DESC
                """;

        return jdbc.queryForList(sql, customer, status, status);
    }

    // ACC-04: Get account details with ownership check
    public Map<String, Object> owned(
            String customer,
            String account) {

        String sql = """
                SELECT
                    a.account_id,
                    a.account_no,
                    a.account_type,
                    a.account_status,
                    a.branch_code,
                    b.branch_name,
                    b.ifsc_code
                FROM public."MB002003" a
                LEFT JOIN public."MB002002" b
                    ON b.branch_code = a.branch_code
                WHERE a.customer_no = ?
                  AND a.account_id = CAST(? AS uuid)
                """;

        return jdbc.queryForMap(sql, customer, account);
    }

    // ACC-06 / ACC-07: Account transactions
    public List<Map<String, Object>> transactions(
            String customer,
            String account,
            String kind,
            String from,
            String to,
            int limit,
            int offset) {

        String sql = """
                SELECT
                    txn_id AS "txnId",
                    title,
                    txn_at AS "date",
                    amount,
                    kind,
                    status,
                    mode,
                    balance_after AS "balanceAfter"
                FROM public.mobile_account_transactions
                WHERE customer_no = ?
                  AND account_no = ?
                  AND (
                      CAST(? AS varchar) IS NULL
                      OR UPPER(category) = UPPER(CAST(? AS varchar))
                  )
                  AND (
                      CAST(? AS varchar) IS NULL
                      OR txn_at::date >= CAST(? AS date)
                  )
                  AND (
                      CAST(? AS varchar) IS NULL
                      OR txn_at::date <= CAST(? AS date)
                  )
                ORDER BY txn_at DESC, txn_id DESC
                LIMIT ? OFFSET ?
                """;

        return jdbc.queryForList(
                sql,
                customer,
                account,
                kind, kind,
                from, from,
                to, to,
                limit,
                offset
        );
    }

    // ACC-06: Count transactions for pagination
    public long countTransactions(
            String customer,
            String account,
            String kind,
            String from,
            String to) {

        String sql = """
                SELECT COUNT(*)
                FROM public.mobile_account_transactions
                WHERE customer_no = ?
                  AND account_no = ?
                  AND (
                      CAST(? AS varchar) IS NULL
                      OR UPPER(category) = UPPER(CAST(? AS varchar))
                  )
                  AND (
                      CAST(? AS varchar) IS NULL
                      OR txn_at::date >= CAST(? AS date)
                  )
                  AND (
                      CAST(? AS varchar) IS NULL
                      OR txn_at::date <= CAST(? AS date)
                  )
                """;

        Long count = jdbc.queryForObject(
                sql,
                Long.class,
                customer,
                account,
                kind, kind,
                from, from,
                to, to
        );

        return count == null ? 0 : count;
    }
}
