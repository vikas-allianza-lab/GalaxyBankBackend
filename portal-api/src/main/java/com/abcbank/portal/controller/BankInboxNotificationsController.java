package com.abcbank.portal.controller;

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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.abcbank.portal.requests.ApiException;
import com.abcbank.portal.requests.Caller;

/** Per-user inbox for bank employees. Every recipient has an independent read flag. */
@RestController
@RequestMapping("/mobile-utilities/inbox-notifications")
public class BankInboxNotificationsController {

    private static final Set<String> CATEGORIES = Set.of(
            "TRANSACTIONS", "ACCOUNT_UPDATES", "SECURITY", "REGISTRATION",
            "FUND_TRANSFER", "SERVICE_REQUEST", "GRIEVANCE",
            "BANK_ANNOUNCEMENT", "SYSTEM_ALERT");

    private final JdbcTemplate db;

    public BankInboxNotificationsController(JdbcTemplate db) {
        this.db = db;
    }

    @GetMapping
    public Map<String, Object> list(Authentication auth,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        Caller who = Caller.of(auth);
        String cat = category(category);
        String state = status(status);
        int pg = Math.max(page, 1);
        int size = Math.min(Math.max(pageSize, 1), 100);

        StringBuilder where = new StringBuilder(" WHERE recipient_user_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(who.id());
        if (cat != null) { where.append(" AND category = ?"); args.add(cat); }
        if ("UNREAD".equals(state)) where.append(" AND is_read = FALSE");
        if ("READ".equals(state)) where.append(" AND is_read = TRUE");

        Long total = db.queryForObject("SELECT count(*) FROM public.\"MB003005\"" + where,
                Long.class, args.toArray());
        Long unread = db.queryForObject(
                "SELECT count(*) FROM public.\"MB003005\" WHERE recipient_user_id = ? AND is_read = FALSE",
                Long.class, who.id());
        args.add(size);
        args.add((long) (pg - 1) * size);

        List<Map<String, Object>> items = db.query(
                "SELECT id, category, title, message, branch_code, reference_type, reference_id, is_read, created_on " +
                "FROM public.\"MB003005\"" + where +
                " ORDER BY created_on DESC, id DESC LIMIT ? OFFSET ?",
                (rs, row) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", rs.getLong("id"));
                    item.put("category", rs.getString("category"));
                    item.put("title", rs.getString("title"));
                    item.put("message", rs.getString("message"));
                    item.put("branchCode", rs.getString("branch_code"));
                    item.put("referenceType", rs.getString("reference_type"));
                    item.put("referenceId", rs.getString("reference_id"));
                    item.put("isRead", rs.getBoolean("is_read"));
                    item.put("createdAt", rs.getTimestamp("created_on").toInstant());
                    return item;
                }, args.toArray());
        return Map.of("items", items, "total", total == null ? 0 : total,
                "unreadCount", unread == null ? 0 : unread, "page", pg, "pageSize", size);
    }

    @GetMapping("/count")
    public Map<String, Object> count(Authentication auth,
            @RequestParam(required = false) String category) {
        Caller who = Caller.of(auth);
        String cat = category(category);
        String sql = "SELECT count(*) FROM public.\"MB003005\" WHERE recipient_user_id = ? AND is_read = FALSE";
        Long count = cat == null
                ? db.queryForObject(sql, Long.class, who.id())
                : db.queryForObject(sql + " AND category = ?", Long.class, who.id(), cat);
        return Map.of("unreadCount", count == null ? 0 : count);
    }

    @PutMapping("/{id}/read")
    public Map<String, Object> markRead(@PathVariable long id, Authentication auth) {
        Caller who = Caller.of(auth);
        int updated = db.update("UPDATE public.\"MB003005\" SET is_read = TRUE, read_on = now() " +
                "WHERE id = ? AND recipient_user_id = ? AND is_read = FALSE", id, who.id());
        if (updated == 0) {
            Integer exists = db.queryForObject(
                    "SELECT count(*) FROM public.\"MB003005\" WHERE id = ? AND recipient_user_id = ?",
                    Integer.class, id, who.id());
            if (exists == null || exists == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Notification not found.");
        }
        return Map.of("id", id, "status", "READ");
    }

    @PutMapping("/read-all")
    public Map<String, Object> markAllRead(Authentication auth,
            @RequestParam(required = false) String category) {
        Caller who = Caller.of(auth);
        String cat = category(category);
        int updated = cat == null
                ? db.update("UPDATE public.\"MB003005\" SET is_read = TRUE, read_on = now() WHERE recipient_user_id = ? AND is_read = FALSE", who.id())
                : db.update("UPDATE public.\"MB003005\" SET is_read = TRUE, read_on = now() WHERE recipient_user_id = ? AND is_read = FALSE AND category = ?", who.id(), cat);
        return Map.of("status", "READ", "updated", updated);
    }

    /** Admin sends a bank-side alert to one active employee, a branch, or a role. */
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body, Authentication auth) {
        Caller creator = Caller.admin(auth);
        String category = category(required(body, "category", 40));
        String title = required(body, "title", 120);
        String message = required(body, "message", 1000);
        Object recipientRaw = body.get("recipientUserId");
        String branch = optional(body.get("branchCode"));
        String targetRole = optional(body.get("targetRole"));
        String referenceType = optional(body.get("referenceType"));
        String referenceId = optional(body.get("referenceId"));

        if (recipientRaw == null && branch == null && targetRole == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Provide recipientUserId, branchCode or targetRole.");
        }
        if (targetRole != null && !Set.of("STAFF", "ADMIN", "ALL_BANK").contains(targetRole.toUpperCase(Locale.ROOT))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "targetRole must be STAFF, ADMIN or ALL_BANK.");
        }

        List<Long> recipients;
        if (recipientRaw != null) {
            long recipientId;
            try { recipientId = Long.parseLong(recipientRaw.toString()); }
            catch (NumberFormatException ex) { throw new ApiException(HttpStatus.BAD_REQUEST, "recipientUserId must be numeric."); }
            recipients = db.query("SELECT user_id FROM public.\"MB001001\" WHERE user_id = ? AND user_type IN ('STAFF','ADMIN') AND status = 'ACTIVE'",
                    (rs, row) -> rs.getLong(1), recipientId);
        } else {
            StringBuilder sql = new StringBuilder("SELECT user_id FROM public.\"MB001001\" WHERE user_type IN ('STAFF','ADMIN') AND status = 'ACTIVE'");
            List<Object> args = new ArrayList<>();
            if (targetRole != null && !"ALL_BANK".equalsIgnoreCase(targetRole)) {
                sql.append(" AND user_type = ?");
                args.add(targetRole.toUpperCase(Locale.ROOT));
            }
            if (branch != null) {
                List<Map<String, Object>> branches = db.queryForList(
                        "SELECT branch_code, branch_name FROM public.\"MB002002\" WHERE upper(branch_code)=upper(?) OR upper(branch_name)=upper(?) LIMIT 1", branch, branch);
                if (branches.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown branch.");
                Map<String, Object> found = branches.get(0);
                sql.append(" AND upper(coalesce(branch,'')) IN (upper(?), upper(?))");
                args.add(found.get("branch_code"));
                args.add(found.get("branch_name"));
            }
            sql.append(" ORDER BY user_id");
            recipients = db.query(sql.toString(), (rs, row) -> rs.getLong(1), args.toArray());
        }
        if (recipients.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "No active bank recipients matched the target.");

        int inserted = 0;
        for (Long recipientId : recipients) {
            inserted += db.update("INSERT INTO public.\"MB003005\" " +
                    "(recipient_user_id, branch_code, category, title, message, reference_type, reference_id, created_by) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", recipientId, branch, category, title, message,
                    referenceType, referenceId, creator.id());
        }
        return Map.of("status", "CREATED", "recipients", inserted, "category", category);
    }

    private String category(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!CATEGORIES.contains(normalized)) throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported notification category.");
        return normalized;
    }

    private String status(String value) {
        String normalized = value == null ? "ALL" : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ALL", "READ", "UNREAD").contains(normalized)) throw new ApiException(HttpStatus.BAD_REQUEST, "status must be ALL, READ or UNREAD.");
        return normalized;
    }

    private String required(Map<String, Object> body, String key, int max) {
        String value = optional(body.get(key));
        if (value == null || value.length() > max) throw new ApiException(HttpStatus.BAD_REQUEST, key + " is required and must be at most " + max + " characters.");
        return value;
    }

    private String optional(Object raw) {
        if (raw == null) return null;
        String value = raw.toString().trim();
        return value.isEmpty() ? null : value;
    }
}
