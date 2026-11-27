package com.abcbank.portal.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Binds every request-handling module (except RTGS / NEFT / IMPS, see {@link PaymentDocumentService})
 * to its own HTML template under {@code src/main/resources/templates/} and says which table column
 * fills which {{placeholder}} of that template.
 *
 * Wording, layout and labels live in the template file; this class only maps data.
 * Free of Spring / database code so that it is easy to test.
 *
 * Placeholders available in every template:
 * transactionDate, bankName, branchName, accountNumber, customerNumber, customerName, mobileNumber,
 * transactionNumber, officeTransactionDate, makerEmployeeNumber, checkerEmployeeNumber.
 */
final class RequestDocumentForms {

    enum Kind { TEXT, MONEY, PERCENT, DATE, BOOL }

    /** {{key}} in the template is filled from {@code column} of the request table. */
    record Item(String key, String column, Kind kind) {}

    /** @param prefix document / transaction prefix (CHQ gives CHQ-000012.pdf); @param title used as the email subject */
    record Spec(String key, String prefix, String template, String title, List<Item> items) {}

    private static Item t(String key, String col) { return new Item(key, col, Kind.TEXT); }
    private static Item m(String key, String col) { return new Item(key, col, Kind.MONEY); }
    private static Item p(String key, String col) { return new Item(key, col, Kind.PERCENT); }
    private static Item d(String key, String col) { return new Item(key, col, Kind.DATE); }
    private static Item b(String key, String col) { return new Item(key, col, Kind.BOOL); }

    static final Map<String, Spec> SPECS;
    static {
        Map<String, Spec> s = new LinkedHashMap<>();
        s.put("cheque-book", new Spec("cheque-book", "CHQ", "Cheque_Book_Request_Form.html", "Cheque Book Request Form", List.of(
                t("numberOfLeaves", "noof_leaves"), t("chequeBookType", "cheque_book_type"),
                t("deliveryMethod", "delivery"), t("deliveryAddress", "delivery_address"))));
        s.put("stop-payment", new Spec("stop-payment", "STP", "Stop_Payment_Request_Form.html", "Stop Payment Request Form", List.of(
                t("chequeNumber", "cheque_no"), d("chequeDate", "cheque_date"), t("issuedTo", "issued_to"),
                m("chequeAmount", "cheque_amount"), t("reason", "reason"))));

        List<Item> deposit = List.of(
                t("productCode", "prod_code"), t("productName", "prod_name"),
                t("schemeCode", "scheme_code"), t("schemeName", "scheme_name"),
                d("certificateDate", "cert_date"), d("asOfDate", "asof_date"),
                m("depositAmount", "deposit_amount"), p("interestRate", "int_rate"), p("offsetRate", "offset_rate"),
                t("tenure", "tenure"),
                t("interestCreditAccount", "cr_int_acid"), t("maturityCreditAccount", "maturity_acid"),
                d("maturityDate", "maturity_date"), m("maturityValue", "maturity_value"));
        List<Item> fd = new ArrayList<>(deposit);
        fd.add(b("autoRenewal", "auto_renewal"));      // FD only: RD has no auto-renewal
        fd.add(b("renewWithInterest", "renew_int"));
        s.put("fd-opening", new Spec("fd-opening", "FD", "FD_Opening_Request_Form.html", "Fixed Deposit Opening Request Form", List.copyOf(fd)));
        s.put("rd-opening", new Spec("rd-opening", "RD", "RD_Opening_Request_Form.html", "Recurring Deposit Opening Request Form", deposit));

        s.put("loan-emi-payment", new Spec("loan-emi-payment", "EMI", "Loan_EMI_Payment_Request_Form.html", "Loan EMI Payment Request Form", List.of(
                t("loanAccountNumber", "loan_account_no"), t("loanAccountName", "loan_ac_name"), t("loanAccountStatus", "loan_ac_status"),
                m("emiAmount", "emi_amount"), m("instalmentAmount", "instal_amount"),
                m("totalOutstanding", "total_outstanding"), m("interestOutstanding", "int_outstanding"), d("expiryDate", "expiry_date"))));
        s.put("closing-accounts", new Spec("closing-accounts", "FDC", "FD_RD_Closure_Request_Form.html", "FD / RD Closure Request Form", List.of(
                t("depositAccountNumber", "fd_acid"), d("certificateDate", "cert_date"), d("asOfDate", "asof_date"),
                m("depositAmount", "deposit_amount"), p("interestRate", "int_rate"), p("offsetRate", "offset_rate"),
                t("tenure", "tenure"), d("maturityDate", "maturity_date"), m("maturityValue", "maturity_value"), t("remarks", "remarks"))));
        s.put("standing-instructions", new Spec("standing-instructions", "SI", "Standing_Instruction_Request_Form.html", "Standing Instruction Request Form", List.of(
                m("debitAmount", "debit_amount"), t("frequency", "frequency"), t("tenure", "tenure"), d("expiryDate", "expiry_date"),
                t("creditAccountNumber", "cr_acid"), t("creditAccountName", "cr_ac_name"), t("creditAccountStatus", "cr_ac_status"))));
        s.put("locker-application", new Spec("locker-application", "LKR", "Locker_Application_Form.html", "Locker Application Form", List.of(
                t("lockerMobile", "mobile"), t("lockerEmail", "email"), t("lockerType", "locker_type"),
                t("operationMode", "operation_mode"), t("operationYears", "operation_yrs"), t("nomineeName", "nominee_name"))));
        s.put("fresh-loan-application", new Spec("fresh-loan-application", "LN", "Loan_Application_Form.html", "Loan Application Form", List.of(
                t("loanProduct", "product_code"), m("loanAmount", "loan_amount"), m("totalIncome", "total_income"),
                m("totalExpense", "total_exp"), t("eligibility", "eligibility"), t("applicantMobile", "mobile"), t("applicantEmail", "email"))));
        SPECS = Collections.unmodifiableMap(s);
    }

    /** Placeholders every template may use, in addition to the per-module items. */
    static final Set<String> COMMON_KEYS = Set.of("transactionDate", "bankName", "branchName", "accountNumber", "customerNumber",
            "customerName", "mobileNumber", "transactionNumber", "officeTransactionDate", "makerEmployeeNumber", "checkerEmployeeNumber");

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final Pattern TOKEN = Pattern.compile("\\{\\{(\\w+)}}");

    // ------------------------------------------------------------------ formatting

    static String format(Object v, Kind kind) {
        if (v == null) return "";
        try {
            switch (kind) {
                case MONEY:
                    return "Rs. " + indianMoney(new BigDecimal(v.toString()));
                case PERCENT: {
                    BigDecimal bd = new BigDecimal(v.toString()).stripTrailingZeros();
                    if (bd.scale() < 0) bd = bd.setScale(0);
                    return bd.toPlainString() + " %";
                }
                case DATE:
                    if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate().format(DATE_FMT);
                    if (v instanceof java.sql.Date sd) return sd.toLocalDate().format(DATE_FMT);
                    if (v instanceof LocalDate ld) return ld.format(DATE_FMT);
                    if (v instanceof OffsetDateTime od) return od.toLocalDate().format(DATE_FMT);
                    return LocalDate.parse(v.toString().substring(0, 10)).format(DATE_FMT);
                case BOOL: {
                    if (v instanceof Boolean bo) return bo ? "Yes" : "No";
                    if (v instanceof Number n) return n.intValue() != 0 ? "Yes" : "No";
                    String x = v.toString().trim().toLowerCase(Locale.ROOT);
                    return (x.equals("true") || x.equals("t") || x.equals("y") || x.equals("yes") || x.equals("1")) ? "Yes" : "No";
                }
                default:
                    return v.toString();
            }
        } catch (RuntimeException e) {
            return v.toString(); // never fail a document because of one odd value
        }
    }

    /** 1250000.5 becomes "12,50,000.50" (Indian digit grouping, two decimals). */
    static String indianMoney(BigDecimal amount) {
        BigDecimal bd = amount.setScale(2, RoundingMode.HALF_UP);
        String[] parts = bd.abs().toPlainString().split("\\.");
        String whole = parts[0];
        if (whole.length() > 3) {
            String head = whole.substring(0, whole.length() - 3), tail = whole.substring(whole.length() - 3);
            StringBuilder g = new StringBuilder();
            for (int i = 0; i < head.length(); i++) {
                if (i > 0 && (head.length() - i) % 2 == 0) g.append(',');
                g.append(head.charAt(i));
            }
            whole = g + "," + tail;
        }
        return (bd.signum() < 0 ? "-" : "") + whole + "." + parts[1];
    }

    static String escape(String s) {
        return Objects.toString(s, "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    // ------------------------------------------------------------------ rendering

    /** All {{placeholder}} values for one request: the common ones from {@code ctx} plus the module's own items from {@code row}. */
    static Map<String, String> values(Spec spec, Map<String, Object> row, Map<String, String> ctx) {
        Map<String, String> v = new HashMap<>(ctx);
        for (Item it : spec.items()) v.put(it.key(), format(row.get(it.column()), it.kind()));
        return v;
    }

    /** Fills a template. Values are HTML-escaped; each {{token}} is replaced once, so a value can never inject another token. */
    static String render(String template, Map<String, String> values) {
        Matcher mt = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder();
        while (mt.find()) mt.appendReplacement(out, Matcher.quoteReplacement(escape(values.getOrDefault(mt.group(1), ""))));
        mt.appendTail(out);
        return out.toString();
    }

    static Set<String> tokensIn(String template) {
        Set<String> found = new TreeSet<>();
        Matcher mt = TOKEN.matcher(template);
        while (mt.find()) found.add(mt.group(1));
        return found;
    }

    private RequestDocumentForms() {}
}
