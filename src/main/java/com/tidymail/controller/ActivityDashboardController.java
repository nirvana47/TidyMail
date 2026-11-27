package com.tidymail.controller;

import com.tidymail.service.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Controller
public class ActivityDashboardController {
    private final OwnedAccountScopeService accounts;
    private final DashboardJobService jobs;
    private final JdbcTemplate jdbc;
    public ActivityDashboardController(OwnedAccountScopeService accounts, DashboardJobService jobs, JdbcTemplate jdbc) {
        this.accounts = accounts; this.jobs = jobs; this.jdbc = jdbc;
    }
    public record StorageEstimate(long total, long known, double bytes) {
        public String formattedBytes() {
            String[] units = {"B", "KB", "MB", "GB", "TB"};
            double value = bytes;
            int unit = 0;
            while (value >= 1000 && unit < units.length - 1) { value /= 1000; unit++; }
            return String.format(java.util.Locale.US, unit == 0 ? "%,.0f %s" : "%,.1f %s", value, units[unit]);
        }
    }
    @ModelAttribute
    public void noStore(jakarta.servlet.http.HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    @GetMapping("/activity")
    public String activity(@RequestParam(defaultValue="0") int page, OAuth2AuthenticationToken auth, Model model) {
        if (page < 0 || page > 1_000_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid history page");
        long account = accounts.resolve(auth, "current").getFirst();
        model.addAttribute("mailbox", jdbc.queryForObject("SELECT email FROM user_accounts WHERE id=?", String.class, account));
        model.addAttribute("outlook",jdbc.queryForObject("SELECT provider='microsoft' FROM user_accounts WHERE id=?",Boolean.class,account));
        model.addAttribute("history", jobs.history(account, page));
        model.addAttribute("active", jobs.list(account).stream().filter(j -> j.remaining() > 0 || j.unresolved() > 0).toList());
        model.addAttribute("totals", jdbc.queryForMap("""
            SELECT count(DISTINCT t.message_id) FILTER(WHERE t.state='CHANGED' AND j.action_type IN ('ARCHIVE','MARK_READ_ARCHIVE','TRASH')) cleared,
              count(*) FILTER(WHERE t.state='CHANGED' AND j.action_type IN ('ARCHIVE','MARK_READ_ARCHIVE')) archived,
              count(*) FILTER(WHERE t.state='CHANGED' AND j.action_type='TRASH') trashed,
              count(*) FILTER(WHERE (t.state='CHANGED' AND j.action_type IN ('MARK_READ','MARK_READ_ARCHIVE'))
                OR (j.action_type='MARK_READ_ARCHIVE' AND t.outlook_read_completed)) read
            FROM cleanup_jobs j JOIN cleanup_job_targets t ON t.job_id=j.public_id
            WHERE j.account_id=?
            """, account));
        var storage = jdbc.queryForMap("""
            WITH trashed AS (
                SELECT t.message_id, max(COALESCE(t.size_estimate,m.size_estimate)) bytes
                FROM cleanup_jobs j JOIN cleanup_job_targets t ON t.job_id=j.public_id AND t.account_id=j.account_id
                LEFT JOIN message_metadata m ON m.account_id=j.account_id AND m.dashboard_message_id=t.message_id
                WHERE j.account_id=? AND j.action_type='TRASH' AND t.state='CHANGED'
                GROUP BY t.message_id
            ) SELECT count(*) total, count(bytes) known, COALESCE(sum(bytes),0) bytes FROM trashed
            """, account);
        model.addAttribute("storage", new StorageEstimate(((Number)storage.get("total")).longValue(),
            ((Number)storage.get("known")).longValue(), ((Number)storage.get("bytes")).doubleValue()));
        model.addAttribute("trend", jdbc.queryForList("""
            SELECT to_char(d.day,'Mon DD') label, count(t.ordinal) changed
            FROM generate_series(current_date-13,current_date,interval '1 day') d(day)
            LEFT JOIN cleanup_jobs j ON j.account_id=? AND j.created_at>=d.day AND j.created_at<d.day+interval '1 day'
            LEFT JOIN cleanup_job_targets t ON t.job_id=j.public_id AND t.state='CHANGED'
            GROUP BY d.day ORDER BY d.day
            """, account));
        model.addAttribute("senders", jdbc.queryForList("""
            SELECT COALESCE(NULLIF(m.from_address,''),'Sender unavailable') sender, count(DISTINCT t.message_id) cleared
            FROM cleanup_jobs j JOIN cleanup_job_targets t ON t.job_id=j.public_id
            LEFT JOIN message_metadata m ON m.account_id=j.account_id AND m.dashboard_message_id=t.message_id
            WHERE j.account_id=? AND t.state='CHANGED' AND j.action_type IN ('ARCHIVE','MARK_READ_ARCHIVE','TRASH')
            GROUP BY 1 ORDER BY cleared DESC, sender LIMIT 5
            """, account));
        return "activity";
    }
}
