package com.abcbank.mobile_banking.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/** Production SMS adapter for MSG91's approved SMS Flow template API. */
@Service
@ConditionalOnProperty(name = "app.otp.delivery-mode", havingValue = "MSG91")
public class Msg91OtpDeliveryService implements OtpDeliveryService {

    private final RestClient restClient;
    private final OtpEmailSender emailSender;

    @Value("${app.otp.msg91.auth-key}")
    private String authKey;

    @Value("${app.otp.msg91.template-id}")
    private String templateId;

    /** Must exactly match the variable name configured in the MSG91 template. */
    @Value("${app.otp.msg91.otp-variable:VAR1}")
    private String otpVariable;

    public Msg91OtpDeliveryService(RestClient.Builder builder, OtpEmailSender emailSender) {
        this.restClient = builder.baseUrl("https://control.msg91.com/api/v5/flow").build();
        this.emailSender = emailSender;
    }

    @Override
    public void sendOtp(String mobileNumber, String otp) {
        if (!StringUtils.hasText(authKey) || !StringUtils.hasText(templateId)) {
            throw new IllegalStateException("MSG91 auth key and template ID must be configured.");
        }
        if (!StringUtils.hasText(mobileNumber)) {
            throw new IllegalArgumentException("Registered mobile number is missing.");
        }

        Map<String, Object> recipient = new LinkedHashMap<>();
        recipient.put("mobiles", toIndiaInternationalFormat(mobileNumber));
        recipient.put(otpVariable, otp);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("template_id", templateId);
        payload.put("recipients", List.of(recipient));

        Map<?, ?> response = restClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("authkey", authKey)
                .body(payload)
                .retrieve()
                .body(Map.class);

        if (response == null || "error".equalsIgnoreCase(String.valueOf(response.get("type")))) {
            throw new IllegalStateException("MSG91 did not accept the OTP SMS request.");
        }
    }

    @Override
    public void sendEmailOtp(String email, String otp) {
        emailSender.send(email, otp);
    }

    private String toIndiaInternationalFormat(String mobile) {
        String digits = mobile.replaceAll("\\D", "");
        if (digits.length() == 10) return "91" + digits;
        if (digits.length() == 12 && digits.startsWith("91")) return digits;
        throw new IllegalArgumentException("Mobile number must be a valid Indian 10-digit number.");
    }
}
