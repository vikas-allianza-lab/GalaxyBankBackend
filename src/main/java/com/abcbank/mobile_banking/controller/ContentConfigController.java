
package com.abcbank.mobile_banking.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.abcbank.mobile_banking.service.ContentConfigService;

@RestController
@RequestMapping("/api/v1")
public class ContentConfigController {

    private final ContentConfigService service;

    public ContentConfigController(ContentConfigService service) {
        this.service = service;
    }

    // CNT-01: Bank information
    @GetMapping("/content/bank-info")
    public ResponseEntity<List<Map<String, Object>>> bankInfo() {
        return ResponseEntity.ok(service.getBankInfo());
    }

    // CNT-02: Banners
    @GetMapping("/content/banners")
    public ResponseEntity<List<Map<String, Object>>> banners() {
        return ResponseEntity.ok(service.getBanners());
    }

    // CNT-03: App configuration
    @GetMapping("/config/app")
    public ResponseEntity<Map<String, Object>> appConfig(
            @RequestParam(defaultValue = "ANDROID") String platform,
            @RequestParam(required = false) String currentVersion) {

        return ResponseEntity.ok(
            service.getAppConfig(platform, currentVersion)
        );
    }

    // CNT-04: Cut-off times
    @GetMapping("/config/cut-off-times")
    public ResponseEntity<List<Map<String, Object>>> cutOffTimes() {
        return ResponseEntity.ok(service.getCutOffTimes());
    }

    // CNT-05: Transaction limits
    @GetMapping("/config/transaction-limits")
    public ResponseEntity<List<Map<String, Object>>> transactionLimits() {
        return ResponseEntity.ok(service.getTransactionLimits());
    }
}
