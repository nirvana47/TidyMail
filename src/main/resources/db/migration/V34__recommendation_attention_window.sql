-- Local policy only: read/older financial mail may be disposable; recent unread consequential mail is held.
ALTER TABLE recommendation_policies
 ADD COLUMN attention_days INTEGER NOT NULL DEFAULT 60 CHECK(attention_days BETWEEN 1 AND 365),
 ADD COLUMN trash_read_or_old_bills BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE message_recommendations ADD COLUMN next_evaluation_at TIMESTAMPTZ;
CREATE INDEX recommendation_time_due ON message_recommendations(account_id,next_evaluation_at,message_id)
 WHERE next_evaluation_at IS NOT NULL;

CREATE OR REPLACE FUNCTION recommendation_policy_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 NEW.revision=OLD.revision+CASE WHEN ROW(NEW.trash_statements,NEW.disposable_bulk,NEW.attention_days,NEW.trash_read_or_old_bills)
 IS DISTINCT FROM ROW(OLD.trash_statements,OLD.disposable_bulk,OLD.attention_days,OLD.trash_read_or_old_bills) THEN 1 ELSE 0 END;
 RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION recommendation_metadata_hash(m message_metadata) RETURNS TEXT LANGUAGE sql IMMUTABLE AS $$
 SELECT md5(jsonb_build_array(m.provider,m.provider_message_id,m.dashboard_message_id,
 m.labels,m.label_evidence_known,m.dashboard_from_address,m.from_address,m.from_display_name,
 m.subject_header,m.received_at,m.size_estimate,m.to_values,m.cc_values,m.bcc_values,
 m.delivered_to_values,m.in_reply_to_values,m.references_values,m.list_unsubscribe_values,
 m.snippet_text,m.snippet_state,m.attachment_state,m.attachment_source,m.attachment_observed_at,
 m.classification_override,m.classification_category,m.classification_kind,m.classification_stream,
 m.classification_confidence,m.classification_hash,m.classification_evidence_hash,m.classification_version,
 m.classification_judgments,m.unread)::text)
$$;
CREATE TRIGGER recommendation_read_state_changes AFTER UPDATE OF unread ON message_metadata
 FOR EACH ROW WHEN(OLD.unread IS DISTINCT FROM NEW.unread) EXECUTE FUNCTION recommendation_message_changed();
