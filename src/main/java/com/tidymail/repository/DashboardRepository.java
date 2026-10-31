package com.tidymail.repository;

import com.tidymail.dto.DashboardFilter;
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
    public static final String CATEGORY = "COALESCE(m.classification_override,CASE WHEN m.classification_hash=m.classification_evidence_hash AND m.classification_version='"
            + com.tidymail.service.JevClassifier.VERSION + "' THEN m.classification_category END,'UNCLASSIFIED')";
    public static final String FILTERED = """
            WITH pending AS MATERIALIZED (
            SELECT COALESCE(jsonb_object_agg(message_id,state),'{}'::jsonb) AS states FROM (
              SELECT message_id,CASE min(CASE state WHEN 'AWAITING_VERIFICATION' THEN 0 WHEN 'DISPATCH_INTENT' THEN 1 ELSE 2 END)
                WHEN 0 THEN 'AWAITING_VERIFICATION' WHEN 1 THEN 'DISPATCH_INTENT' ELSE 'QUEUED' END AS state
              FROM cleanup_job_targets WHERE account_id=:account
                AND state IN ('QUEUED','DISPATCH_INTENT','AWAITING_VERIFICATION') GROUP BY message_id
            ) outcomes
            ), filtered AS (
            SELECT m.*,"""
            + CATEGORY + """
                      AS category,g.public_id AS group_public_id,g.group_key,g.label AS group_label,
                      u.public_id AS unit_public_id,u.kind AS unit_kind,u.canonical_key AS unit_key,u.evidence_reason,
                      COALESCE(m.dashboard_from_address,m.from_address,'Unknown sender') AS address,
                      (SELECT states FROM pending)->>m.dashboard_message_id::text AS pending_state
                    FROM message_metadata m JOIN user_accounts a ON a.id=m.account_id
                    JOIN message_sender_units link ON link.account_id=m.account_id AND link.metadata_id=m.id
                    JOIN sender_units u ON u.account_id=link.account_id AND u.id=link.unit_id
                    JOIN sender_group_members member ON member.account_id=u.account_id AND member.unit_id=u.id
                    JOIN sender_groups g ON g.account_id=member.account_id AND g.id=member.group_id
                    WHERE m.account_id=:account AND
                    """ + RECEIVED + """
                    AND (:category='' OR
                    """ + CATEGORY + """
                      =:category)
                    AND (:scope='all' OR 'INBOX'=ANY(m.labels))
                    AND (CAST(:start AS timestamptz) IS NULL OR m.received_at>=:start)
                    AND (CAST(:end AS timestamptz) IS NULL OR m.received_at<:end)
                    AND (:q='' OR position(lower(:q) in lower(g.label))>0
                      OR position(lower(:q) in lower(COALESCE(m.dashboard_from_address,m.from_address,'')))>0
                      OR position(lower(:q) in lower(COALESCE(m.from_display_name,'')))>0
                      OR position(lower(:q) in lower(COALESCE(m.subject_header,'')))>0))
                    """;

    public MapSqlParameterSource parameters(long account, DashboardFilter filter) {
        return new MapSqlParameterSource("account", account).addValue("scope", filter.scope())
                .addValue("category", filter.category()).addValue("q", filter.q())
                .addValue("start", timestamp(filter.startInstant()))
                .addValue("end", timestamp(filter.endInstant())).addValue("offset", filter.page() * 10L);
    }

    public Page page(long account, DashboardFilter filter) {
        var p = parameters(account, filter);
        var summary = jdbc.queryForMap(FILTERED
                + " SELECT count(*) messages,count(*) FILTER(WHERE unread) unread,count(DISTINCT group_public_id) groups,count(*) FILTER(WHERE received_at IS NULL) undated FROM filtered",
                p);
        var mailingLists = new ArrayList<UUID>();
        var groups = jdbc.query(
                FILTERED + """
                        SELECT group_public_id,group_key,group_label,count(*) messages,count(*) FILTER(WHERE unread) unread,
                        max(received_at) latest,count(DISTINCT address) addresses,
                        CASE WHEN group_key LIKE 'address:%' THEN max(NULLIF(from_display_name,'')) END display_name,
                        CASE WHEN group_key LIKE 'address:%' THEN min(address) END exact_address,count(*) FILTER(WHERE pending_state IS NOT NULL) pending
                        FROM filtered GROUP BY group_public_id,group_key,group_label
                        ORDER BY count(*) DESC,group_key LIMIT 10 OFFSET :offset
                        """,
                p,
                (r, n) -> {
                    UUID id = uuid(r, "group_public_id");
                    String key = r.getString("group_key"), label = r.getString("group_label");
                    if (key.equals("list:" + label)) mailingLists.add(id);
                    return new Group(id, r.getString("display_name")==null?label:r.getString("display_name"), r.getLong("messages"),
                        r.getLong("unread"), instant(r, "latest"), r.getLong("addresses"),
                        groupingEvidence(key), r.getLong("pending"),r.getString("exact_address"));
                });
        groups = labelMailingLists(account, groups, mailingLists);
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

    /** Display evidence never changes list membership or overrides a saved label.
     * Read the whole received group so a search/category/page cannot hide conflicting names. */
    private List<Group> labelMailingLists(long account, List<Group> groups, List<UUID> mailingLists) {
        if (mailingLists.isEmpty()) return groups;
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
                        g.evidence(), g.pending(), g.address())
                : g).toList();
    }

    public long revision(long account) {
        Long result = jdbc.queryForObject(
                "SELECT COALESCE((SELECT grouping_revision FROM dashboard_account_settings WHERE account_id=:account),0)",
                Map.of("account", account), Long.class);
        return result;
    }

    public long classificationRevision(long account) {
        return jdbc.queryForObject("SELECT COALESCE((SELECT classification_revision FROM dashboard_account_settings WHERE account_id=:account),0)",Map.of("account",account),Long.class);
    }
    public Details details(long account, DashboardFilter filter, UUID group, int messagePage) {
        return details(account,filter,group,messagePage,null);
    }
    public Details details(long account, DashboardFilter filter, UUID group, int messagePage,String address) {
        if (messagePage < 0 || messagePage > 1_000_000)
            throw new IllegalArgumentException("Invalid message page");
        var p = parameters(account, filter).addValue("group", group).addValue("message_offset", messagePage * 10L).addValue("address",address);
        if (jdbc.queryForObject("SELECT count(*) FROM sender_groups WHERE account_id=:account AND public_id=:group", p,
                Long.class) == 0)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Sender group unavailable for this account");
        String allGroup = " FROM filtered WHERE group_public_id=:group";
        String inGroup=allGroup+" AND (CAST(:address AS text) IS NULL OR address=:address)";
        long total = jdbc.queryForObject(FILTERED + "SELECT count(*)" + inGroup, p, Long.class);
        List<Message> messages = jdbc.query(
                FILTERED + "SELECT *" + inGroup
                        + " ORDER BY received_at DESC NULLS LAST,provider_message_id LIMIT 10 OFFSET :message_offset",
                p, (r, n) -> message(r));
        var addresses = jdbc.query(
                FILTERED + "SELECT address,max(NULLIF(from_display_name,'')) display_name,count(*) messages,count(*) FILTER(WHERE unread) unread" + allGroup
                        + " GROUP BY address ORDER BY address",
                p, (r, n) -> new Address(r.getString("address"), r.getLong("messages"), r.getLong("unread"),r.getString("display_name")));
        var units = jdbc.query(
                FILTERED + "SELECT DISTINCT unit_public_id,unit_kind,unit_key,evidence_reason" + allGroup
                        + " ORDER BY unit_public_id",
                p, (r, n) -> new Unit(uuid(r, "unit_public_id"), r.getString("unit_kind"), r.getString("unit_key"),
                        r.getString("evidence_reason")));
        return new Details(messages, addresses, units, total, messagePage, revision(account),classificationRevision(account));
    }

    public List<Selected> selection(long account, DashboardFilter filter, List<UUID> groups, String address) {
        if (groups == null || groups.isEmpty())
            return List.of();
        var p = parameters(account, filter).addValue("groups", groups).addValue("address", address);
        if (jdbc.queryForObject(
                "SELECT count(*) FROM sender_groups WHERE account_id=:account AND public_id IN (:groups)", p,
                Long.class) != groups.stream().distinct().count())
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Sender group unavailable for this account");
        return jdbc.query(FILTERED
                + "SELECT dashboard_message_id,group_public_id,address FROM filtered WHERE group_public_id IN (:groups) AND (CAST(:address AS text) IS NULL OR address=:address) ORDER BY provider_message_id",
                p,
                (r, n) -> new Selected(uuid(r, "dashboard_message_id"), uuid(r, "group_public_id"),
                        r.getString("address")));
    }

    public static Message message(ResultSet r) throws SQLException {
        return new Message(uuid(r, "dashboard_message_id"), r.getString("subject_header"), r.getString("address"),
                instant(r, "received_at"), r.getBoolean("unread"), uuid(r, "group_public_id"),
                r.getString("pending_state"), r.getString("category"),r.getString("from_display_name"),r.getString("classification_override")!=null?"MANUAL":"JEV");
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
        if (key.startsWith("domain:"))
            return "Grouped by organization domain, including subdomains";
        if (key.startsWith("list:"))
            return "Grouped by a single valid List-ID";
        return key.startsWith("address:") ? "Exact sender address; no domain merge inferred"
                : "Kept separate — identity evidence is incomplete";
    }

    public record Group(UUID id, String label, long messages, long unread, Instant latest, long addresses,
            String evidence, long pending,String address) {
    }

    public record Message(UUID id, String subject, String address, Instant receivedAt, boolean unread, UUID groupId,
            String pendingState, String category,String displayName,String categorySource) {
    }

    public record Address(String address, long messages, long unread,String displayName) {
    }

    public record Unit(UUID id, String kind, String key, String reason) {
    }

    public record Selected(UUID id, UUID groupId, String address) {
    }

    public record Details(List<Message> messages, List<Address> addresses, List<Unit> units, long total, int page,
            long revision,long classificationRevision) {
    }

    public record Page(List<Group> groups, Map<String, Object> summary, Map<String, Object> coverage,
            Map<String, Object> sync, long revision, DashboardFilter filter,long classificationRevision) {
    }
}
