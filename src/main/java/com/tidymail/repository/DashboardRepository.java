package com.tidymail.repository;

import com.tidymail.dto.DashboardFilter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DashboardRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public DashboardRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }

    public NamedParameterJdbcTemplate jdbc() { return jdbc; }

    // Exclusions are message-local and take priority over positive received evidence.
    public static final String DELIVERY = """
       EXISTS(SELECT 1 FROM unnest(m.delivered_to_values) d
         WHERE lower(trim(both '<> ' from d))=lower(a.email)
           OR EXISTS(SELECT 1 FROM dashboard_verified_aliases alias
             WHERE alias.account_id=m.account_id AND lower(alias.address)=lower(trim(both '<> ' from d))))
       """;
    public static final String RECEIVED = """
       NOT(m.labels && ARRAY['SPAM','TRASH','DRAFT'])
       AND (m.label_evidence_known OR cardinality(m.labels)>0)
       AND ('INBOX'=ANY(m.labels) OR NOT('SENT'=ANY(m.labels)) OR
       """+DELIVERY+")";
    // Read pending state once. A scalar initplan keeps its map out of the row joins
    // and avoids a correlated target query for every matching message.
    public static final String FILTERED = """
       WITH pending AS MATERIALIZED (
       SELECT COALESCE(jsonb_object_agg(message_id,state),'{}'::jsonb) AS states FROM (
         SELECT message_id,CASE min(CASE state WHEN 'AWAITING_VERIFICATION' THEN 0 WHEN 'DISPATCH_INTENT' THEN 1 ELSE 2 END)
           WHEN 0 THEN 'AWAITING_VERIFICATION' WHEN 1 THEN 'DISPATCH_INTENT' ELSE 'QUEUED' END AS state
         FROM cleanup_job_targets WHERE account_id=:account
           AND state IN ('QUEUED','DISPATCH_INTENT','AWAITING_VERIFICATION') GROUP BY message_id
       ) outcomes
       ), filtered AS (
       SELECT m.*,g.public_id AS group_public_id,g.group_key,g.label AS group_label,
         u.public_id AS unit_public_id,u.kind AS unit_kind,u.canonical_key AS unit_key,u.evidence_reason,
         COALESCE(m.dashboard_from_address,m.from_address,'Unknown sender') AS address,
         (SELECT states FROM pending)->>m.dashboard_message_id::text AS pending_state
       FROM message_metadata m JOIN user_accounts a ON a.id=m.account_id
       JOIN message_sender_units link ON link.account_id=m.account_id AND link.metadata_id=m.id
       JOIN sender_units u ON u.account_id=link.account_id AND u.id=link.unit_id
       JOIN sender_group_members member ON member.account_id=u.account_id AND member.unit_id=u.id
       JOIN sender_groups g ON g.account_id=member.account_id AND g.id=member.group_id
       WHERE m.account_id=:account AND
       """+RECEIVED+"""
       AND (:scope='all' OR 'INBOX'=ANY(m.labels))
       AND (CAST(:start AS timestamptz) IS NULL OR m.received_at>=:start)
       AND (CAST(:end AS timestamptz) IS NULL OR m.received_at<:end)
       AND (:q='' OR position(lower(:q) in lower(g.label))>0
         OR position(lower(:q) in lower(COALESCE(m.dashboard_from_address,m.from_address,'')))>0
         OR position(lower(:q) in lower(COALESCE(m.from_display_name,'')))>0
         OR position(lower(:q) in lower(COALESCE(m.subject_header,'')))>0))
       """;

    public MapSqlParameterSource parameters(long account, DashboardFilter filter) {
        return new MapSqlParameterSource("account",account).addValue("scope",filter.scope())
            .addValue("q",filter.q()).addValue("start",timestamp(filter.startInstant()))
            .addValue("end",timestamp(filter.endInstant())).addValue("offset",filter.page()*10L);
    }

    public Page page(long account,DashboardFilter filter) {
        var p=parameters(account,filter);
        var summary=jdbc.queryForMap(FILTERED+" SELECT count(*) messages,count(*) FILTER(WHERE unread) unread,count(DISTINCT group_public_id) groups,count(*) FILTER(WHERE received_at IS NULL) undated FROM filtered",p);
        var groups=jdbc.query(FILTERED+"""
          SELECT group_public_id,group_key,group_label,count(*) messages,count(*) FILTER(WHERE unread) unread,
          max(received_at) latest,count(DISTINCT address) addresses,count(*) FILTER(WHERE pending_state IS NOT NULL) pending
          FROM filtered GROUP BY group_public_id,group_key,group_label
          ORDER BY count(*) DESC,group_key LIMIT 10 OFFSET :offset
          """,p,(r,n)->new Group(uuid(r,"group_public_id"),r.getString("group_label"),r.getLong("messages"),r.getLong("unread"),instant(r,"latest"),r.getLong("addresses"),groupingEvidence(r.getString("group_key")),r.getLong("pending")));
        var coverage=jdbc.queryForMap("""
          SELECT count(*) indexed,min(m.received_at) oldest,max(m.received_at) newest,
            max(m.last_synced_at) as_of,
            count(*) FILTER(WHERE NOT m.label_evidence_known AND cardinality(m.labels)=0) unknown_labels,
            count(*) FILTER(WHERE m.dashboard_from_address IS NULL) unknown_identity,
            count(*) FILTER(WHERE 'SENT'=ANY(m.labels) AND NOT('INBOX'=ANY(m.labels)) AND NOT (
          """+DELIVERY+"""
            )) ambiguous_sent FROM message_metadata m JOIN user_accounts a ON a.id=m.account_id WHERE m.account_id=:account
          """,p);
        Map<String,Object> state=jdbc.queryForMap("""
          SELECT a.email,a.oauth_status,s.lifecycle,s.backfill_status,s.initial_batch_completed_at,
            s.incremental_last_success_at,s.error_code FROM user_accounts a LEFT JOIN mailbox_sync_state s ON s.account_id=a.id
          WHERE a.id=:account
          """,p);
        long revision=revision(account);
        return new Page(groups,summary,coverage,state,revision,filter);
    }

    public long revision(long account) {
        Long result=jdbc.queryForObject("SELECT COALESCE((SELECT grouping_revision FROM dashboard_account_settings WHERE account_id=:account),0)",Map.of("account",account),Long.class);
        return result;
    }

    public Details details(long account,DashboardFilter filter,UUID group,int messagePage) {
        if (messagePage<0 || messagePage>1_000_000) throw new IllegalArgumentException("Invalid message page");
        var p=parameters(account,filter).addValue("group",group).addValue("message_offset",messagePage*10L);
        if(jdbc.queryForObject("SELECT count(*) FROM sender_groups WHERE account_id=:account AND public_id=:group",p,Long.class)==0)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Sender group unavailable for this account");
        String inGroup=" FROM filtered WHERE group_public_id=:group";
        long total=jdbc.queryForObject(FILTERED+"SELECT count(*)"+inGroup,p,Long.class);
        List<Message> messages=jdbc.query(FILTERED+"SELECT *"+inGroup+" ORDER BY received_at DESC NULLS LAST,provider_message_id LIMIT 10 OFFSET :message_offset",p,(r,n)->message(r));
        var addresses=jdbc.query(FILTERED+"SELECT address,count(*) messages,count(*) FILTER(WHERE unread) unread"+inGroup+" GROUP BY address ORDER BY address",p,(r,n)->new Address(r.getString("address"),r.getLong("messages"),r.getLong("unread")));
        var units=jdbc.query(FILTERED+"SELECT DISTINCT unit_public_id,unit_kind,unit_key,evidence_reason"+inGroup+" ORDER BY unit_public_id",p,(r,n)->new Unit(uuid(r,"unit_public_id"),r.getString("unit_kind"),r.getString("unit_key"),r.getString("evidence_reason")));
        return new Details(messages,addresses,units,total,messagePage,revision(account));
    }

    public List<Selected> selection(long account,DashboardFilter filter,List<UUID> groups,String address) {
        if(groups==null || groups.isEmpty()) return List.of();
        var p=parameters(account,filter).addValue("groups",groups).addValue("address",address);
        if(jdbc.queryForObject("SELECT count(*) FROM sender_groups WHERE account_id=:account AND public_id IN (:groups)",p,Long.class)!=groups.stream().distinct().count())
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Sender group unavailable for this account");
        return jdbc.query(FILTERED+"SELECT dashboard_message_id,group_public_id,address FROM filtered WHERE group_public_id IN (:groups) AND (CAST(:address AS text) IS NULL OR address=:address) ORDER BY provider_message_id",p,
            (r,n)->new Selected(uuid(r,"dashboard_message_id"),uuid(r,"group_public_id"),r.getString("address")));
    }

    public static Message message(ResultSet r) throws SQLException {
        return new Message(uuid(r,"dashboard_message_id"),r.getString("subject_header"),r.getString("address"),instant(r,"received_at"),r.getBoolean("unread"),uuid(r,"group_public_id"),r.getString("pending_state"));
    }
    public static UUID uuid(ResultSet r,String column) throws SQLException { return r.getObject(column,UUID.class); }
    public static Instant instant(ResultSet r,String column) throws SQLException { Timestamp v=r.getTimestamp(column);return v==null ? null : v.toInstant(); }
    public static Timestamp timestamp(Instant instant) { return instant==null ? null : Timestamp.from(instant); }
    private static String groupingEvidence(String key) {
        if(key.startsWith("manual:"))return "Manually merged — saved account correction";
        if(key.startsWith("separate:"))return "Kept separate by your saved correction";
        if(key.startsWith("domain:"))return "Grouped by reviewed exact domain";
        if(key.startsWith("list:"))return "Grouped by a single valid List-ID";
        return key.startsWith("address:") ? "Exact sender address; no domain merge inferred" : "Kept separate — identity evidence is incomplete";
    }
    public record Group(UUID id,String label,long messages,long unread,Instant latest,long addresses,String evidence,long pending) {}
    public record Message(UUID id,String subject,String address,Instant receivedAt,boolean unread,UUID groupId,String pendingState) {}
    public record Address(String address,long messages,long unread) {}
    public record Unit(UUID id,String kind,String key,String reason) {}
    public record Selected(UUID id,UUID groupId,String address) {}
    public record Details(List<Message> messages,List<Address> addresses,List<Unit> units,long total,int page,long revision) {}
    public record Page(List<Group> groups,Map<String,Object> summary,Map<String,Object> coverage,Map<String,Object> sync,long revision,DashboardFilter filter) {}
}
