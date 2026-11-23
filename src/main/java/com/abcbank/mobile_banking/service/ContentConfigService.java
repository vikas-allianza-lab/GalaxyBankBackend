
package com.abcbank.mobile_banking.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContentConfigService {

    private final JdbcTemplate jdbc;

    public ContentConfigService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // CNT-01: Bank information from MB002001
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getBankInfo() {

        return jdbc.queryForList("""
            SELECT bank_code, bank_name, bank_type, rbi_license,
                   sponsor_bank, ifsc_code, micr_code,
                   address, add1, add2, add3,
                   city, state, pincode,
                   working_time_from, working_time_to
              FROM public."MB002001"
             ORDER BY bank_name
            """);
    }

    // CNT-02: Active banners
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getBanners() {

        return jdbc.queryForList("""
            SELECT banner_id, title, description, image_url,
                   target_url, display_order, starts_at, ends_at
              FROM public.content_banners
             WHERE status = 'ACTIVE'
               AND (starts_at IS NULL
                    OR starts_at <= CURRENT_TIMESTAMP)
               AND (ends_at IS NULL
                    OR ends_at > CURRENT_TIMESTAMP)
             ORDER BY display_order ASC, banner_id DESC
            """);
    }

    // CNT-03: App configuration and version check
    @Transactional(readOnly = true)
    public Map<String, Object> getAppConfig(
            String platform, String currentVersion) {

        String normalizedPlatform =
            platform == null
                ? "ANDROID"
                : platform.trim().toUpperCase(Locale.ROOT);

        if (!normalizedPlatform.matches("ANDROID|IOS|WEB")) {
            throw new IllegalArgumentException(
                "Platform must be ANDROID, IOS or WEB."
            );
        }

        Map<String, Object> result;

        try {
            result = jdbc.queryForMap("""
                SELECT platform, latest_version,
                       minimum_supported_version,
                       force_update, maintenance_mode,
                       maintenance_message, support_email,
                       support_mobile, privacy_policy_url, terms_url
                  FROM public.app_configuration
                 WHERE platform = ?
                   AND status = 'ACTIVE'
                """, normalizedPlatform);

        } catch (EmptyResultDataAccessException ex) {
            throw new IllegalStateException(
                "App configuration is not available for "
                + normalizedPlatform + "."
            );
        }

        result.put("currentVersion", currentVersion);

        if (currentVersion != null && !currentVersion.isBlank()) {

            String latest =
                result.get("latest_version").toString();

            String minimum =
                result.get("minimum_supported_version").toString();

            boolean belowMinimum =
                compareVersions(currentVersion, minimum) < 0;

            boolean belowLatest =
                compareVersions(currentVersion, latest) < 0;

            result.put("versionSupported", !belowMinimum);
            result.put("updateAvailable", belowLatest);

            result.put(
                "forceUpdateRequired",
                belowMinimum ||
                (Boolean.TRUE.equals(result.get("force_update"))
                    && belowLatest)
            );
        }

        return result;
    }

    private int compareVersions(String left, String right) {

        String[] a = left.split("\\.");
        String[] b = right.split("\\.");

        int length = Math.max(a.length, b.length);

        for (int i = 0; i < length; i++) {

            int av = i < a.length
                ? parseVersionPart(a[i]) : 0;

            int bv = i < b.length
                ? parseVersionPart(b[i]) : 0;

            if (av != bv) {
                return Integer.compare(av, bv);
            }
        }

        return 0;
    }

    private int parseVersionPart(String part) {

        String numeric = part.replaceFirst("[^0-9].*$", "");

        if (numeric.isEmpty()) {
            return 0;
        }

        try {
            return Integer.parseInt(numeric);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                "Invalid app version format."
            );
        }
    }

    // CNT-04: Active cut-off times
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getCutOffTimes() {

        return jdbc.queryForList("""
            SELECT transaction_type, day_of_week,
                   cutoff_time, timezone, is_working_day
              FROM public.transaction_cut_off_times
             WHERE status = 'ACTIVE'
             ORDER BY transaction_type, day_of_week
            """);
    }

    // CNT-05: Active transaction limits
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getTransactionLimits() {

        return jdbc.queryForList("""
            SELECT transaction_type, minimum_amount,
                   maximum_per_transaction, daily_amount_limit,
                   daily_transaction_count, currency
              FROM public.transaction_limits
             WHERE status = 'ACTIVE'
             ORDER BY transaction_type
            """);
    }
}
