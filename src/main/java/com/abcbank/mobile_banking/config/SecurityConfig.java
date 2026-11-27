
package com.abcbank.mobile_banking.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.abcbank.mobile_banking.security.JwtAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtFilter) throws Exception {

        return http
                .csrf(csrf -> csrf.disable())

                .sessionManagement(s ->
                        s.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(a -> a

                        // Public APIs
                        .requestMatchers(
                                "/api/v1/registration/start",
                                "/api/v1/registration/verify-otp",
                                "/actuator/health"
                        ).permitAll()

                        .requestMatchers(
                                "/api/v1/auth/web/login",
                                "/api/v1/auth/mpin/login",
                                "/api/v1/auth/token/refresh",
                                "/api/v1/auth/forgot-mpin/request",
                                "/api/v1/auth/forgot-mpin/verify-otp"
                        ).permitAll()

                        // Public branches and grievances
                        .requestMatchers(
                                "/api/v1/grievances/**",
                                "/api/v1/branches/**"
                        ).permitAll()

                        // Scope-based APIs
                        .requestMatchers(
                                "/api/v1/auth/forgot-mpin/reset"
                        ).hasAuthority("SCOPE_mpin:reset")

                        .requestMatchers(
                                "/api/v1/registration/complete"
                        ).hasAuthority("SCOPE_registration:complete")

                        .requestMatchers(
                                "/api/v1/transfers/**"
                        ).hasAuthority("SCOPE_fund:transfer")

                        .requestMatchers(
                                "/api/v1/requests/stop-payment"
                        ).hasAuthority("SCOPE_fund:transfer")

                        // Customer APIs
                        .requestMatchers(
                                "/api/v1/notifications/**",
                                "/api/v1/deposits/**",
                                "/api/v1/locker/**",
                                "/api/v1/beneficiaries",
                                "/api/v1/beneficiaries/**",
                                "/api/v1/positive-pay/**",
                                "/api/v1/customers/**",
                                "/api/v1/customer-360",
                                "/api/v1/accounts/**"
                        ).hasRole("CUSTOMER")



                        // PUBLIC THEME GET API - NO BEARER TOKEN
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/themes"
                        ).permitAll()

                        // Other theme endpoints remain protected
                        .requestMatchers("/api/v1/themes/**")
                        .hasRole("CUSTOMER")

                        // All other APIs require authentication
                        .anyRequest().authenticated()
                )

                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())

                .addFilterBefore(
                        jwtFilter,
                        UsernamePasswordAuthenticationFilter.class
                )

                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
