package com.abcbank.mobile_banking.integration;

import java.math.BigDecimal;
import java.util.Map;

/** Adapter boundary for the issuer/card processor. Implement with the bank's certified processor API/HSM. */
public interface CardProcessorClient {
    void block(String processorCardRef, String blockType, String reason);
    void unblock(String processorCardRef);
    void updateLimits(String processorCardRef, Map<String, BigDecimal> limits);
    void changePin(String processorCardRef, String expiryMmYy, String cvv, String newPin);
}
