package com.abcbank.mobile_banking.integration;

import java.math.BigDecimal;

/** BBPS / recharge aggregator / UPI PSP integration boundary. */
public interface PaymentProviderClient {
    PaymentResult payBill(String billerCode, String consumerNo, String accountNo,
                          BigDecimal amount, String idempotencyKey);
    PaymentResult recharge(String mobileNo, String operator, String planId, String accountNo,
                           String idempotencyKey);
    PaymentResult payUpi(String vpa, BigDecimal amount, String accountNo, String remarks,
                         String idempotencyKey);

    record PaymentResult(String partnerReference, String status, BigDecimal amount) {}
}
