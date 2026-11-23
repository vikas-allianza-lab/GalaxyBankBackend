package com.abcbank.mobile_banking.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private static final Set<String> CATEGORIES = Set.of(
            "TRANSACTIONS", "ACCOUNT_UPDATES", "SECURITY", "REGISTRATION",
            "FUND_TRANSFER", "SERVICE_REQUEST", "GRIEVANCE",
            "BANK_ANNOUNCEMENT", "SYSTEM_ALERT");

    private final JdbcTemplate jdbc;

    public NotificationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public Map<String, Object> list(
            Authentication authentication,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        String customerNo = customerNo(authentication);
        String normalizedCategory = normalizeCategory(category);
        String normalizedStatus = normalizeStatus(status);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);

        StringBuilder where = new StringBuilder(" WHERE customer_no = ?");
        List<Object> args = new ArrayList<>();
        args.add(customerNo);
        if (normalizedCategory != null) {
            where.append(" AND category = ?");
            args.add(normalizedCategory);
        }
        if ("UNREAD".equals(normalizedStatus)) where.append(" AND is_read = FALSE");
        if ("READ".equals(normalizedStatus)) where.append(" AND is_read = TRUE");

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM public.\"MB003001\"" + where,
                Long.class, args.toArray());
        Long unread = jdbc.queryForObject(
                "SELECT count(*) FROM public.\"MB003001\" WHERE customer_no = ? AND is_read = FALSE",
                Long.class, customerNo);

        args.add(safeSize);
        args.add((long) safePage * safeSize);
        List<Map<String, Object>> items = jdbc.query(
                "SELECT id, customer_no, notification, category, is_read, created_on, reference_type, reference_id " +
                "FROM public.\"MB003001\"" + where +
                " ORDER BY created_on DESC, id DESC LIMIT ? OFFSET ?",
                (rs, row) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    String notification = rs.getString("notification");
                    int separator = notification == null ? -1 : notification.indexOf(':');
                    item.put("id", rs.getLong("id"));
                    item.put("customerId", rs.getString("customer_no"));
                    item.put("title", separator > 0 ? notification.substring(0, separator).trim() : "ABC Bank");
                    item.put("message", separator > 0 ? notification.substring(separator + 1).trim() : notification);
                    item.put("category", rs.getString("category"));
                    item.put("isRead", rs.getBoolean("is_read"));
                    item.put("createdAt", rs.getTimestamp("created_on").toInstant());
                    item.put("referenceType", rs.getString("reference_type"));
                    item.put("referenceId", rs.getString("reference_id"));
                    return item;
                }, args.toArray());

        return Map.of("items", items, "total", total == null ? 0 : total,
                "unreadCount", unread == null ? 0 : unread,
                "page", safePage, "size", safeSize);
    }

    @GetMapping("/count")
    public Map<String, Object> count(Authentication authentication,
            @RequestParam(required = false) String category) {
        String customerNo = customerNo(authentication);
        String normalizedCategory = normalizeCategory(category);
        String sql = "SELECT count(*) FROM public.\"MB003001\" WHERE customer_no = ? AND is_read = FALSE";
        if (normalizedCategory != null) sql += " AND category = ?";
        Long count = normalizedCategory == null
                ? jdbc.queryForObject(sql, Long.class, customerNo)
                : jdbc.queryForObject(sql, Long.class, customerNo, normalizedCategory);
        return Map.of("unreadCount", count == null ? 0 : count);
    }

    @PutMapping("/{notificationId}/read")
    public Map<String, Object> markRead(@PathVariable long notificationId,
            Authentication authentication) {
        String customerNo = customerNo(authentication);
        int updated = jdbc.update(
                "UPDATE public.\"MB003001\" SET is_read = TRUE " +
                "WHERE id = ? AND customer_no = ? AND is_read = FALSE",
                notificationId, customerNo);
        if (updated == 0) {
            Integer exists = jdbc.queryForObject(
                    "SELECT count(*) FROM public.\"MB003001\" WHERE id = ? AND customer_no = ?",
                    Integer.class, notificationId, customerNo);
            if (exists == null || exists == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found.");
        }
        return Map.of("id", notificationId, "status", "READ");
    }

    @PutMapping("/read-all")
    public Map<String, Object> markAllRead(Authentication authentication,
            @RequestParam(required = false) String category) {
        String customerNo = customerNo(authentication);
        String normalizedCategory = normalizeCategory(category);
        int updated = normalizedCategory == null
                ? jdbc.update("UPDATE public.\"MB003001\" SET is_read = TRUE WHERE customer_no = ? AND is_read = FALSE", customerNo)
                : jdbc.update("UPDATE public.\"MB003001\" SET is_read = TRUE WHERE customer_no = ? AND is_read = FALSE AND category = ?", customerNo, normalizedCategory);
        return Map.of("status", "READ", "updated", updated);
    }

    private String customerNo(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required.");
        }
        if (authentication.getAuthorities().stream().noneMatch(a -> "ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Customer access only.");
        }
        return authentication.getName();
    }

    private String normalizeCategory(String category) {
        if (category == null || category.isBlank()) return null;
        String value = category.trim().toUpperCase(Locale.ROOT);
        if (!CATEGORIES.contains(value)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported notification category.");
        return value;
    }

    private String normalizeStatus(String status) {
        String value = status == null ? "ALL" : status.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ALL", "READ", "UNREAD").contains(value)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status must be ALL, READ or UNREAD.");
        return value;
    }
}
