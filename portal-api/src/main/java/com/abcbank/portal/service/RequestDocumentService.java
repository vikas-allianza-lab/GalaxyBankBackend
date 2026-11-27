package com.abcbank.portal.service;

import com.abcbank.portal.requests.RequestModules;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;

/**
 * Generates the verified request form (PDF) from the module's own HTML template for every request-handling module except
 * RTGS / NEFT / IMPS (see {@link PaymentDocumentService}), stores it next to the payment PDFs and emails it
 * to the customer. Files are named {PREFIX}-{id:000000}.pdf, e.g. CHQ-000012.pdf, FD-000007.pdf.
 *
 * The PDF is always kept, even when the customer has no email or SMTP is not configured.
 */
@Service
public class RequestDocumentService {
    private static final Logger LOG = Logger.getLogger(RequestDocumentService.class.getName());
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final JdbcTemplate db;
    private final ObjectProvider<JavaMailSender> mailSender;
    @Value("${portal.payment-documents.directory:./payment-documents}") private String directory;
    @Value("${portal.payment-documents.bank-name:Demo National Bank}") private String bankName;
    @Value("${spring.mail.username:}") private String mailFrom;

    public RequestDocumentService(JdbcTemplate db, ObjectProvider<JavaMailSender> mailSender) {
        this.db = db;
        this.mailSender = mailSender;
    }

    /** True for every module that has a verified-request document (all except payments, which has its own service). */
    public boolean supports(String moduleKey) { return RequestDocumentForms.SPECS.containsKey(moduleKey); }

    /** Existing, valid PDF for this request, or null. */
    public Path find(String moduleKey, long id) {
        RequestDocumentForms.Spec spec = RequestDocumentForms.SPECS.get(moduleKey);
        if (spec == null) return null;
        Path p = Paths.get(directory).toAbsolutePath().normalize().resolve(fileName(spec, id));
        try { return Files.isRegularFile(p) && Files.size(p) >= 500 ? p : null; }
        catch (IOException ex) { LOG.warning("Unable to validate existing request PDF: " + ex.getMessage()); return null; }
    }

    /** Creates the PDF and emails it to the customer (used right after a request is verified). */
    public Path generateAndEmail(String moduleKey, long id) { return generate(moduleKey, id, true); }

    /** Creates the PDF; sends the email only when {@code email} is true (re-creating a missing file must not re-send mail). */
    public Path generate(String moduleKey, long id, boolean email) {
        RequestDocumentForms.Spec spec = RequestDocumentForms.SPECS.get(moduleKey);
        RequestModules.Module module = RequestModules.find(moduleKey);
        if (spec == null || module == null) throw new IllegalArgumentException("No request document is defined for module " + moduleKey);

        Map<String, Object> row = db.queryForMap("select t.*, b.branch_name as _branch_name from " + module.qualified()
                + " t left join public.\"MB002002\" b on b.branch_code = t.branch_code where t.id = ?", id);

        String customerNo = val(row, "customer_no");
        String branch = val(row, "_branch_name");
        if (branch.isBlank()) branch = val(row, "branch_code");
        String customerEmail = "", customerName = "", customerMobile = "";
        try {
            Map<String, Object> u = db.queryForMap("select coalesce(full_name,concat_ws(' ',first_name,middle_name,last_name)) as full_name,email,mobile from public.\"MB001001\" where customer_no=? order by user_id desc limit 1", customerNo);
            customerEmail = val(u, "email"); customerName = val(u, "full_name"); customerMobile = val(u, "mobile");
        } catch (Exception ex) {
            LOG.warning("Customer lookup for " + spec.prefix() + " document id=" + id + " failed: " + ex.getMessage());
        }
        if (customerEmail.isBlank()) customerEmail = val(row, "email");   // locker / loan requests carry their own email
        if (customerMobile.isBlank()) customerMobile = val(row, "mobile");

        String today = LocalDate.now(IST).format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH));
        String txn = String.format("%s-%06d", spec.prefix(), id);
        Map<String, String> ctx = new HashMap<>();
        ctx.put("transactionDate", today); ctx.put("officeTransactionDate", today);
        ctx.put("bankName", bankName); ctx.put("branchName", branch);
        ctx.put("accountNumber", val(row, "account_no")); ctx.put("customerNumber", customerNo);
        ctx.put("customerName", customerName); ctx.put("mobileNumber", customerMobile);
        ctx.put("transactionNumber", txn);
        ctx.put("makerEmployeeNumber", staffNo(row.get("created_by")));
        ctx.put("checkerEmployeeNumber", staffNo(row.get("verified_by")));

        // Every module has its own template, e.g. templates/Cheque_Book_Request_Form.html
        String template;
        try (InputStream in = getClass().getResourceAsStream("/templates/" + spec.template())) {
            if (in == null) throw new IOException("Request form template missing: " + spec.template());
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException("Unable to read request PDF template", e); }
        String html = RequestDocumentForms.render(template, RequestDocumentForms.values(spec, row, ctx));

        Path file;
        try {
            Path dir = Paths.get(directory).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            file = dir.resolve(fileName(spec, id));
            // Render in memory first so a failed render never leaves an empty / stale PDF behind.
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            new PdfRendererBuilder().withHtmlContent(html, Paths.get(".").toAbsolutePath().toUri().toString()).toStream(buffer).run();
            byte[] pdf = buffer.toByteArray();
            if (pdf.length < 500 || pdf[0] != '%' || pdf[1] != 'P' || pdf[2] != 'D' || pdf[3] != 'F') {
                throw new IOException("PDF renderer produced an empty or invalid PDF document");
            }
            Files.write(file, pdf, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (Exception ex) { throw new IllegalStateException("Failed to create " + spec.title() + " PDF", ex); }

        if (email) sendEmail(spec, customerEmail, customerName, file, txn);
        return file;
    }

    private static String fileName(RequestDocumentForms.Spec spec, long id) { return String.format("%s-%06d.pdf", spec.prefix(), id); }

    private void sendEmail(RequestDocumentForms.Spec spec, String to, String name, Path pdf, String txn) {
        if (to == null || to.isBlank()) { LOG.warning("No customer email found; PDF created but email skipped for " + txn); return; }
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) { LOG.warning("SMTP is not configured; PDF created but email skipped for " + txn); return; }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(message, true, "UTF-8");
            h.setTo(to);
            if (mailFrom != null && !mailFrom.isBlank()) h.setFrom(mailFrom);
            h.setSubject(spec.title() + " - " + txn);
            h.setText("Dear " + (name == null || name.isBlank() ? "Customer" : name) + ",\n\nYour request (" + txn
                    + ") has been verified. Please find the " + spec.title().toLowerCase(Locale.ROOT) + " attached.\n\nRegards,\n" + bankName, false);
            h.addAttachment(pdf.getFileName().toString(), new FileSystemResource(pdf));
            sender.send(message);
        } catch (Exception ex) { LOG.warning("PDF created, but customer email failed for " + txn + ": " + ex.getMessage()); }
    }

    private String staffNo(Object userId) {
        if (userId == null) return "";
        try { return db.queryForObject("select coalesce(emp_no,user_id::text) from public.\"MB001001\" where user_id=?", String.class, userId); }
        catch (Exception e) { return Objects.toString(userId, ""); }
    }

    private static String val(Map<String, Object> m, String k) { return Objects.toString(m.get(k), ""); }
}
