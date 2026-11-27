
package com.abcbank.mobile_banking.security;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
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

            chain.doFilter(request, response);
            return;
        }

        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(request, response);
            return;
        }

        try {

            String token = header.substring(7).trim();

            Claims claims = jwtService.parse(token);

            String subject = claims.getSubject();
            String userType = claims.get("user_type", String.class);
            String scope = claims.get("scope", String.class);

            log.info("JWT DEBUG subject: {}", subject);
            log.info("JWT DEBUG user_type: {}", userType);
            log.info("JWT DEBUG scope: {}", scope);

            if (subject == null || subject.isBlank()) {
                log.warn("JWT DEBUG: Subject is missing");
                SecurityContextHolder.clearContext();

            } else if (!"CU".equals(userType)) {

                log.warn(
                        "JWT DEBUG: Not a customer token. user_type={}",
                        userType);

                SecurityContextHolder.clearContext();

            } else {

                List<SimpleGrantedAuthority> authorities =
                        new ArrayList<>();

                authorities.add(
                        new SimpleGrantedAuthority("ROLE_CUSTOMER"));

                if (scope != null) {

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
            }

        } catch (ExpiredJwtException ex) {

            SecurityContextHolder.clearContext();

            log.warn("JWT DEBUG: Token expired");

        } catch (JwtException | IllegalArgumentException ex) {

            SecurityContextHolder.clearContext();

            log.error("JWT DEBUG: Invalid JWT: {}", ex.getMessage());
        }

        chain.doFilter(request, response);
    }
}
