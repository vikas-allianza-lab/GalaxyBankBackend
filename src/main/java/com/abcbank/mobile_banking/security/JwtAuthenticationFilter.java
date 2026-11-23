
package com.abcbank.mobile_banking.security;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log =
            LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwtService;
    private final JdbcTemplate jdbc;

    public JwtAuthenticationFilter(
            JwtService jwtService,
            JdbcTemplate jdbc) {
        this.jwtService = jwtService;
        this.jdbc = jdbc;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        log.info("JWT DEBUG: Request URL: {}", request.getRequestURI());

        if (header == null || !header.startsWith("Bearer ")) {
            log.warn("JWT DEBUG: Bearer token missing");
        }

        if (header != null
                && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {

            try {
                String token = header.substring(7).trim();

                Claims claims = jwtService.parse(token);

                String subject = claims.getSubject();
                String userType = claims.get("user_type", String.class);
                String scope = claims.get("scope", String.class);

                log.info("JWT DEBUG subject: {}", subject);
                log.info("JWT DEBUG user_type: {}", userType);
                log.info("JWT DEBUG scope: {}", scope);

                if (subject != null
                        && !subject.isBlank()
                        && "CU".equals(userType)) {

                    List<SimpleGrantedAuthority> authorities =
                            new ArrayList<>();

                    // Customer role
                    authorities.add(
                            new SimpleGrantedAuthority("ROLE_CUSTOMER"));

                    // Token scope authorities
                    if ("registration:complete".equals(scope)) {

                        authorities.add(
                                new SimpleGrantedAuthority(
                                        "SCOPE_registration:complete"));

                    } else if ("mpin:reset".equals(scope)) {

                        authorities.add(
                                new SimpleGrantedAuthority(
                                        "SCOPE_mpin:reset"));

                    } else if ("fund:transfer".equals(scope)
                            || "access".equals(scope)) {

                        authorities.add(
                                new SimpleGrantedAuthority(
                                        "SCOPE_fund:transfer"));
                    }

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    subject,
                                    null,
                                    authorities);

                    SecurityContextHolder.getContext()
                            .setAuthentication(authentication);

                    log.info("JWT DEBUG: Customer authentication accepted");
                    log.info("JWT DEBUG: Authorities: {}", authorities);

                } else {

                    log.warn(
                            "JWT DEBUG: Authentication rejected. subject={}, userType={}",
                            subject,
                            userType);
                }

            } catch (JwtException | IllegalArgumentException ex) {
                SecurityContextHolder.clearContext();
            }
        }

        chain.doFilter(request, response);
    }
}
