package com.abcbank.mobile_banking.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.abcbank.mobile_banking.repository.AccountRepository;

@Service
public class AccountService {
 private final AccountRepository repo; private final CustomerIdentityResolver identities; private final JdbcTemplate jdbc; private final PasswordEncoder encoder;
 public AccountService(AccountRepository repo,CustomerIdentityResolver identities,JdbcTemplate jdbc,PasswordEncoder encoder){this.repo=repo;this.identities=identities;this.jdbc=jdbc;this.encoder=encoder;}
 private String customer(String principal){return identities.resolve(principal).customerNo();}
 public List<Map<String,Object>> accounts(String principal,String status){String c=customer(principal);return repo.list(c,status).stream().map(this::maskAccount).toList();}
 public Map<String,Object> details(String principal,String accountId,boolean reveal,String mpin,String ip){String c=customer(principal);Map<String,Object> row=owned(c,accountId);Map<String,Object> out=maskAccount(row);
  if(reveal){verifyMpin(c,mpin);out.put("fullNumber",row.get("account_no")); audit(c,String.valueOf(row.get("account_id")),ip);}
  return out;}
 public Map<String,Object> balance(String principal,String accountId){String c=customer(principal);Map<String,Object> a=owned(c,accountId);String accountNo=String.valueOf(a.get("account_no"));try{return jdbc.queryForMap("""
SELECT available_balance AS "availableBalance",ledger_balance AS "ledgerBalance",currency,as_of AS "asOf"
    FROM public.mobile_account_balances WHERE customer_no=? AND account_no=?""",c,accountNo);}catch(EmptyResultDataAccessException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Balance is not available for this account yet");}}
 public Map<String,Object> transactions(String principal,String accountId,String filter,String from,String to,int page,int size){String c=customer(principal);Map<String,Object> a=owned(c,accountId);String accountNo=String.valueOf(a.get("account_no"));if(page<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"page must be zero or greater");if(size<1||size>50)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"size must be between 1 and 50");
  LocalDate fromDay; LocalDate toDay;
  try { fromDay=from==null?LocalDate.now().minusYears(1):LocalDate.parse(from); toDay=to==null?LocalDate.now():LocalDate.parse(to); }
  catch (java.time.format.DateTimeParseException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Dates must use YYYY-MM-DD format"); }
  if(toDay.isBefore(fromDay))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"toDate must not be before fromDate");
  if(fromDay.isBefore(LocalDate.now().minusYears(1)))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Date range is limited to one year");
  String effectiveFrom=fromDay.toString(); String effectiveTo=toDay.toString();
  String kind=normalizeKind(filter);long total=repo.countTransactions(c,accountNo,kind,effectiveFrom,effectiveTo);List<Map<String,Object>> items=repo.transactions(c,accountNo,kind,effectiveFrom,effectiveTo,size,page*size);Map<String,Object> out=new LinkedHashMap<>();out.put("items",items);out.put("page",page);out.put("size",size);out.put("totalItems",total);out.put("totalPages",(int)Math.ceil((double)total/size));return out;}
 public Map<String,Object> mini(String principal,String accountId){String c=customer(principal);Map<String,Object> a=owned(c,accountId);String accountNo=String.valueOf(a.get("account_no"));List<Map<String,Object>> items=repo.transactions(c,accountNo,null,null,null,10,0);return Map.of("items",items,"count",items.size());}
 private String normalizeKind(String f){if(f==null||f.isBlank()||f.equalsIgnoreCase("ALL"))return null;String v=f.trim().toUpperCase().replace(' ','_');if(v.equals("DEPOSITS"))v="DEPOSIT";if(v.equals("WITHDRAWALS"))v="WITHDRAWAL";if(v.equals("BILL_PAYMENTS"))v="BILL_PAYMENT";if(!List.of("DEPOSIT","WITHDRAWAL","BILL_PAYMENT","UPI").contains(v))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Supported filters: All, Deposits, Withdrawals, Bill Payments, UPI");return v;}
 private Map<String,Object> owned(String c,String a){try{return repo.owned(c,a);}catch(EmptyResultDataAccessException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Account not found for this customer");}}
 private Map<String,Object> maskAccount(Map<String,Object> r){Map<String,Object> m=new LinkedHashMap<>();String n=String.valueOf(r.get("account_no"));m.put("accountId",r.get("account_id")==null?null:r.get("account_id").toString());m.put("type",r.get("account_type"));m.put("maskedNumber",n.length()<=4?n:"X".repeat(n.length()-4)+n.substring(n.length()-4));m.put("branch",r.get("branch_name"));m.put("branchCode",r.get("branch_code"));m.put("ifsc",r.get("ifsc_code"));m.put("status",r.get("account_status"));return m;}
 private void verifyMpin(String c,String pin){
  if(pin==null||pin.isBlank())throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"X-MPIN header is required for account number reveal");
  try {
   Map<String,Object> row=jdbc.queryForMap("""
SELECT mpin_hash, failed_attempts FROM public.\"MB001003\"
 WHERE customer_id=? AND status='ACTIVE' AND (locked_until IS NULL OR locked_until<CURRENT_TIMESTAMP)
""",c);
   String hash=String.valueOf(row.get("mpin_hash"));
   if(!encoder.matches(pin,hash)) {
    jdbc.update("""
UPDATE public.\"MB001003\" SET failed_attempts=failed_attempts+1,
       locked_until=CASE WHEN failed_attempts+1>=5 THEN CURRENT_TIMESTAMP + INTERVAL '15 minutes' ELSE locked_until END,
       modified_on=CURRENT_TIMESTAMP WHERE customer_id=? AND status='ACTIVE'""",c);
    throw new ResponseStatusException(HttpStatus.FORBIDDEN,"MPIN verification failed");
   }
   jdbc.update("UPDATE public.\"MB001003\" SET failed_attempts=0, locked_until=NULL WHERE customer_id=? AND status='ACTIVE'",c);
  } catch(EmptyResultDataAccessException e) { throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Active MPIN is missing or locked"); }
 }
 private void audit(String c,String account,String ip){jdbc.update("""
INSERT INTO public.mobile_account_access_audit (customer_no,account_id,action,ip_address) VALUES (?, ?, 'ACCOUNT_REVEAL', ?)""",c,account,ip);}
}
