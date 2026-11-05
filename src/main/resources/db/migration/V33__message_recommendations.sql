-- Local composition only. No paid-work enqueue, saved-category rewrite or historical result build.
CREATE TABLE recommendation_policies (
 account_id BIGINT PRIMARY KEY REFERENCES user_accounts(id) ON DELETE CASCADE,
 revision BIGINT NOT NULL DEFAULT 1 CHECK(revision>0),
 trash_statements BOOLEAN NOT NULL DEFAULT true,
 disposable_bulk TEXT[] NOT NULL DEFAULT ARRAY['MARKETING','NEWSLETTER','SOCIAL'],
 CHECK(disposable_bulk <@ ARRAY['MARKETING','NEWSLETTER','SOCIAL']::text[] AND array_position(disposable_bulk,NULL) IS NULL)
);
CREATE TABLE recommendation_corrections (
 account_id BIGINT NOT NULL,
 message_id UUID NOT NULL,
 revision BIGINT NOT NULL DEFAULT 1 CHECK(revision>0),
 action TEXT CHECK(action IN ('KEEP_IN_INBOX','KEEP_STORED','ARCHIVE','SUGGEST_TRASH','REVIEW')),
 importance TEXT CHECK(importance IN ('IMPORTANT','ROUTINE','UNKNOWN')),
 PRIMARY KEY(account_id,message_id),
 FOREIGN KEY(account_id,message_id) REFERENCES message_metadata(account_id,dashboard_message_id) ON DELETE CASCADE
);
CREATE TABLE message_recommendations (
 account_id BIGINT NOT NULL,
 message_id UUID NOT NULL,
 source_hash TEXT NOT NULL,
 policy_revision TEXT NOT NULL,
 engine_version TEXT NOT NULL,
 adapter_version TEXT NOT NULL,
 result JSONB NOT NULL,
 assessment JSONB NOT NULL,
 relationship JSONB NOT NULL,
 evaluated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(account_id,message_id),
 FOREIGN KEY(account_id,message_id) REFERENCES message_metadata(account_id,dashboard_message_id) ON DELETE CASCADE
);
CREATE TABLE recommendation_dirty (
 account_id BIGINT NOT NULL,
 message_id UUID NOT NULL,
 queued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(account_id,message_id),
 FOREIGN KEY(account_id,message_id) REFERENCES message_metadata(account_id,dashboard_message_id) ON DELETE CASCADE
);
CREATE INDEX recommendation_dirty_order ON recommendation_dirty(account_id,queued_at,message_id);
CREATE TABLE recommendation_backfill (
 account_id BIGINT PRIMARY KEY REFERENCES user_accounts(id) ON DELETE CASCADE,
 last_message_id BIGINT NOT NULL DEFAULT 0,
 complete BOOLEAN NOT NULL DEFAULT false,
 coverage_hash TEXT
);
CREATE TABLE recommendation_address_dirty (
 account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
 address TEXT NOT NULL,
 last_message_id BIGINT NOT NULL DEFAULT 0,
 queued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(account_id,address)
);
CREATE INDEX recommendation_exact_address ON message_metadata(account_id,dashboard_from_address,id);

-- Ignore read state/sync timestamps. A semantic candidate's inputs, not its sync frequency, determine freshness.
CREATE FUNCTION recommendation_metadata_hash(m message_metadata) RETURNS TEXT LANGUAGE sql IMMUTABLE AS $$
 SELECT md5(jsonb_build_array(m.provider,m.provider_message_id,m.dashboard_message_id,
 m.labels,m.label_evidence_known,m.dashboard_from_address,m.from_address,m.from_display_name,
 m.subject_header,m.received_at,m.size_estimate,m.to_values,m.cc_values,m.bcc_values,
 m.delivered_to_values,m.in_reply_to_values,m.references_values,m.list_unsubscribe_values,
 m.snippet_text,m.snippet_state,m.attachment_state,m.attachment_source,m.attachment_observed_at,
 m.classification_override,m.classification_category,m.classification_kind,m.classification_stream,
 m.classification_confidence,m.classification_hash,m.classification_evidence_hash,m.classification_version,
 m.classification_judgments)::text)
$$;
CREATE FUNCTION recommendation_message_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' AND recommendation_metadata_hash(NEW)=recommendation_metadata_hash(OLD) THEN RETURN NEW; END IF;
 INSERT INTO recommendation_dirty(account_id,message_id) VALUES(NEW.account_id,NEW.dashboard_message_id) ON CONFLICT DO NOTHING;
 RETURN NEW;
END $$;
CREATE TRIGGER recommendation_message_changes AFTER INSERT OR UPDATE OF
 labels,label_evidence_known,dashboard_from_address,from_address,from_display_name,subject_header,received_at,size_estimate,
 to_values,cc_values,bcc_values,delivered_to_values,in_reply_to_values,references_values,list_unsubscribe_values,
 snippet_text,snippet_state,attachment_state,attachment_source,attachment_observed_at,classification_override,
 classification_category,classification_kind,classification_stream,classification_confidence,classification_hash,
 classification_evidence_hash,classification_version,classification_judgments ON message_metadata
 FOR EACH ROW EXECUTE FUNCTION recommendation_message_changed();

CREATE FUNCTION recommendation_evidence_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' AND ROW(NEW.evidence_version,NEW.evidence_hash,NEW.facts) IS NOT DISTINCT FROM
                          ROW(OLD.evidence_version,OLD.evidence_hash,OLD.facts) THEN RETURN NULL; END IF;
 INSERT INTO recommendation_dirty(account_id,message_id)
 SELECT account_id,dashboard_message_id FROM message_metadata
 WHERE account_id=COALESCE(NEW.account_id,OLD.account_id)
 AND provider_message_id=COALESCE(NEW.provider_message_id,OLD.provider_message_id)
 ON CONFLICT DO NOTHING;
 RETURN NULL;
END $$;
CREATE TRIGGER recommendation_evidence_changes AFTER INSERT OR UPDATE OR DELETE ON message_evidence
 FOR EACH ROW EXECUTE FUNCTION recommendation_evidence_changed();

CREATE FUNCTION recommendation_correction_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 NEW.revision=OLD.revision+CASE WHEN ROW(NEW.action,NEW.importance) IS DISTINCT FROM ROW(OLD.action,OLD.importance) THEN 1 ELSE 0 END;
 RETURN NEW;
END $$;
CREATE TRIGGER recommendation_correction_versions BEFORE UPDATE ON recommendation_corrections
 FOR EACH ROW EXECUTE FUNCTION recommendation_correction_version();
CREATE FUNCTION recommendation_correction_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' AND NEW.revision=OLD.revision THEN RETURN NULL; END IF;
 INSERT INTO recommendation_dirty(account_id,message_id)
 SELECT account_id,dashboard_message_id FROM message_metadata
 WHERE account_id=COALESCE(NEW.account_id,OLD.account_id) AND dashboard_message_id=COALESCE(NEW.message_id,OLD.message_id)
 ON CONFLICT DO NOTHING;
 RETURN NULL;
END $$;
CREATE TRIGGER recommendation_correction_changes AFTER INSERT OR UPDATE OR DELETE ON recommendation_corrections
 FOR EACH ROW EXECUTE FUNCTION recommendation_correction_changed();

CREATE FUNCTION recommendation_policy_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 NEW.revision=OLD.revision+CASE WHEN ROW(NEW.trash_statements,NEW.disposable_bulk)
 IS DISTINCT FROM ROW(OLD.trash_statements,OLD.disposable_bulk) THEN 1 ELSE 0 END;
 RETURN NEW;
END $$;
CREATE TRIGGER recommendation_policy_versions BEFORE UPDATE ON recommendation_policies
 FOR EACH ROW EXECUTE FUNCTION recommendation_policy_version();
CREATE FUNCTION recommendation_rebuild() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owner_id BIGINT;
BEGIN
 IF TG_TABLE_NAME='recommendation_policies' THEN
   IF TG_OP='UPDATE' AND NEW.revision=OLD.revision THEN RETURN NULL; END IF;
 END IF;
 IF TG_TABLE_NAME='user_accounts' THEN owner_id=NEW.id;
 ELSE owner_id=COALESCE(NEW.account_id,OLD.account_id); END IF;
 IF TG_TABLE_NAME='dashboard_verified_aliases' AND TG_OP='UPDATE' THEN
   IF OLD.account_id IS DISTINCT FROM NEW.account_id THEN
     UPDATE recommendation_backfill SET last_message_id=0,complete=false WHERE account_id=OLD.account_id;
   END IF;
 END IF;
 -- Do not start a build merely because a retained account/alias is being edited.
 UPDATE recommendation_backfill SET last_message_id=0,complete=false WHERE account_id=owner_id;
 RETURN NULL;
END $$;
CREATE TRIGGER recommendation_policy_changes AFTER INSERT OR UPDATE OR DELETE ON recommendation_policies
 FOR EACH ROW EXECUTE FUNCTION recommendation_rebuild();
CREATE TRIGGER recommendation_owner_changes AFTER UPDATE OF email ON user_accounts
 FOR EACH ROW WHEN(OLD.email IS DISTINCT FROM NEW.email) EXECUTE FUNCTION recommendation_rebuild();
CREATE TRIGGER recommendation_alias_changes AFTER INSERT OR UPDATE OR DELETE ON dashboard_verified_aliases
 FOR EACH ROW EXECUTE FUNCTION recommendation_rebuild();

CREATE FUNCTION recommendation_relationship_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' AND ROW(NEW.sent_count,NEW.received_count,NEW.sent_reply_count,NEW.received_reply_count,NEW.last_sent_at,NEW.last_received_at)
 IS NOT DISTINCT FROM ROW(OLD.sent_count,OLD.received_count,OLD.sent_reply_count,OLD.received_reply_count,OLD.last_sent_at,OLD.last_received_at)
 THEN RETURN NULL; END IF;
 INSERT INTO recommendation_address_dirty(account_id,address)
 SELECT id,COALESCE(NEW.address,OLD.address) FROM user_accounts WHERE id=COALESCE(NEW.account_id,OLD.account_id)
 ON CONFLICT(account_id,address) DO UPDATE SET last_message_id=0,queued_at=now();
 RETURN NULL;
END $$;
CREATE TRIGGER recommendation_relationship_changes AFTER INSERT OR UPDATE OR DELETE ON correspondent_summary
 FOR EACH ROW EXECUTE FUNCTION recommendation_relationship_changed();
