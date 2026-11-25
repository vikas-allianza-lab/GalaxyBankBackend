
package com.abcbank.mobile_banking.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class DeviceRegisterRequest {

    @NotBlank(message = "Device ID is required")
    @Size(max = 100, message = "Device ID cannot exceed 100 characters")
    private String deviceId;

    @NotBlank(message = "Push token is required")
    @Size(max = 500, message = "Push token cannot exceed 500 characters")
    private String pushToken;

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getPushToken() {
        return pushToken;
    }

    public void setPushToken(String pushToken) {
        this.pushToken = pushToken;
    }
}
