package com.tidymail.repository;

import com.tidymail.dto.DashboardFilter;
import com.tidymail.service.RecommendationSql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DashboardRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public DashboardRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public NamedParameterJdbcTemplate jdbc() {
        return jdbc;
    }

    // Exclusions are message-local and take priority over positive received
    // evidence.
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
            """ + DELIVERY + ")";
    // Read pending state once. A scalar initplan keeps its map out of the row joins
    // and avoids a correlated target query for every matching message.
    public static final String CATEGORY = "replace(COALESCE(m.classification_override,CASE WHEN m.classification_hash=m.classification_evidence_hash AND m.classification_version='"
            + com.tidymail.service.JevClassifier.VERSION
            + "' THEN m.classification_category END,'UNCLASSIFIED'),'UNCERTAIN','OTHER')";
    private static final String SEMANTIC_LABEL_MATCH="(:label=ANY(sl.labels) AND sl.input_hash="+RecommendationSql.SEMANTIC_HASH+")";
    private static final String LABELING_CATEGORY="COALESCE(m.classification_override,CASE WHEN sl.input_hash="+RecommendationSql.SEMANTIC_HASH
        +" THEN NULLIF(sl.purpose,'UNKNOWN') END,"+CATEGORY+")";
    private static final String RECOMMENDATION_FILTERS = """
                    AND (:recommendation='' OR CASE WHEN saved.message_id IS NULL THEN 'PENDING'
                      WHEN """+RecommendationSql.CURRENT+"""
                      THEN saved.result->>'action' ELSE 'STALE' END=:recommendation)
                    AND (:importance='' OR ("""+RecommendationSql.CURRENT+"""
                      AND saved.result->>'importance'=:importance))
                    AND (:label='' OR CASE :label
                      WHEN 'CONTACT' THEN COALESCE(h.sent_count,0)>0
                      WHEN 'LARGE_MESSAGE' THEN m.size_estimate>1000000
                      WHEN 'SENT' THEN 'SENT'=ANY(m.labels)
                      WHEN 'HAS_ATTACHMENTS' THEN m.attachment_state='PRESENT'
                      WHEN 'CALENDAR_INVITE' THEN """+SEMANTIC_LABEL_MATCH+"""
                        OR COALESCE(jsonb_path_exists(e.facts,'$.parts[*] ? (@.calendar == true)'),false)
                        OR COALESCE(jsonb_array_length(e.facts->'calendars'),0)>0
                        OR ("""+RecommendationSql.CURRENT+"""
                          AND saved.result->>'purpose'='CALENDAR_INVITE')
                      ELSE ("""+RecommendationSql.CURRENT+"""
                        AND (jsonb_exists(saved.result->'facets',:label) OR saved.result->>'purpose'=
                          CASE :label WHEN 'HUMAN_CORRESPONDENCE' THEN 'PERSONAL' ELSE :label END)) OR """+SEMANTIC_LABEL_MATCH+"""
                        END)
        """;
    private static final String GROUPING_LOOKUP = """
                    JOIN LATERAL (
                      SELECT g.public_id group_public_id,g.group_key,g.label group_label,
                        u.public_id unit_public_id,u.kind unit_kind,u.canonical_key unit_key,u.evidence_reason
                      FROM sender_units u JOIN sender_group_members member ON member.account_id=u.account_id AND member.unit_id=u.id
                      JOIN sender_groups g ON g.account_id=member.account_id AND g.id=member.group_id
                      WHERE u.account_id=link.account_id AND u.id=link.unit_id OFFSET 0
                    ) identity ON true
        """;
    // Keep membership as a keyed per-unit lookup even when freshness selectivity is underestimated.
    public static final String FILTERED = """
            WITH """+RecommendationSql.ACCOUNT_CONTEXT+"""
            , pending AS MATERIALIZED (
            SELECT COALESCE(jsonb_object_agg(message_id,state),'{}'::jsonb) AS states FROM (
              SELECT message_id,CASE min(CASE state WHEN 'AWAITING_VERIFICATION' THEN 0 WHEN 'DISPATCH_INTENT' THEN 1 ELSE 2 END)
                WHEN 0 THEN 'AWAITING_VERIFICATION' WHEN 1 THEN 'DISPATCH_INTENT' ELSE 'QUEUED' END AS state
              FROM cleanup_job_targets WHERE account_id=:account
                AND state IN ('QUEUED','DISPATCH_INTENT','AWAITING_VERIFICATION') GROUP BY message_id
            ) outcomes
            ), filtered AS (
            SELECT m.*,"""
            + LABELING_CATEGORY + """
                      AS category,identity.group_public_id,identity.group_key,identity.group_label,
                      identity.unit_public_id,identity.unit_kind,identity.unit_key,identity.evidence_reason,
                      COALESCE(m.dashboard_from_address,m.from_address,'Unknown sender') AS address,
                      (SELECT states FROM pending)->>m.dashboard_message_id::text AS pending_state
                    FROM message_metadata m JOIN account_context a ON a.id=m.account_id
                    """+RecommendationSql.JOINS+"""
                    JOIN message_sender_units link ON link.account_id=m.account_id AND link.metadata_id=m.id
                    """+GROUPING_LOOKUP+"""
                    WHERE m.account_id=:account AND
                    """ + "(:scope='indexed' OR " + RECEIVED + """
                    OR (:suggestion='large' AND 'SENT'=ANY(m.labels)
                        AND NOT(m.labels && ARRAY['SPAM','TRASH','DRAFT'])))
                    AND (:category='' OR
                    """ + LABELING_CATEGORY + """
                      =:category)
                    AND (:suggestion<>'large' OR m.size_estimate>1000000)
                    """+RECOMMENDATION_FILTERS+"""
                    AND (:scope IN ('all','indexed') OR 'INBOX'=ANY(m.labels))
                    AND (CAST(:start AS timestamptz) IS NULL OR m.received_at>=:start)
                    AND (CAST(:end AS timestamptz) IS NULL OR m.received_at<:end)
                    AND (:q='' OR position(lower(:q) in lower(identity.group_label))>0
                      OR position(lower(:q) in lower(COALESCE(m.dashboard_from_address,m.from_address,'')))>0
                      OR position(lower(:q) in lower(COALESCE(m.from_display_name,'')))>0
                      OR position(lower(:q) in lower(COALESCE(m.subject_header,'')))>0))
                    """;

    private static final String BASIC_FILTERED = FILTERED
            .replace(RecommendationSql.ACCOUNT_CONTEXT+", pending", " pending")
            .replace("JOIN account_context a ON a.id=m.account_id", "JOIN user_accounts a ON a.id=m.account_id")
            .replace(RecommendationSql.JOINS, "")
            .replace(GROUPING_LOOKUP,"""
                JOIN sender_units u ON u.account_id=link.account_id AND u.id=link.unit_id
                JOIN sender_group_members member ON member.account_id=u.account_id AND member.unit_id=u.id
                JOIN sender_groups g ON g.account_id=member.account_id AND g.id=member.group_id
                """)
            .replace("identity.group_public_id","g.public_id AS group_public_id")
            .replace("identity.group_key","g.group_key").replace("identity.group_label,","g.label AS group_label,")
            .replace("identity.unit_public_id","u.public_id AS unit_public_id").replace("identity.unit_kind","u.kind AS unit_kind")
            .replace("identity.unit_key","u.canonical_key AS unit_key").replace("identity.evidence_reason","u.evidence_reason")
            .replace("lower(identity.group_label)","lower(g.label)")
            .replace(LABELING_CATEGORY, CATEGORY)
            .replace(RECOMMENDATION_FILTERS, "");
    private static final String CURRENT_ACTION_FILTERED = FILTERED.replace(RECOMMENDATION_FILTERS,
        "AND saved.result->>'action'=:recommendation AND "+RecommendationSql.CURRENT+"\n"
        +"AND (:importance='' OR saved.result->>'importance'=:importance)\n"
        +"AND (:label='' OR jsonb_exists(saved.result->'facets',:label) OR saved.result->>'purpose'="
        +"CASE :label WHEN 'HUMAN_CORRESPONDENCE' THEN 'PERSONAL' ELSE :label END)\n")
        .replace(LABELING_CATEGORY,"COALESCE(m.classification_override,NULLIF(saved.result->>'purpose','UNKNOWN'),"+CATEGORY+")");
    /** Avoid enrichment joins entirely on the ordinary browsing path. */
    public String filtered(DashboardFilter filter) {
        if(filter.category().isEmpty() && filter.recommendation().isEmpty() && filter.importance().isEmpty() && filter.label().isEmpty()) return BASIC_FILTERED;
        if(!filter.recommendation().isEmpty() && !java.util.Set.of("PENDING","STALE").contains(filter.recommendation())) return CURRENT_ACTION_FILTERED;
        return FILTERED;
    }

    public MapSqlParameterSource parameters(long account, DashboardFilter filter) {
        return new MapSqlParameterSource("account", account).addValue("scope", filter.scope())
                .addValue("suggestion", filter.suggestion()).addValue("category", filter.category())
                .addValue("recommendation",filter.recommendation()).addValue("importance",filter.importance()).addValue("label",filter.label())
                .addValue("asOf",timestamp(Instant.now()))
                .addValue("q", filter.q())
                .addValue("start", timestamp(filter.startInstant()))
                .addValue("end", timestamp(filter.endInstant())).addValue("offset", filter.page() * 10L);
    }

    public Page page(long account, DashboardFilter filter) {
        var p = parameters(account, filter);
        var summary = new java.util.LinkedHashMap<String,Object>();
        var mailingLists = new ArrayList<UUID>();
        var collected = new ArrayList<Group>();
        // Count and page one grouped snapshot instead of scanning the enriched mailbox twice.
        jdbc.query(filtered(filter)+"""
                , group_counts AS MATERIALIZED (
                  SELECT group_public_id,group_key,group_label,count(*) messages,count(*) FILTER(WHERE unread) unread,
                    max(received_at) latest,count(DISTINCT address) addresses,
                    COALESCE(sum(size_estimate),0) known_bytes,count(*) FILTER(WHERE size_estimate IS NULL) unknown_sizes,
                    count(*) FILTER(WHERE received_at IS NULL) undated,NULL::text display_name,
                    CASE WHEN group_key LIKE 'address:%' THEN min(address) END exact_address,
                    count(*) FILTER(WHERE pending_state IS NOT NULL) pending
                  FROM filtered GROUP BY group_public_id,group_key,group_label
                ), totals AS (
                  SELECT COALESCE(sum(messages),0)::bigint messages,COALESCE(sum(unread),0)::bigint unread,count(*) groups,
                    COALESCE(sum(undated),0)::bigint undated,COALESCE(sum(known_bytes),0) known_bytes,
                    COALESCE(sum(unknown_sizes),0)::bigint unknown_sizes FROM group_counts
                ), page_groups AS (
                  SELECT * FROM group_counts ORDER BY
                """+(filter.sort().equals("unread")?"unread DESC,":"")+"""
                  messages DESC,group_key LIMIT 10 OFFSET :offset
                ) SELECT totals.messages total_messages,totals.unread total_unread,totals.groups total_groups,
                    totals.undated total_undated,totals.known_bytes total_known_bytes,totals.unknown_sizes total_unknown_sizes,page_groups.*
                  FROM totals LEFT JOIN page_groups ON true
                """,p,(org.springframework.jdbc.core.RowCallbackHandler)r->{
            for(String key:List.of("messages","unread","groups","undated","known_bytes","unknown_sizes"))summary.put(key,r.getObject("total_"+key));
            UUID id=uuid(r,"group_public_id");if(id==null)return;
            String key=r.getString("group_key"),label=r.getString("group_label");
            if(key.equals("list:"+label) || key.startsWith("address:"))mailingLists.add(id);
            collected.add(new Group(id,label,r.getLong("messages"),r.getLong("unread"),instant(r,"latest"),r.getLong("addresses"),
                groupingEvidence(key),r.getLong("pending"),r.getString("exact_address"),r.getLong("known_bytes"),r.getLong("unknown_sizes")));
        });
        var groups = labelMailingLists(account,collected,mailingLists);
        var coverage = jdbc.queryForMap(
                """
                        SELECT count(*) indexed,min(m.received_at) oldest,max(m.received_at) newest,
                          max(m.last_synced_at) as_of,
                          count(*) FILTER(WHERE NOT m.label_evidence_known AND cardinality(m.labels)=0) unknown_labels,
                          count(*) FILTER(WHERE m.dashboard_from_address IS NULL) unknown_identity,
                          count(*) FILTER(WHERE 'SENT'=ANY(m.labels) AND NOT('INBOX'=ANY(m.labels)) AND NOT (
                        """ + DELIVERY
                        + """
                                  )) ambiguous_sent FROM message_metadata m JOIN user_accounts a ON a.id=m.account_id WHERE m.account_id=:account
                                """,
                p);
        Map<String, Object> state = jdbc.queryForMap(
                """
                        SELECT a.email,a.oauth_status,s.lifecycle,s.backfill_status,s.initial_batch_completed_at,
                          s.incremental_last_success_at,s.error_code FROM user_accounts a LEFT JOIN mailbox_sync_state s ON s.account_id=a.id
                        WHERE a.id=:account
                        """,
                p);
        long revision = revision(account);
        return new Page(groups, summary, coverage, state, revision, filter, classificationRevision(account));
    }

    /**
     * Display evidence never changes membership or overrides a saved label.
     * Read the whole received group so a search/category/page cannot hide
     * conflicting names.
     */
    private List<Group> labelMailingLists(long account, List<Group> groups, List<UUID> mailingLists) {
        if (mailingLists.isEmpty())
            return groups;
        var names = new HashMap<UUID, String>();
        var p = new MapSqlParameterSource("account", account).addValue("groups", mailingLists);
        jdbc.query("""
                SELECT g.public_id, count(DISTINCT NULLIF(btrim(m.from_display_name),'')) name_count,
                  min(NULLIF(btrim(m.from_display_name),'')) display_name
                FROM sender_groups g
                JOIN sender_group_members member ON member.account_id=g.account_id AND member.group_id=g.id
                JOIN message_sender_units link ON link.account_id=member.account_id AND link.unit_id=member.unit_id
                JOIN message_metadata m ON m.account_id=link.account_id AND m.id=link.metadata_id
                JOIN user_accounts a ON a.id=m.account_id
                WHERE g.account_id=:account AND g.public_id IN (:groups) AND
                """ + RECEIVED + " GROUP BY g.public_id", p, r -> {
            if (r.getLong("name_count") == 1)
                names.put(uuid(r, "public_id"), r.getString("display_name"));
        });
        return groups.stream().map(g -> names.containsKey(g.id())
                ? new Group(g.id(), names.get(g.id()), g.messages(), g.unread(), g.latest(), g.addresses(),
                        g.evidence(), g.pending(), g.address(), g.knownBytes(), g.unknownSizes())
                : g).toList();
    }

    public long revision(long account) {
        Long result = jdbc.queryForObject(
                "SELECT COALESCE((SELECT grouping_revision FROM dashboard_account_settings WHERE account_id=:account),0)",
                Map.of("account", account), Long.class);
        return result;
    }

    public long classificationRevision(long account) {
        return jdbc.queryForObject(
                "SELECT COALESCE((SELECT classification_revision FROM dashboard_account_settings WHERE account_id=:account),0)",
                Map.of("account", account), Long.class);
    }

    public Details details(long account, DashboardFilter filter, UUID group, int messagePage) {
        return details(account, filter, group, messagePage, null);
    }

    public Details details(long account, DashboardFilter filter, UUID group, int messagePage, String address) {
        return details(account, filter, group, messagePage, address, false);
    }

    public Details details(long account, DashboardFilter filter, UUID group, int messagePage,String address, boolean unreadOnly) {
        if (messagePage < 0 || messagePage > 1_000_000)
            throw new IllegalArgumentException("Invalid message page");
        var p = parameters(account, filter).addValue("group", group).addValue("message_offset", messagePage * 10L).addValue("address",address);
        if (jdbc.queryForObject("SELECT count(*) FROM sender_groups WHERE account_id=:account AND public_id=:group", p,
                Long.class) == 0)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Sender group unavailable for this account");
        String allGroup = " FROM filtered WHERE group_public_id=:group";
        String inGroup=allGroup+" AND (CAST(:address AS text) IS NULL OR address=:address)" + (unreadOnly ? " AND unread" : "");
        long total = jdbc.queryForObject(filtered(filter) + "SELECT count(*)" + inGroup, p, Long.class);
        List<Message> messages = jdbc.query(
                filtered(filter) + """
                SELECT filtered.*,
                EXISTS(SELECT 1 FROM correspondent_summary h WHERE h.account_id=filtered.account_id
                  AND h.address=filtered.dashboard_from_address AND h.sent_count>0) contact,
                EXISTS(SELECT 1 FROM message_evidence e WHERE e.account_id=filtered.account_id
                  AND e.provider_message_id=filtered.provider_message_id
                  AND (jsonb_path_exists(e.facts,'$.parts[*] ? (@.calendar == true)')
                    OR jsonb_array_length(COALESCE(e.facts->'calendars','[]'::jsonb))>0)) calendar_invite,
                (SELECT p.label FROM dashboard_pattern_labels p
                JOIN dashboard_pattern_reviews review ON review.account_id=p.account_id AND review.id=p.review_id
                JOIN message_metadata source ON source.account_id=p.account_id AND source.dashboard_message_id=p.source_id
                JOIN message_metadata target ON target.account_id=p.account_id AND target.dashboard_message_id=p.message_id
                WHERE p.account_id=filtered.account_id AND p.message_id=filtered.dashboard_message_id
                AND review.version='"""+com.tidymail.service.ReviewedPatternService.VERSION+"'"+"""
                AND dashboard_pattern_hash(target)=p.evidence_hash AND dashboard_pattern_hash(source)=p.source_hash
                AND source.classification_override=p.label
                AND NOT EXISTS(SELECT 1 FROM message_evidence e WHERE e.account_id=target.account_id
                  AND e.provider_message_id IN (target.provider_message_id,source.provider_message_id) AND jsonb_array_length(e.facts->'calendars')>0)) reviewed_pattern
                """ + inGroup
                        + " ORDER BY received_at DESC NULLS LAST,provider_message_id LIMIT 10 OFFSET :message_offset",
                p, (r, n) -> message(r,r.getString("reviewed_pattern")));
        var addresses = jdbc.query(
                filtered(filter) + "SELECT address,max(NULLIF(from_display_name,'')) display_name,count(*) messages,count(*) FILTER(WHERE unread) unread" + allGroup
                        + " GROUP BY address ORDER BY address",
                p, (r, n) -> new Address(r.getString("address"), r.getLong("messages"), r.getLong("unread"),r.getString("display_name")));
        var units = jdbc.query(
                filtered(filter) + "SELECT DISTINCT unit_public_id,unit_kind,unit_key,evidence_reason" + allGroup
                        + " ORDER BY unit_public_id",
                p, (r, n) -> new Unit(uuid(r, "unit_public_id"), r.getString("unit_kind"), r.getString("unit_key"),
                        r.getString("evidence_reason")));
        return new Details(messages, addresses, units, total, messagePage, revision(account),classificationRevision(account));
    }

    public List<Selected> selection(long account, DashboardFilter filter, List<UUID> groups, String address) {
        return selection(account, filter, groups, address, false);
    }
    public List<Selected> selection(long account, DashboardFilter filter, List<UUID> groups, String address, boolean unreadOnly) {
        if (groups == null || groups.isEmpty())
            return List.of();
        var p = parameters(account, filter).addValue("groups", groups).addValue("address", address);
        if (jdbc.queryForObject(
                "SELECT count(*) FROM sender_groups WHERE account_id=:account AND public_id IN (:groups)", p,
                Long.class) != groups.stream().distinct().count())
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Sender group unavailable for this account");
        return jdbc.query(filtered(filter)
                + "SELECT dashboard_message_id,group_public_id,address FROM filtered WHERE group_public_id IN (:groups) AND (CAST(:address AS text) IS NULL OR address=:address)"
                + (unreadOnly ? " AND unread" : "") + " ORDER BY provider_message_id",
                p,
                (r, n) -> new Selected(uuid(r, "dashboard_message_id"), uuid(r, "group_public_id"),
                        r.getString("address")));
    }

    public static Message message(ResultSet r) throws SQLException { return message(r,null); }

    private static Message message(ResultSet r,String pattern) throws SQLException {
        return new Message(uuid(r, "dashboard_message_id"), r.getString("subject_header"), r.getString("address"),
                instant(r, "received_at"), r.getBoolean("unread"), uuid(r, "group_public_id"),
                r.getString("pending_state"), r.getString("category"),r.getString("from_display_name"),r.getString("classification_override")!=null?"MANUAL":r.getString("category").equals("UNCLASSIFIED")?"NONE":"JEV",
                r.getObject("size_estimate",Long.class),r.getString("attachment_state"),r.getString("snippet_text"),r.getString("snippet_state"),pattern,java.util.Arrays.asList((String[])r.getArray("labels").getArray()).contains("SENT"),r.getBoolean("contact"),r.getBoolean("calendar_invite"));
    }

    public static UUID uuid(ResultSet r, String column) throws SQLException {
        return r.getObject(column, UUID.class);
    }




    public static Instant instant(ResultSet r, String column) throws SQLException {
        Timestamp v = r.getTimestamp(column);
        return v == null ? null : v.toInstant();


    }


    public static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static String groupingEvidence(String key) {
        if (key.startsWith("manual:"))
            return "Manually merged — saved account correction";
        if (key.startsWith("separate:"))
            return "Kept separate by your saved correction";
        if (key.startsWith("classified-domain:"))
            return "Grouped by Jev classification of organization and service";
        if (key.startsWith("organization:"))
            return "Reviewed organization domain; message types remain separate";
        if (key.startsWith("domain:"))
            return "Grouped by organization domain, including subdomains";
        if (key.startsWith("list:"))
            return "Grouped by a single valid List-ID";
        return key.startsWith("address:") ? "Exact sender address; no domain merge inferred"
                : "Kept separate — identity evidence is incomplete";
    }

    public record Group(UUID id, String label, long messages, long unread, Instant latest, long addresses,
            String evidence, long pending,String address,long knownBytes,long unknownSizes) {
    }

    public record Message(UUID id, String subject, String address, Instant receivedAt, boolean unread, UUID groupId,
            String pendingState, String category,String displayName,String categorySource,
            Long sizeEstimate,String attachmentState,String snippet,String snippetState,String reviewedPattern,boolean sent,boolean contact,boolean calendarInvite) {
    }

    public record Address(String address, long messages, long unread,String displayName) {
    }

    public record Unit(UUID id, String kind, String key, String reason) {
    }

    public record Selected(UUID id, UUID groupId,  String address) {
    }

    public record Details(List<Message> messages, List<Address> addresses, List<Unit> units, long total, int page,
            long revision,long classificationRevision, java.util.List<com.tidymail.service.RecommendationService.View> recommendations) {
        public Details(List<Message> messages,List<Address> addresses,List<Unit> units,long total,int page,long revision,long classificationRevision) {
            this(messages,addresses,units,total,page,revision,classificationRevision,List.of());
        }
        public Details withRecommendations(java.util.List<com.tidymail.service.RecommendationService.View> views) {
            return new Details(messages,addresses,units,total,page,revision,classificationRevision,views);
        }
    }

    public record Page(List<Group> groups, Map<String, Object> summary, Map<String, Object> coverage,
            Map<String, Object> sync, long revision, DashboardFilter filter,long classificationRevision) {
    }
}
