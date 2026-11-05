package com.tidymail.service;

/** Shared SQL dependencies; no provider calls or writes during dashboard reads. */
public final class RecommendationSql {
    private RecommendationSql() {}
    public static final String ACCOUNT_CONTEXT = "\n"+"""
account_context_raw AS MATERIALIZED (
          SELECT a.id,a.email,
            COALESCE((SELECT array_agg(address ORDER BY address) FROM dashboard_verified_aliases WHERE account_id=a.id),'{}'::text[]) aliases,
            md5(jsonb_build_array(b.complete,s.backfill_status,s.rescan_status,
                EXISTS(SELECT 1 FROM correspondent_dirty d WHERE d.account_id=a.id))::text) coverage_hash,
            CASE WHEN b.account_id IS NULL THEN 'UNKNOWN'
              WHEN b.complete AND s.backfill_status='COMPLETE' AND s.rescan_status IN ('NOT_STARTED','COMPLETE')
                AND NOT EXISTS(SELECT 1 FROM correspondent_dirty d WHERE d.account_id=a.id) THEN 'INDEXED'
              ELSE 'PARTIAL' END coverage
          FROM user_accounts a LEFT JOIN correspondent_backfill b ON b.account_id=a.id
          LEFT JOIN mailbox_sync_state s ON s.account_id=a.id WHERE a.id=:account
        ), account_context AS MATERIALIZED (
          SELECT raw.*,md5(jsonb_build_array(email,aliases,coverage_hash)::text) account_stamp FROM account_context_raw raw
        )
        """;
    /** Same message-only fingerprint as V37; explicit columns allow projection pruning in mailbox filters. */
    public static final String SEMANTIC_HASH = """
        md5(jsonb_build_array(m.dashboard_from_address,COALESCE(m.from_display_name,''),COALESCE(m.subject_header,''),
          cardinality(m.references_values)>0 OR cardinality(m.in_reply_to_values)>0,
          cardinality(m.list_unsubscribe_values)>0,m.label_evidence_known,
          m.labels && ARRAY['CATEGORY_PERSONAL'],m.labels && ARRAY['CATEGORY_PROMOTIONS'],
          m.labels && ARRAY['CATEGORY_SOCIAL'],m.labels && ARRAY['CATEGORY_UPDATES'],m.labels && ARRAY['CATEGORY_FORUMS'],
          CASE WHEN sl.variant='SNIPPET' THEN m.snippet_text END,CASE WHEN sl.variant='SNIPPET' THEN m.snippet_state END)::text)
        """;
    public static final String HASH = """
          md5(jsonb_build_array(recommendation_metadata_hash(m),a.email,a.aliases,a.coverage_hash,
              e.evidence_version,e.evidence_hash,e.facts,to_jsonb(h),to_jsonb(p),to_jsonb(c),sl.input_hash,sl.judgment,
              CASE WHEN NOT m.label_evidence_known THEN 'UNKNOWN'
                WHEN NOT m.unread THEN 'READ'
                WHEN m.received_at IS NULL OR m.received_at>CAST(:asOf AS timestamptz) THEN 'UNKNOWN'
                WHEN extract(epoch FROM(CAST(:asOf AS timestamptz)-m.received_at))>COALESCE(p.attention_days,60)*86400 THEN 'OLDER_UNREAD'
                ELSE 'RECENT_UNREAD' END)::text)
        """;
    public static final String STAMP = """
        md5(jsonb_build_array(COALESCE(v.revision,0),a.email,a.aliases,a.coverage_hash,
          e.evidence_version,e.evidence_hash,to_jsonb(h),p.revision,c.revision,sl.input_hash,sl.attempt_id,
          CASE WHEN NOT m.label_evidence_known THEN 'UNKNOWN'
            WHEN NOT m.unread THEN 'READ'
            WHEN m.received_at IS NULL OR m.received_at>CAST(:asOf AS timestamptz) THEN 'UNKNOWN'
            WHEN extract(epoch FROM(CAST(:asOf AS timestamptz)-m.received_at))>COALESCE(p.attention_days,60)*86400 THEN 'OLDER_UNREAD'
            ELSE 'RECENT_UNREAD' END)::text)
        """;
    public static final String JOINS = """
        LEFT JOIN message_evidence e ON e.account_id=m.account_id AND e.provider_message_id=m.provider_message_id
        LEFT JOIN correspondent_summary h ON h.account_id=m.account_id AND h.address=m.dashboard_from_address
        LEFT JOIN message_semantic_labels sl ON sl.account_id=m.account_id AND sl.message_id=m.dashboard_message_id
        LEFT JOIN recommendation_policies p ON p.account_id=m.account_id
        LEFT JOIN recommendation_corrections c ON c.account_id=m.account_id AND c.message_id=m.dashboard_message_id
        LEFT JOIN recommendation_message_versions v ON v.account_id=m.account_id AND v.message_id=m.dashboard_message_id
        LEFT JOIN message_recommendations saved ON saved.account_id=m.account_id AND saved.message_id=m.dashboard_message_id
        """;
    public static final String CURRENT = "(saved.engine_version='"+MessageRecommendation.VERSION
        +"' AND saved.adapter_version='"+RecommendationService.ADAPTER_VERSION+"' AND "+"""
        saved.metadata_revision=COALESCE(v.revision,0) AND saved.account_stamp=a.account_stamp
        AND saved.policy_source_revision=COALESCE(p.revision,0)
        AND ROW(saved.history_revision,saved.history_sent,saved.history_received,saved.history_sent_replies,saved.history_received_replies,
                saved.history_last_sent,saved.history_last_received)
          IS NOT DISTINCT FROM ROW(COALESCE(h.revision,0),COALESCE(h.sent_count,0),COALESCE(h.received_count,0),COALESCE(h.sent_reply_count,0),COALESCE(h.received_reply_count,0),h.last_sent_at,h.last_received_at)
        AND saved.attention_source=CASE WHEN NOT m.label_evidence_known THEN 'UNKNOWN'
            WHEN NOT m.unread THEN 'READ'
            WHEN m.received_at IS NULL OR m.received_at>CAST(:asOf AS timestamptz) THEN 'UNKNOWN'
            WHEN extract(epoch FROM(CAST(:asOf AS timestamptz)-m.received_at))>COALESCE(p.attention_days,60)*86400 THEN 'OLDER_UNREAD'
            ELSE 'RECENT_UNREAD' END)
        """;
}
