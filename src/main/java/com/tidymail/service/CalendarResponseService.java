package com.tidymail.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tidymail.mail.gmail.GoogleCalendarReader;
import com.tidymail.mail.gmail.GmailEvidenceReader;
import com.tidymail.repository.UserAccountRepository;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Explicit, account-scoped reads. Calendar responses never change cleanup recommendations. */
@Service
public class CalendarResponseService {
    private final JdbcTemplate jdbc;
    private final UserAccountRepository accounts;
    private final GmailEvidenceReader mail;
    private final GoogleCalendarReader calendar;
    private final com.tidymail.mail.outlook.OutlookCalendarReader outlook;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final Set<Long> reading=ConcurrentHashMap.newKeySet();
    public CalendarResponseService(JdbcTemplate jdbc,UserAccountRepository accounts,GmailEvidenceReader mail,
            GoogleCalendarReader calendar,com.tidymail.mail.outlook.OutlookCalendarReader outlook,ObjectMapper json,TransactionTemplate tx) {
        this.outlook=outlook;
        this.jdbc=jdbc;this.accounts=accounts;this.mail=mail;this.calendar=calendar;this.json=json;this.tx=tx;
    }
    public record Snapshot(String state,Instant checkedAt,Instant expiresAt,List<CalendarResponse> events) {}
    public Snapshot inspect(long account,UUID message) {
        var rows=jdbc.queryForList("""
            SELECT provider_message_id FROM message_metadata WHERE account_id=? AND dashboard_message_id=?
              AND provider IN ('google','microsoft') AND NOT(labels && ARRAY['SPAM','TRASH','DRAFT'])
            """,String.class,account,message);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Email unavailable for this account");
        if(!reading.add(account))throw new ResponseStatusException(HttpStatus.CONFLICT,"Another calendar check is running for this account");
        try {
            var owner=accounts.findById(account).orElseThrow();
            List<CalendarResponse> events;
            if("microsoft".equals(owner.getProvider()))
                events=outlook.read(owner,rows.getFirst());
            else if(!GoogleCalendarReader.hasAccess(owner))
                events=calendar.read(owner,List.of());
            else {
                var identities=mail.readCalendarIdentities(owner,rows.getFirst());
                events=calendar.read(owner,identities);
            }
            Instant now=Instant.now();
            var result=new Snapshot(state(events),now,now.plusSeconds(3600),events);
            String encoded=json.writeValueAsString(result);
            tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM user_accounts WHERE id=? FOR UPDATE",Long.class,account);
                var present=jdbc.queryForList("SELECT id FROM message_metadata WHERE account_id=? AND provider_message_id=? FOR KEY SHARE",Long.class,account,rows.getFirst());
                if(present.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Email no longer indexed");
                jdbc.update("""
                    INSERT INTO calendar_response_checks(account_id,provider_message_id,response_state,checked_at,facts)
                    VALUES(?,?,?,?,CAST(? AS jsonb)) ON CONFLICT(account_id,provider_message_id) DO UPDATE
                    SET response_state=EXCLUDED.response_state,checked_at=EXCLUDED.checked_at,facts=EXCLUDED.facts
                    """,account,rows.getFirst(),result.state(),Timestamp.from(now),encoded);
                jdbc.update("""
                    INSERT INTO dashboard_account_settings(account_id,classification_revision) VALUES(?,1)
                    ON CONFLICT(account_id) DO UPDATE SET classification_revision=dashboard_account_settings.classification_revision+1
                    """,account);
            });
            return result;
        } catch(ResponseStatusException e) { throw e; }
        catch(Exception failure) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Calendar could not be checked. Retry or reconnect your account if needed."); }
        finally { reading.remove(account); }
    }
    private static String state(List<CalendarResponse> events) {
        if(events.isEmpty() || events.stream().anyMatch(e -> !e.state().equals("MATCHED")))return "UNKNOWN";
        if(events.stream().allMatch(e -> e.response().equals("cancelled")))return "CANCELLED";
        if(events.stream().anyMatch(e -> e.response().equals("needsAction")))return "NEEDS_RESPONSE";
        if(events.stream().allMatch(e -> Set.of("accepted","declined","tentative").contains(e.response())))return "RESPONDED";
        return "UNKNOWN";
    }
}
