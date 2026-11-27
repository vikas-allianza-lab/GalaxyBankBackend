package com.abcbank.portal.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.core.io.FileSystemResource;
import jakarta.mail.internet.MimeMessage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;

@Service
public class PaymentDocumentService {
    private static final Logger LOG = Logger.getLogger(PaymentDocumentService.class.getName());
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final JdbcTemplate db;
    private final ObjectProvider<JavaMailSender> mailSender;
    @Value("${portal.payment-documents.directory:./payment-documents}") private String directory;
    @Value("${portal.payment-documents.bank-name:Demo National Bank}") private String bankName;
    @Value("${spring.mail.username:}") private String mailFrom;

    public PaymentDocumentService(JdbcTemplate db, ObjectProvider<JavaMailSender> mailSender) { this.db=db; this.mailSender=mailSender; }

    public Path generateAndEmail(long id) {
        Map<String,Object> r=db.queryForMap("select t.*, b.branch_name as _branch_name from public.\"MB004001\" t left join public.\"MB002002\" b on b.branch_code=t.branch_code where t.id=?", id);
        String customerNo=val(r,"customer_no"), account=val(r,"account_no"), branch=val(r,"_branch_name"), mode=val(r,"pay_mode");
        if (branch.isBlank()) branch = lookupBranchName(val(r,"branch_code"));
        mode = mode.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("NEFT", "RTGS", "IMPS").contains(mode)) mode = mode.isBlank() ? "" : mode;
        String email="", customerName="", mobile="";
        try {
            Map<String,Object> u=db.queryForMap("select coalesce(full_name,concat_ws(' ',first_name,middle_name,last_name)) as full_name,email,mobile from public.\"MB001001\" where customer_no=? order by user_id desc limit 1",customerNo);
            email=val(u,"email"); customerName=val(u,"full_name"); mobile=val(u,"mobile");
        } catch(Exception ex) { LOG.warning("Customer email/name lookup for payment document id="+id+" failed: "+ex.getMessage()); }
        String maker=staffNo(r.get("created_by")), checker=staffNo(r.get("verified_by"));
        String template;
        try(InputStream in=getClass().getResourceAsStream("/templates/RTGS_NEFT_IMPS_Request_Form.html")) {
            if(in==null) throw new IOException("Payment form template missing"); template=new String(in.readAllBytes(),StandardCharsets.UTF_8);
        } catch(IOException e) { throw new IllegalStateException("Unable to read payment PDF template",e); }
        String today=LocalDate.now(IST).format(DateTimeFormatter.ofPattern("dd MMM yyyy"));
        Map<String,String> values=new HashMap<>();
        values.put("transactionDate",today); values.put("bankName",bankName); values.put("branchName",branch); values.put("accountNumber",account);
        values.put("transferMode",mode); values.put("formTitle",mode.isBlank()?"Payment Request Form":mode+" Request Form"); values.put("applicationSubject",mode.isBlank()?"Application for Remitting Funds":"Application for Remitting Funds through "+mode); values.put("beneficiaryName",val(r,"ben_name")); values.put("beneficiaryAccountNumber",val(r,"ben_account"));
        values.put("beneficiaryIfsc",val(r,"ben_ifsc")); values.put("beneficiaryBankName",val(r,"ben_bank")); values.put("beneficiaryBranchName",val(r,"ben_branch"));
        values.put("beneficiaryMobile",val(r,"ben_mobile").isBlank()?beneficiaryMobile(r):val(r,"ben_mobile")); values.put("amount",val(r,"amount")); values.put("customerName",customerName); values.put("mobileNumber",mobile);
        values.put("transactionNumber",String.format("PAY-%06d",id)); values.put("officeTransactionDate",today); values.put("makerEmployeeNumber",maker); values.put("checkerEmployeeNumber",checker);
        for(var e:values.entrySet()) template=template.replace("{{"+e.getKey()+"}}",escape(e.getValue()));
        Path file;
        try {
            Path dir=Paths.get(directory).toAbsolutePath().normalize(); Files.createDirectories(dir);
            file=dir.resolve(String.format("PAY-%06d.pdf",id));
            // Render to memory first so a failed render cannot leave a zero-byte/stale PDF behind.
            ByteArrayOutputStream pdfBuffer = new ByteArrayOutputStream();
            new PdfRendererBuilder().withHtmlContent(template, Paths.get(".").toAbsolutePath().toUri().toString()).toStream(pdfBuffer).run();
            byte[] pdfBytes = pdfBuffer.toByteArray();
            if (pdfBytes.length < 500 || pdfBytes[0] != '%' || pdfBytes[1] != 'P' || pdfBytes[2] != 'D' || pdfBytes[3] != 'F') {
                throw new IOException("PDF renderer produced an empty or invalid PDF document");
            }
            Files.write(file, pdfBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch(Exception ex) { throw new IllegalStateException("Failed to create payment confirmation PDF",ex); }
        sendEmail(email,customerName,file,id);
        return file;
    }
    public Path find(long id) {
        Path p=Paths.get(directory).toAbsolutePath().normalize().resolve(String.format("PAY-%06d.pdf",id));
        try { return Files.isRegularFile(p) && Files.size(p) >= 500 ? p : null; }
        catch (IOException ex) { LOG.warning("Unable to validate existing payment PDF: " + ex.getMessage()); return null; }
    }

    private String lookupBranchName(String branchCode) {
        if (branchCode == null || branchCode.isBlank()) return "";
        try { return db.queryForObject("select branch_name from public.\"MB002002\" where branch_code = ?", String.class, branchCode); }
        catch (Exception e) { LOG.warning("Branch name lookup failed for code=" + branchCode + ": " + e.getMessage()); return branchCode; }
    }
    private String beneficiaryMobile(Map<String,Object> row) {
        // Discover optional beneficiary-phone columns without assuming a fixed table schema.
        for (String column : List.of("ben_mobile", "ben_phone", "beneficiary_mobile", "beneficiary_phone", "ben_contact")) {
            try {
                Boolean exists = db.queryForObject("select exists(select 1 from information_schema.columns where table_schema='public' and table_name='MB004001' and column_name=?)", Boolean.class, column);
                if (Boolean.TRUE.equals(exists)) {
                    Object value = db.queryForObject("select \"" + column + "\" from public.\"MB004001\" where id = ?", Object.class, row.get("id"));
                    if (value != null && !value.toString().isBlank()) return value.toString();
                }
            } catch (Exception ignored) { /* optional column */ }
        }
        return "";
    }
    private void sendEmail(String email,String name,Path pdf,long id) {
        if(email==null||email.isBlank()) { LOG.warning("No customer email found; PDF created but email skipped for payment id="+id); return; }
        JavaMailSender sender=mailSender.getIfAvailable(); if(sender==null) { LOG.warning("SMTP is not configured; PDF created but email skipped for payment id="+id); return; }
        try { MimeMessage message=sender.createMimeMessage(); MimeMessageHelper h=new MimeMessageHelper(message,true,"UTF-8");
            h.setTo(email); if(mailFrom!=null&&!mailFrom.isBlank()) h.setFrom(mailFrom); h.setSubject("RTGS / NEFT / IMPS Payment Request Confirmation - PAY-"+String.format("%06d",id));
            h.setText("Dear "+(name==null||name.isBlank()?"Customer":name)+",\n\nYour payment request has been verified. Please find the payment request form attached.\n\nRegards,\n"+bankName,false);
            h.addAttachment(pdf.getFileName().toString(),new FileSystemResource(pdf)); sender.send(message);
        } catch(Exception ex) { LOG.warning("PDF created, but customer email failed for payment id="+id+": "+ex.getMessage()); }
    }
    private String staffNo(Object userId){ if(userId==null)return ""; try {return db.queryForObject("select coalesce(emp_no,user_id::text) from public.\"MB001001\" where user_id=?",String.class,userId);}catch(Exception e){return Objects.toString(userId,"");} }
    private static String val(Map<String,Object> m,String k){return Objects.toString(m.get(k),"");}
    private static String escape(String s){return Objects.toString(s,"").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
}
