package com.tidymail.service;

import com.tidymail.entity.UserAccount;
import com.tidymail.mail.gmail.GoogleCalendarReader;
import com.tidymail.repository.UserAccountRepository;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Reuses known invitation evidence; never submits mail to a semantic classifier. */
@Service
public class CalendarLabelRefreshService {
    private final JdbcTemplate jdbc;
    private final UserAccountRepository accounts;
    private final CalendarResponseService calendar;
    public CalendarLabelRefreshService(JdbcTemplate jdbc,UserAccountRepository accounts,CalendarResponseService calendar) {
        this.jdbc=jdbc;this.accounts=accounts;this.calendar=calendar;
    }

    /** One email per account per pass. Persisted check times make restarts resumable. */
    public void runOnce() {
        for(var account:accounts.findAll()) {
            if(account.getOauthStatus()!=UserAccount.OAuthStatus.ACTIVE || account.getEncryptedAccessToken()==null
                    || !hasCalendarAccess(account))continue;
            var candidates=jdbc.queryForList("""
                SELECT m.dashboard_message_id FROM message_metadata m
                LEFT JOIN calendar_response_checks cr USING(account_id,provider_message_id)
                LEFT JOIN message_recommendations saved ON saved.account_id=m.account_id AND saved.message_id=m.dashboard_message_id
                LEFT JOIN message_semantic_labels sl ON sl.account_id=m.account_id AND sl.message_id=m.dashboard_message_id
                LEFT JOIN message_evidence e ON e.account_id=m.account_id AND e.provider_message_id=m.provider_message_id
                WHERE m.account_id=? AND NOT(m.labels && ARRAY['SPAM','TRASH','DRAFT','SENT'])
                  AND (cr.checked_at IS NULL OR cr.checked_at<now()-CASE WHEN cr.response_state='UNKNOWN'
                       THEN interval '24 hours' ELSE interval '50 minutes' END)
                  AND (cr.provider_message_id IS NOT NULL OR 'OUTLOOK_CALENDAR_INVITE'=ANY(m.labels)
                    OR jsonb_exists(saved.local_label_reasons,'CALENDAR_INVITE')
                    OR 'CALENDAR_INVITE'=ANY(sl.labels)
                    OR saved.result->>'purpose'='CALENDAR_INVITE'
                    OR COALESCE(jsonb_path_exists(e.facts,'$.parts[*] ? (@.calendar == true)'),false)
                    OR COALESCE(jsonb_array_length(e.facts->'calendars'),0)>0)
                ORDER BY cr.checked_at ASC NULLS FIRST,m.received_at DESC,m.id
                LIMIT 1
                """,UUID.class,account.getId());
            if(candidates.isEmpty())continue;
            try { calendar.inspect(account.getId(),candidates.getFirst()); }
            catch(RuntimeException unavailable) { /* Retry next pass; provider details never enter logs. */ }
        }
    }

    private boolean hasCalendarAccess(UserAccount account) {
        if("google".equals(account.getProvider()))return GoogleCalendarReader.hasAccess(account);
        return "microsoft".equals(account.getProvider()) && account.getScopes()!=null
            && account.getScopes().stream().anyMatch(s -> Set.of("Calendars.ReadBasic","Calendars.Read","Calendars.ReadWrite")
                .contains(s.substring(s.lastIndexOf('/')+1)));
    }
}
