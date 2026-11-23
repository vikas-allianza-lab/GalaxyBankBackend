package com.abcbank.mobile_banking.controller;

import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.abcbank.mobile_banking.service.AccountService;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {
 private final AccountService service; public AccountController(AccountService service){this.service=service;}
 @GetMapping public ResponseEntity<List<Map<String,Object>>> list(Authentication a,@RequestParam(required=false) String status){return ResponseEntity.ok(service.accounts(a.getName(),status));}
 @GetMapping("/{accountId}") public ResponseEntity<Map<String,Object>> details(Authentication a,@PathVariable String accountId,@RequestParam(defaultValue="false") boolean reveal,@RequestHeader(value="X-MPIN",required=false) String mpin,HttpServletRequest request){return ResponseEntity.ok(service.details(a.getName(),accountId,reveal,mpin,request.getRemoteAddr()));}
 @GetMapping("/{accountId}/balance") public ResponseEntity<Map<String,Object>> balance(Authentication a,@PathVariable String accountId){return ResponseEntity.ok(service.balance(a.getName(),accountId));}
 @GetMapping("/{accountId}/transactions") public ResponseEntity<Map<String,Object>> transactions(Authentication a,@PathVariable String accountId,@RequestParam(required=false) String filter,@RequestParam(required=false) String fromDate,@RequestParam(required=false) String toDate,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return ResponseEntity.ok(service.transactions(a.getName(),accountId,filter,fromDate,toDate,page,size));}
 @GetMapping("/{accountId}/mini-statement") public ResponseEntity<Map<String,Object>> mini(Authentication a,@PathVariable String accountId){return ResponseEntity.ok(service.mini(a.getName(),accountId));}
}
