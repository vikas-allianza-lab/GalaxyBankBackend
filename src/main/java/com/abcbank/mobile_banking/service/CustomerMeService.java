package com.abcbank.mobile_banking.service;

import java.util.Map;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.abcbank.mobile_banking.dto.response.CustomerMeResponse;

@Service
public class CustomerMeService {
    private final JdbcTemplate jdbc;
    private final CustomerIdentityResolver identityResolver;
    public CustomerMeService(JdbcTemplate jdbc, CustomerIdentityResolver identityResolver) { this.jdbc=jdbc; this.identityResolver=identityResolver; }

    @Transactional(readOnly=true)
    public CustomerMeResponse getMyDetails(String principal) {
        String customerNo=identityResolver.resolve(principal).customerNo();
        try {
            Map<String,Object> row=jdbc.queryForMap("""
                SELECT u.customer_no, COALESCE(NULLIF(u.disp_name,''), u.full_name) AS customer_name,
                       COALESCE(NULLIF(cr.registered_mobile,''), u.mobile) AS mobile, u.email,
                       u.branch, u.login_time,
                       (SELECT kp.kyc_status FROM public.\"MB004016\" kp
                         WHERE kp.customer_no=u.customer_no ORDER BY kp.modified_on DESC NULLS LAST, kp.created_on DESC LIMIT 1) AS kyc_status
                  FROM public.\"MB001001\" u
                  LEFT JOIN public.mb001001cr cr ON cr.customer_id=u.customer_no
                 WHERE u.customer_no=? AND u.user_type='CU' AND u.status='ACTIVE'
                """, customerNo);
            return new CustomerMeResponse(str(row.get("customer_no")), str(row.get("customer_name")),
                    maskMobile(str(row.get("mobile"))), maskEmail(str(row.get("email"))),
                    row.get("kyc_status")==null?"NOT_STARTED":str(row.get("kyc_status")),
                    str(row.get("branch")), row.get("login_time")==null?null:str(row.get("login_time")));
        } catch (EmptyResultDataAccessException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Active customer not found"); }
    }
    private String str(Object v){return v==null?null:v.toString();}
    private String maskMobile(String v){if(v==null||v.length()<4)return v;return "X".repeat(Math.max(0,v.length()-4))+v.substring(v.length()-4);}
    private String maskEmail(String v){if(v==null||!v.contains("@"))return v;String[] p=v.split("@",2);String local=p[0];return (local.length()<=2?local.charAt(0)+"***":local.substring(0,2)+"***")+"@"+p[1];}
}
