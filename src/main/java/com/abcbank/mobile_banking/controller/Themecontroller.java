package com.abcbank.mobile_banking.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/themes")
public class Themecontroller {

    private final JdbcTemplate jdbc;

    public Themecontroller(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public ResponseEntity<?> getTheme(
            @RequestParam("bank_code") String bankCode) {

        if (bankCode == null || bankCode.isBlank()) {

            return ResponseEntity.badRequest().body(
                    Map.of("message", "bank_code is required")
            );
        }

        bankCode = bankCode.trim();

        try {
            Map<String, Object> theme = jdbc.queryForObject(
                    """
                    SELECT code,
                           theme_json::text AS theme_json,
                           updated_at,
                           bank_id
                    FROM public.portal_bank_theme
                    WHERE code = ?
                    """,
                    (rs, rowNum) -> {

                        Map<String, Object> response =
                                new LinkedHashMap<>();

                        response.put("code", rs.getString("code"));

                        response.put("theme_json",
                                rs.getString("theme_json"));

                        response.put("updated_at",
                                rs.getObject("updated_at"));

                        response.put("bank_id",
                                rs.getString("bank_id"));

                        return response;
                    },
                    bankCode
            );

            return ResponseEntity.ok(theme);

        } catch (EmptyResultDataAccessException ex) {

            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of(
                            "message", "Theme not found",
                            "bank_code", bankCode
                    ));
        }
    }
}