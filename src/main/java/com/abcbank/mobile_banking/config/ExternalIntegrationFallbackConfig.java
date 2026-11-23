package com.abcbank.mobile_banking.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.abcbank.mobile_banking.integration.CardProcessorClient;
import com.abcbank.mobile_banking.integration.PaymentProviderClient;

import java.math.BigDecimal;
import java.util.Map;

/** Fail-closed defaults. A real bank/partner adapter bean overrides these defaults. */
@Configuration
public class ExternalIntegrationFallbackConfig {

    @Bean
    @ConditionalOnMissingBean(CardProcessorClient.class)
    CardProcessorClient unavailableCardProcessorClient() {
        return new CardProcessorClient() {
            private ResponseStatusException unavailable() {
                return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Card processor is not configured. No card operation was submitted.");
            }
            public void block(String ref, String type, String reason) { throw unavailable(); }
            public void unblock(String ref) { throw unavailable(); }
            public void updateLimits(String ref, Map<String, BigDecimal> limits) { throw unavailable(); }
            public void changePin(String ref, String expiry, String cvv, String pin) { throw unavailable(); }
        };
    }

    @Bean
    @ConditionalOnMissingBean(PaymentProviderClient.class)
    PaymentProviderClient unavailablePaymentProviderClient() {
        return new PaymentProviderClient() {
            private ResponseStatusException unavailable() {
                return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Payment partner is not configured. No payment was submitted or debited.");
            }
            public PaymentResult payBill(String biller, String consumer, String account,
                                         BigDecimal amount, String key) { throw unavailable(); }
            public PaymentResult recharge(String mobile, String operator, String plan,
                                          String account, String key) { throw unavailable(); }
            public PaymentResult payUpi(String vpa, BigDecimal amount, String account,
                                        String remarks, String key) { throw unavailable(); }
        };
    }
}
