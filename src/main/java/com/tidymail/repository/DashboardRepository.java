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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DashboardRepository {
    private static final Pattern BRAND_SEPARATORS = Pattern.compile("[^a-z0-9]");
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
            AND (m.provider<>'microsoft' OR NOT(m.labels && ARRAY['SENT','OUTLOOK_EXCLUDED']))
            AND ('INBOX'=ANY(m.labels) OR NOT('SENT'=ANY(m.labels)) OR
            """ + DELIVERY + ")";
    // Read pending state once. A scalar initplan keeps its map out of the row joins
    // and avoids a correlated target query for every matching message.
    public static final String CATEGORY = "replace(COALESCE(m.classification_override,CASE WHEN m.classification_hash=m.classification_evidence_hash AND m.classification_version='"
            + com.tidymail.service.JevClassifier.VERSION
            + "' THEN m.classification_category END,'UNCLASSIFIED'),'UNCERTAIN','OTHER')";
    private static final String REQUESTED_SEMANTIC_MATCH="(requested.label=ANY(sl.labels) AND sl.input_hash="+RecommendationSql.SEMANTIC_HASH+")";
    private static final String LABELING_CATEGORY="COALESCE(m.classification_override,CASE WHEN sl.input_hash="+RecommendationSql.SEMANTIC_HASH
        +" THEN NULLIF(sl.purpose,'UNKNOWN') END,"+CATEGORY+")";
    private static final String LOCAL_CURRENT = "(saved.local_rules_version='"
            +com.tidymail.service.LocalMailLabels.VERSION+"' AND saved.metadata_revision=COALESCE(v.revision,0))";
    private static final String LOCAL_LABEL = "(" + LOCAL_CURRENT + " AND jsonb_exists(saved.local_label_reasons,requested.label))";
    private static final String LABEL_MATCH = """
            CASE requested.label
                      WHEN 'CONTACT' THEN COALESCE(h.sent_count,0)>0
                      WHEN 'LARGE_MESSAGE' THEN m.size_estimate>1000000
                      WHEN 'SENT' THEN 'SENT'=ANY(m.labels)
                      WHEN 'HAS_ATTACHMENTS' THEN m.attachment_state='PRESENT'
                      WHEN 'GMAIL_PROMOTIONS' THEN m.provider='google' AND m.label_evidence_known
                        AND 'CATEGORY_PROMOTIONS'=ANY(m.labels)
                      WHEN 'OLDER_DOCUMENT_COMMENT' THEN """+LOCAL_CURRENT+"""
                        AND jsonb_exists(saved.local_label_reasons,'DOCUMENT_COMMENT')
                        AND m.received_at<CAST(:asOf AS timestamptz)-interval '720 hours'
                      WHEN 'PAST_EVENT' THEN """+LOCAL_CURRENT+"""
                        AND saved.local_calendar_end<=:asOf
                      WHEN 'CALENDAR_INVITE' THEN """+LOCAL_LABEL+" OR "+REQUESTED_SEMANTIC_MATCH+"""
                        OR COALESCE(jsonb_path_exists(e.facts,'$.parts[*] ? (@.calendar == true)'),false)
                        OR COALESCE(jsonb_array_length(e.facts->'calendars'),0)>0
                        OR ("""+RecommendationSql.CURRENT+"""
                          AND saved.result->>'purpose'='CALENDAR_INVITE')
                      WHEN 'UNCLASSIFIED' THEN requested.include_category AND ("""+LABELING_CATEGORY+"""
                        )='UNCLASSIFIED' AND NOT COALESCE(("""+LOCAL_CURRENT+"""
                        AND saved.local_label_reasons<>'{}'::jsonb),false)
                      ELSE (m.provider='google' AND m.label_evidence_known
                          AND ('Label_'||substring(requested.label from '^GMAIL_LABEL_([0-9]+)$'))=ANY(m.labels))
                        OR """+LOCAL_LABEL+"""
                        OR (requested.include_category AND ("""+LABELING_CATEGORY+"""
                        ) = CASE requested.label WHEN 'HUMAN_CORRESPONDENCE' THEN 'PERSONAL' ELSE requested.label END)
                        OR ("""+RecommendationSql.CURRENT+"""
                        AND (jsonb_exists(saved.result->'facets',requested.label) OR saved.result->>'purpose'=
                          CASE requested.label WHEN 'HUMAN_CORRESPONDENCE' THEN 'PERSONAL' ELSE requested.label END)) OR """+REQUESTED_SEMANTIC_MATCH+"""
                        END
        """;
    private static final String RECOMMENDATION_FILTERS = """
                    AND (:recommendation='' OR CASE WHEN saved.message_id IS NULL THEN 'PENDING'
                      WHEN """+RecommendationSql.CURRENT+"""
                      THEN saved.result->>'action' ELSE 'STALE' END=:recommendation)
                    AND (:importance='' OR ("""+RecommendationSql.CURRENT+"""
                      AND saved.result->>'importance'=:importance))
                    AND (:label='' OR EXISTS (
                      SELECT FROM (VALUES (CAST(:label AS text), false)) requested(label, include_category) WHERE
                      """+LABEL_MATCH+"""
                      ))
                    AND (:labels='' OR (SELECT CASE WHEN :match='any'
                      THEN bool_or(COALESCE(matches, false)) ELSE bool_and(COALESCE(matches, false)) END
                      FROM (SELECT
                      """+LABEL_MATCH+"""
                        AS matches FROM (SELECT label,true AS include_category FROM unnest(string_to_array(:labels, ',')) label) requested) matching))
                    AND (NOT :excludeProtectedShopping OR NOT ('SHOPPING'=ANY(string_to_array(:labels, ','))) OR NOT EXISTS (
                      SELECT FROM (VALUES ('RECEIPT',true),('SECURITY',true)) requested(label,include_category)
                      WHERE
                      """+LABEL_MATCH+"""
                    ))
        """;
    private static final String ADDRESS_MEMBERSHIP = """
                    LEFT JOIN sender_address_units split ON split.account_id=link.account_id
                      AND split.source_unit_id=link.unit_id AND split.address=m.dashboard_from_address AND split.active
                    """;
    private static final String EFFECTIVE_UNIT = "COALESCE(split.unit_id, link.unit_id)";
    private static final String GROUPING_LOOKUP = """
                    JOIN LATERAL (
                      SELECT g.public_id group_public_id,g.group_key,g.label group_label,
                        u.public_id unit_public_id,u.kind unit_kind,u.canonical_key unit_key,u.evidence_reason
                      FROM sender_units u JOIN sender_group_members member ON member.account_id=u.account_id AND member.unit_id=u.id
                      JOIN sender_groups g ON g.account_id=member.account_id AND g.id=member.group_id
                      WHERE u.account_id=link.account_id AND u.id="""+EFFECTIVE_UNIT+"""
                        OFFSET 0
                    ) identity ON true
        """;
    private static final String COMPOSITION_KEY = """
            CASE WHEN split_part(group_key,':',1) IN ('domain','organization','classified-domain')
              THEN 'domain:'||split_part(group_key,':',2) ELSE 'group:'||group_public_id::text END
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
            ), candidates AS (
            SELECT m.*,"""
            + LABELING_CATEGORY + """
                      AS category,identity.group_public_id,identity.group_key,identity.group_label,
                      identity.unit_public_id,identity.unit_kind,identity.unit_key,identity.evidence_reason,
                      COALESCE(m.dashboard_from_address,m.from_address,'Unknown sender') AS address,
                      (SELECT states FROM pending)->>m.dashboard_message_id::text AS pending_state
                    FROM message_metadata m JOIN account_context a ON a.id=m.account_id
                    """+RecommendationSql.JOINS+"""
                    JOIN message_sender_units link ON link.account_id=m.account_id AND link.metadata_id=m.id
                    """+ADDRESS_MEMBERSHIP+GROUPING_LOOKUP+"""
                    WHERE m.account_id=:account AND
                    """ + "((:scope='indexed' AND m.provider='google') OR " + RECEIVED + """
                    OR (:suggestion='large' AND m.provider='google' AND 'SENT'=ANY(m.labels)
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
            , filtered AS (
              SELECT * FROM candidates WHERE :composition='' OR ("""+COMPOSITION_KEY+"""
                )=:composition)

                    """;

    private static final String KEYED_BASIC_FILTERED = FILTERED
            .replace(RecommendationSql.ACCOUNT_CONTEXT+", pending", " pending")
            .replace("JOIN account_context a ON a.id=m.account_id", "JOIN user_accounts a ON a.id=m.account_id")
            .replace(RecommendationSql.JOINS, "")
            .replace(RECOMMENDATION_FILTERS, "")
            .replace(LABELING_CATEGORY, CATEGORY);
    private static final String BASIC_FILTERED = KEYED_BASIC_FILTERED
            .replace(GROUPING_LOOKUP,"""
                JOIN sender_units u ON u.account_id=link.account_id AND u.id="""+EFFECTIVE_UNIT+"""

                JOIN sender_group_members member ON member.account_id=u.account_id AND member.unit_id=u.id
                JOIN sender_groups g ON g.account_id=member.account_id AND g.id=member.group_id
                """)
            .replace("identity.group_public_id","g.public_id AS group_public_id")
            .replace("identity.group_key","g.group_key").replace("identity.group_label,","g.label AS group_label,")
            .replace("identity.unit_public_id","u.public_id AS unit_public_id").replace("identity.unit_kind","u.kind AS unit_kind")
            .replace("identity.unit_key","u.canonical_key AS unit_key").replace("identity.evidence_reason","u.evidence_reason")
            .replace("lower(identity.group_label)","lower(g.label)");
    private static final String CURRENT_ACTION_FILTERED = FILTERED.replace(RECOMMENDATION_FILTERS,
        "AND saved.result->>'action'=:recommendation AND "+RecommendationSql.CURRENT+"\n"
        +"AND (:importance='' OR saved.result->>'importance'=:importance)\n"
        +"AND (:label='' OR jsonb_exists(saved.result->'facets',:label) OR saved.result->>'purpose'="
        +"CASE :label WHEN 'HUMAN_CORRESPONDENCE' THEN 'PERSONAL' ELSE :label END)\n")
        .replace(LABELING_CATEGORY,"COALESCE(m.classification_override,NULLIF(saved.result->>'purpose','UNKNOWN'),"+CATEGORY+")");
    /** Avoid enrichment joins entirely on the ordinary browsing path. */
    public String filtered(long account, DashboardFilter filter) {
        if (filter.category().isEmpty() && filter.recommendation().isEmpty() && filter.importance().isEmpty()
                && filter.label().isEmpty() && filter.labels().isEmpty()) {
            // Outlook's smaller index can make the ordinary join rescan the mailbox for each sender.
            // The keyed lookup avoids that plan while the ordinary join is faster on Gmail.
            var provider = jdbc.queryForObject("SELECT provider FROM user_accounts WHERE id=:account",
                Map.of("account", account), String.class);
            return "microsoft".equals(provider) ? KEYED_BASIC_FILTERED : BASIC_FILTERED;
        }
        if(!filter.recommendation().isEmpty() && !java.util.Set.of("PENDING","STALE").contains(filter.recommendation()) && filter.labels().isEmpty()) return CURRENT_ACTION_FILTERED;
        return FILTERED;
    }

    public MapSqlParameterSource parameters(long account, DashboardFilter filter) {
        return new MapSqlParameterSource("account", account).addValue("scope", filter.scope())
                .addValue("suggestion", filter.suggestion()).addValue("category", filter.category())
                .addValue("recommendation",filter.recommendation()).addValue("importance",filter.importance()).addValue("label",filter.label())
                .addValue("labels",filter.labels()).addValue("match",filter.match())
                .addValue("excludeProtectedShopping",filter.excludeProtectedShopping())
                .addValue("composition",filter.composition())
                .addValue("asOf",timestamp(Instant.now()))
                .addValue("q", filter.q())
                .addValue("start", timestamp(filter.startInstant()))
                .addValue("end", timestamp(filter.endInstant())).addValue("offset", filter.page() * 10L);
    }

    private static final String GROUP_COUNTS = """
                , group_counts AS MATERIALIZED (
                  SELECT group_public_id,group_key,group_label,count(*) messages,count(*) FILTER(WHERE unread) unread,
                    max(received_at) latest,count(DISTINCT address) addresses,
                    count(*) FILTER(WHERE 'INBOX'=ANY(labels)) inbox,
                    COALESCE(sum(size_estimate),0) known_bytes,count(*) FILTER(WHERE size_estimate IS NULL) unknown_sizes,
                    count(*) FILTER(WHERE received_at IS NULL) undated,
                    CASE WHEN group_key LIKE 'address:%' THEN min(address) END exact_address,
                    count(*) FILTER(WHERE pending_state IS NOT NULL) pending
                  FROM filtered GROUP BY group_public_id,group_key,group_label
                )
                """;

    public Page page(long account, DashboardFilter filter) {
        var p = parameters(account, filter);
        var summary = new java.util.LinkedHashMap<String,Object>();
        var collected = new ArrayList<Group>();
        // Count and page one grouped snapshot instead of scanning the enriched mailbox twice.
        String order = switch (filter.sort()) {
            case "unread" -> "unread DESC,";
            case "latest" -> "latest DESC NULLS LAST,";
            case "size" -> "known_bytes DESC,";
            default -> "";
        };
        jdbc.query(filtered(account, filter)+GROUP_COUNTS+"""
                , totals AS (
                  SELECT COALESCE(sum(messages),0)::bigint messages,COALESCE(sum(unread),0)::bigint unread,count(*) groups,
                    COALESCE(sum(undated),0)::bigint undated,COALESCE(sum(known_bytes),0) known_bytes,
                    COALESCE(sum(unknown_sizes),0)::bigint unknown_sizes FROM group_counts
                ), page_groups AS (
                  SELECT * FROM group_counts ORDER BY
                """+order+"""
                  messages DESC,group_key LIMIT 10 OFFSET :offset
                ) SELECT totals.messages total_messages,totals.unread total_unread,totals.groups total_groups,
                    totals.undated total_undated,totals.known_bytes total_known_bytes,totals.unknown_sizes total_unknown_sizes,page_groups.*
                  FROM totals LEFT JOIN page_groups ON true ORDER BY
                """+order+" messages DESC,group_key",p,(org.springframework.jdbc.core.RowCallbackHandler)r->{
            for(String key:List.of("messages","unread","groups","undated","known_bytes","unknown_sizes"))summary.put(key,r.getObject("total_"+key));
            if (uuid(r,"group_public_id") == null) return;
            collected.add(group(r));
        });
        var names = displayNames(account, collected.stream().map(Group::id).toList());
        var groups = collected.stream().map(g -> names.containsKey(g.id())
                ? new Group(g.id(), names.get(g.id()), g.messages(), g.unread(), g.latest(), g.addresses(),
                        g.evidence(), g.pending(), g.address(), g.knownBytes(), g.unknownSizes(), g.inbox())
                : g).toList();
        var coverage = jdbc.queryForMap(
                """
                        SELECT count(*) indexed,min(m.received_at) oldest,max(m.received_at) newest,
                          max(m.last_synced_at) as_of,
                          count(*) FILTER(WHERE NOT m.label_evidence_known AND cardinality(m.labels)=0) unknown_labels,
                          count(*) FILTER(WHERE m.dashboard_from_address IS NULL) unknown_identity,
                          count(*) FILTER(WHERE m.provider='microsoft' AND m.size_estimate IS NULL
                            AND (""" + RECEIVED + """
                            )) outlook_unknown_sizes,
                          count(*) FILTER(WHERE 'SENT'=ANY(m.labels) AND NOT('INBOX'=ANY(m.labels)) AND NOT (
                        """ + DELIVERY
                        + """
                                  )) ambiguous_sent FROM message_metadata m JOIN user_accounts a ON a.id=m.account_id WHERE m.account_id=:account
                                """,
                p);
        Map<String, Object> state = jdbc.queryForMap(
                """
                        SELECT a.email,a.provider,a.oauth_status,s.lifecycle,s.backfill_status,s.initial_batch_completed_at,
                          s.incremental_last_success_at,s.error_code FROM user_accounts a LEFT JOIN mailbox_sync_state s ON s.account_id=a.id
                        WHERE a.id=:account
                        """,
                p);
        long revision = revision(account);
        return new Page(groups, summary, coverage, state, revision, filter, classificationRevision(account));
    }

    public List<Composition> inboxComposition(long account, DashboardFilter filter) {
        var inbox = filter.inbox();
        var bars = jdbc.query(filtered(account, inbox) + GROUP_COUNTS + """
                , buckets AS (
                  SELECT ("""+COMPOSITION_KEY+"""
                  ) AS key, group_label, messages FROM group_counts
                ) SELECT key, CASE WHEN key LIKE 'domain:%' THEN substring(key FROM 8)
                    ELSE min(group_label) END label, sum(messages) AS messages
                  FROM buckets GROUP BY key ORDER BY messages DESC,key LIMIT 10
                """, parameters(account, inbox), (r, n) -> new Composition(
                    r.getString("key"),r.getString("label"),r.getLong("messages")));
        var addresses = bars.stream().filter(bar -> bar.key().startsWith("group:"))
            .map(bar -> UUID.fromString(bar.key().substring(6))).toList();
        var names = displayNames(account, addresses);
        return bars.stream().map(bar -> bar.key().startsWith("group:")
                ? new Composition(bar.key(), names.getOrDefault(UUID.fromString(bar.key().substring(6)), bar.label()),
                    bar.messages())
                : bar).toList();
    }

    public record Composition(String key, String label, long messages) {}

    private static Group group(ResultSet r) throws SQLException {
        return new Group(uuid(r,"group_public_id"), r.getString("group_label"), r.getLong("messages"),
                r.getLong("unread"), instant(r,"latest"), r.getLong("addresses"),
                groupingEvidence(r.getString("group_key")), r.getLong("pending"), r.getString("exact_address"),
                r.getLong("known_bytes"), r.getLong("unknown_sizes"), r.getLong("inbox"));
    }

    /**
     * Read the whole indexed group so filters cannot hide conflicting names.
     * Combine and Split labels are saved corrections.
     */
    private Map<UUID, String> displayNames(long account, List<UUID> groups) {
        if (groups.isEmpty()) return Map.of();
        var names = new HashMap<UUID, String>();
        var p = new MapSqlParameterSource("account", account).addValue("groups", groups);
        jdbc.query("""
                WITH evidence AS (
                SELECT g.public_id, array_agg(DISTINCT sender.name ORDER BY sender.name)
                    FILTER (WHERE sender.name IS NOT NULL) AS saved_names,
                  CASE
                    WHEN split_part(g.group_key,':',1) IN ('domain','organization','classified-domain')
                      THEN split_part(g.group_key,':',2)
                    WHEN min(sender.domain)=max(sender.domain) THEN min(sender.domain)
                  END AS sender_host,
                  CASE
                  WHEN split_part(g.group_key,':',1) IN ('domain','organization','classified-domain')
                    THEN split_part(g.group_key,':',2)
                  WHEN g.group_key LIKE 'address:%' THEN substring(g.group_key FROM 9)
                  WHEN min(sender.address)=max(sender.address) THEN min(sender.address)
                  WHEN min(sender.domain)=max(sender.domain) THEN min(sender.domain)
                  WHEN min(sender.address) IS NULL THEN 'Unknown sender'
                  ELSE 'Multiple senders'
                END AS display_name
                FROM message_metadata m
                JOIN message_sender_units link ON link.account_id=m.account_id AND link.metadata_id=m.id
                """+ADDRESS_MEMBERSHIP+"""
                JOIN sender_group_members member ON member.account_id=link.account_id AND member.unit_id="""+EFFECTIVE_UNIT+"""
                JOIN sender_groups g ON g.account_id=member.account_id AND g.id=member.group_id
                CROSS JOIN LATERAL (
                  SELECT NULLIF(btrim(m.from_display_name),'') AS name,
                    COALESCE(NULLIF(m.dashboard_from_address,''),NULLIF(m.from_address,'')) AS address,
                    NULLIF(lower(split_part(COALESCE(NULLIF(m.dashboard_from_address,''),m.from_address),'@',2)),'') AS domain
                ) sender
                WHERE m.account_id=:account AND g.public_id IN (:groups)
                  AND g.group_key NOT LIKE 'manual:%'
                  AND NOT (g.group_key LIKE 'separate:%' AND split_part(g.group_key,':',3) IN ('first','rest'))
                GROUP BY g.public_id,g.group_key
                )
                SELECT public_id,saved_names,display_name,
                  CASE WHEN cardinality(saved_names)>1
                    THEN split_part(dashboard_organization_domain(sender_host),'.',1) END AS brand_key
                FROM evidence
                """, p, (org.springframework.jdbc.core.RowCallbackHandler) r -> {
                    var saved = r.getArray("saved_names");
                    names.put(uuid(r, "public_id"), senderName(saved == null ? new String[0] : (String[]) saved.getArray(),
                            r.getString("brand_key"), r.getString("display_name")));
                });
        return names;
    }

    private static String senderName(String[] savedNames, String brandKey, String fallback) {
        if (savedNames.length == 1) return savedNames[0];
        if (brandKey == null) return fallback;
        String normalizedKey = normalizedBrand(brandKey);
        String brand = null;
        for (String name : savedNames) {
            if (!normalizedKey.equals(normalizedBrand(name))) continue;
            if (hasMixedCase(name)) return name;
            if (brand == null) brand = name;
        }
        if (brand == null) return fallback;

        // A longer saved name can preserve the brand's spelling when the exact name is all caps.
        for (String name : savedNames) {
            int length = brand.length();
            if (name.length() > length && Character.isWhitespace(name.charAt(length))
                    && name.regionMatches(true, 0, brand, 0, length)) {
                String prefix = name.substring(0, length);
                if (hasMixedCase(prefix)) return prefix;
            }
        }
        return brand;
    }

    private static String normalizedBrand(String name) {
        return BRAND_SEPARATORS.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private static boolean hasMixedCase(String name) {
        return name.chars().anyMatch(Character::isUpperCase) && name.chars().anyMatch(Character::isLowerCase);
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
        requireGroup(p);
        String inGroup=" FROM filtered WHERE group_public_id=:group"+" AND (CAST(:address AS text) IS NULL OR address=:address)" + (unreadOnly ? " AND unread" : "");
        String filtered = filtered(account, filter);
        long total = jdbc.queryForObject(filtered + "SELECT count(*)" + inGroup, p, Long.class);
        List<Message> messages = jdbc.query(
                filtered + """
                SELECT filtered.*,
                (SELECT folder_path FROM outlook_folders o WHERE o.account_id=filtered.account_id
                    AND o.folder_id=filtered.outlook_folder_id) outlook_folder,
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
        return new Details(messages, total, revision(account),classificationRevision(account));
    }

    public Addresses addresses(long account, DashboardFilter filter, UUID group) {
        var p = parameters(account, filter).addValue("group", group);
        requireGroup(p);
        var addresses = jdbc.query(
                filtered(account, filter) + "SELECT address,max(NULLIF(from_display_name,'')) display_name,count(*) messages,count(*) FILTER(WHERE unread) unread FROM filtered WHERE group_public_id=:group"
                        + " GROUP BY address ORDER BY address",
                p, (r, n) -> new Address(r.getString("address"), r.getLong("messages"), r.getLong("unread"),r.getString("display_name")));
        return new Addresses(addresses, revision(account), classificationRevision(account));
    }

    public Grouping grouping(long account, UUID group) {
        var p = new MapSqlParameterSource("account", account).addValue("group", group);
        requireGroup(p);
        var units = jdbc.query("""
                SELECT u.public_id,u.kind,u.canonical_key,u.evidence_reason,
                    COALESCE(array_agg(DISTINCT m.dashboard_from_address ORDER BY m.dashboard_from_address)
                        FILTER (WHERE m.dashboard_from_address IS NOT NULL), ARRAY[]::text[]) addresses
                FROM message_metadata m
                JOIN message_sender_units link ON link.account_id=m.account_id AND link.metadata_id=m.id
                """+ADDRESS_MEMBERSHIP+"""
                JOIN sender_group_members member ON member.account_id=link.account_id AND member.unit_id="""+EFFECTIVE_UNIT+"""
                JOIN sender_units u ON u.account_id=member.account_id AND u.id=member.unit_id
                JOIN sender_groups g ON g.account_id=member.account_id AND g.id=member.group_id
                WHERE m.account_id=:account AND g.public_id=:group
                GROUP BY u.id ORDER BY u.canonical_key
                """, p, (r,n) -> new Unit(uuid(r,"public_id"),r.getString("kind"),r.getString("canonical_key"),
                    r.getString("evidence_reason"),List.of((String[])r.getArray("addresses").getArray())));
        return new Grouping(units, revision(account), classificationRevision(account));
    }

    private void requireGroup(MapSqlParameterSource parameters) {
        if (jdbc.queryForObject("SELECT count(*) FROM sender_groups WHERE account_id=:account AND public_id=:group", parameters,
                Long.class) == 0)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Sender group unavailable for this account");
    }

    public List<Selected> selection(long account, DashboardFilter filter, List<UUID> groups, String address) {
        return selection(account, filter, groups, address, false);
    }
    public List<Selected> selection(long account, DashboardFilter filter, List<UUID> groups, String address, boolean unreadOnly) {
        return selection(account,filter,groups,address,unreadOnly,false);
    }

    public List<Selected> selection(long account, DashboardFilter filter, List<UUID> groups, String address,
            boolean unreadOnly, boolean allMatching) {
        if (!allMatching && (groups == null || groups.isEmpty()))
            return List.of();
        var p = parameters(account, filter).addValue("groups", groups).addValue("address", address);
        if (!allMatching && jdbc.queryForObject(
                "SELECT count(*) FROM sender_groups WHERE account_id=:account AND public_id IN (:groups)", p,
                Long.class) != groups.stream().distinct().count())
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Sender group unavailable for this account");
        return jdbc.query(filtered(account, filter)
                + "SELECT dashboard_message_id,group_public_id,address FROM filtered WHERE (CAST(:address AS text) IS NULL OR address=:address)"
                + (allMatching ? "" : " AND group_public_id IN (:groups)")
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
                r.getObject("size_estimate",Long.class),r.getString("attachment_state"),r.getString("snippet_text"),r.getString("snippet_state"),pattern,java.util.Arrays.asList((String[])r.getArray("labels").getArray()).contains("SENT"),r.getBoolean("contact"),r.getBoolean("calendar_invite"),
                r.getString("outlook_folder"),r.getArray("outlook_categories")==null?List.of():
                    List.of((String[])r.getArray("outlook_categories").getArray()),r.getString("outlook_web_link"));
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
            String evidence, long pending,String address,long knownBytes,long unknownSizes,long inbox) {
    }

    public record Message(UUID id, String subject, String address, Instant receivedAt, boolean unread, UUID groupId,
            String pendingState, String category,String displayName,String categorySource,
            Long sizeEstimate,String attachmentState,String snippet,String snippetState,String reviewedPattern,boolean sent,boolean contact,boolean calendarInvite,
            String outlookFolder,List<String> outlookCategories,String outlookWebLink) {
    }

    public record Address(String address, long messages, long unread,String displayName) {
    }

    public record Unit(UUID id, String kind, String key, String reason, List<String> addresses) {
    }

    public record Selected(UUID id, UUID groupId,  String address) {
    }

    public record Details(List<Message> messages, long total,
            long revision,long classificationRevision, java.util.List<com.tidymail.service.RecommendationService.View> recommendations) {
        public Details(List<Message> messages,long total,long revision,long classificationRevision) {
            this(messages,total,revision,classificationRevision,List.of());
        }
        public Details withRecommendations(java.util.List<com.tidymail.service.RecommendationService.View> views) {
            return new Details(messages,total,revision,classificationRevision,views);
        }
    }

    public record Addresses(List<Address> addresses, long revision, long classificationRevision) {}

    public record Grouping(List<Unit> units, long revision, long classificationRevision) {}

    public record Page(List<Group> groups, Map<String, Object> summary, Map<String, Object> coverage,
            Map<String, Object> sync, long revision, DashboardFilter filter,long classificationRevision) {
    }
}
