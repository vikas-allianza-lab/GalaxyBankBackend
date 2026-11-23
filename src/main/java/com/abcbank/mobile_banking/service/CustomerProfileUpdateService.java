package com.abcbank.mobile_banking.service;

import java.util.Map;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.abcbank.mobile_banking.dto.request.CustomerProfileUpdateRequest;

@Service
public class CustomerProfileUpdateService {
    private final JdbcTemplate jdbc;
    private final CustomerIdentityResolver identities;
    private final SensitiveActionMpinVerifier mpinVerifier;

    public CustomerProfileUpdateService(JdbcTemplate jdbc, CustomerIdentityResolver identities, SensitiveActionMpinVerifier mpinVerifier) {
        this.jdbc = jdbc; this.identities = identities; this.mpinVerifier = mpinVerifier;
    }

    @Transactional
    public Map<String,Object> update(String principal, CustomerProfileUpdateRequest request) {
        var identity = identities.resolve(principal);
        mpinVerifier.verify(principal, request.mpin());
        String gender = normalizeGender(request.gender());
        String state;
        try {
            state = jdbc.queryForObject("SELECT state_name FROM public.mobile_indian_states WHERE lower(state_name)=lower(?) AND is_active=true", String.class, request.state().trim());
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select a valid state from the state master.");
        }

        Map<String,Object> old = null;
        try {
            old = jdbc.queryForMap("SELECT gender,address,city,state,pincode FROM public.mobile_customer_profile WHERE customer_no=? FOR UPDATE", identity.customerNo());
        } catch (EmptyResultDataAccessException ignored) { }

        jdbc.update("""
            INSERT INTO public.mobile_customer_profile(customer_no,gender,address,city,state,pincode,modified_by,modified_on)
            VALUES (?,?,?,?,?,?,?,now())
            ON CONFLICT(customer_no) DO UPDATE SET gender=EXCLUDED.gender,address=EXCLUDED.address,
              city=EXCLUDED.city,state=EXCLUDED.state,pincode=EXCLUDED.pincode,
              modified_by=EXCLUDED.modified_by,modified_on=now()
            """, identity.customerNo(), gender, request.address().trim(), request.city().trim(), state,
            request.pincode(), identity.userId());

        jdbc.update("""
            INSERT INTO public.mobile_customer_profile_audit(customer_no,old_values,new_values,changed_by)
            VALUES (?,
              CASE WHEN ? THEN '{}'::jsonb ELSE jsonb_build_object(
                'gender',?,'address',?,'city',?,'state',?,'pincode',?) END,
              jsonb_build_object('gender',?,'address',?,'city',?,'state',?,'pincode',?),?)
            """, identity.customerNo(), old == null,
            old == null ? null : old.get("gender"), old == null ? null : old.get("address"),
            old == null ? null : old.get("city"), old == null ? null : old.get("state"),
            old == null ? null : old.get("pincode"),
            gender, request.address().trim(), request.city().trim(), state, request.pincode(), identity.userId());

        return Map.of("status", "UPDATED", "gender", gender, "address", request.address().trim(),
                      "city", request.city().trim(), "state", state, "pincode", request.pincode());
    }

    private static String normalizeGender(String gender) {
        return switch (gender.trim().toLowerCase()) {
            case "female" -> "Female";
            case "male" -> "Male";
            case "other" -> "Other";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Gender must be Female, Male or Other.");
        };
    }

}
