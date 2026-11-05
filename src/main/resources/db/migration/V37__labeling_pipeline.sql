-- Exact reviewed scopes only; installing this migration starts no provider work.
CREATE TABLE labeling_scopes (
 id UUID PRIMARY KEY, account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
 kind TEXT NOT NULL CHECK(kind IN ('MIME','JEV')),
 manifest_hash TEXT NOT NULL, manifest JSONB NOT NULL,
 authorized BOOLEAN NOT NULL DEFAULT false, paused BOOLEAN NOT NULL DEFAULT false,
 max_spend NUMERIC(24,12) NOT NULL DEFAULT 0 CHECK(max_spend>=0),
 mime_requests INT NOT NULL DEFAULT 0, mime_bytes BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE labeling_work (
 id UUID PRIMARY KEY, scope_id UUID NOT NULL REFERENCES labeling_scopes(id) ON DELETE CASCADE,
 account_id BIGINT NOT NULL, message_id UUID NOT NULL,
 state TEXT NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','LEASED','DISPATCHED','DONE','STALE','FAILED','UNKNOWN')),
 input_hash TEXT, content_hash TEXT, evidence JSONB, request JSONB,
 attempt_id UUID REFERENCES classification_attempts(id) ON DELETE SET NULL, response JSONB,
 lease_token UUID, lease_until TIMESTAMPTZ, tries INT NOT NULL DEFAULT 0,
 next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(), error_code TEXT,
 UNIQUE(scope_id,message_id),
 FOREIGN KEY(account_id,message_id) REFERENCES message_metadata(account_id,dashboard_message_id) ON DELETE CASCADE
);
CREATE INDEX labeling_ready ON labeling_work(next_attempt_at,id) WHERE state IN ('PENDING','LEASED');
-- Exposure rows are append-only, including failures and responses rejected as stale.
CREATE TABLE labeling_exposures (
 attempt_id UUID PRIMARY KEY REFERENCES classification_attempts(id) ON DELETE CASCADE,
 scope_id UUID NOT NULL REFERENCES labeling_scopes(id) ON DELETE CASCADE,
 account_id BIGINT NOT NULL, message_id UUID NOT NULL,
 content_hash TEXT NOT NULL, input_hash TEXT NOT NULL, request JSONB NOT NULL,
 reserved NUMERIC(24,12) NOT NULL, pricing JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE message_semantic_labels (
 account_id BIGINT NOT NULL, message_id UUID NOT NULL,
 input_hash TEXT NOT NULL, variant TEXT NOT NULL CHECK(variant IN ('HEADERS','SNIPPET')),
 contract_version TEXT NOT NULL, judgment JSONB NOT NULL,
 attempt_id UUID NOT NULL REFERENCES labeling_exposures(attempt_id) ON DELETE CASCADE,
 saved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(account_id,message_id),
 FOREIGN KEY(account_id,message_id) REFERENCES message_metadata(account_id,dashboard_message_id) ON DELETE CASCADE
);
-- Contact history stays local; new exchanges recompose retention without invalidating/rebuying semantic labels.
-- Independent of cleanup policy, correction, read state, engine version and MIME timestamps.
CREATE FUNCTION labeling_input_hash(m message_metadata,h correspondent_summary,variant text) RETURNS TEXT LANGUAGE sql IMMUTABLE AS $$
 SELECT md5(jsonb_build_array(m.dashboard_from_address,COALESCE(m.from_display_name,''),COALESCE(m.subject_header,''),
 cardinality(m.references_values)>0 OR cardinality(m.in_reply_to_values)>0,
 cardinality(m.list_unsubscribe_values)>0,m.label_evidence_known,
 m.labels && ARRAY['CATEGORY_PERSONAL'],m.labels && ARRAY['CATEGORY_PROMOTIONS'],
 m.labels && ARRAY['CATEGORY_SOCIAL'],m.labels && ARRAY['CATEGORY_UPDATES'],m.labels && ARRAY['CATEGORY_FORUMS'],
 CASE WHEN variant='SNIPPET' THEN m.snippet_text END,CASE WHEN variant='SNIPPET' THEN m.snippet_state END)::text)
$$;
CREATE FUNCTION semantic_labels_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO recommendation_dirty(account_id,message_id) VALUES(COALESCE(NEW.account_id,OLD.account_id),COALESCE(NEW.message_id,OLD.message_id)) ON CONFLICT DO NOTHING;
 RETURN NULL;
END $$;
CREATE TRIGGER semantic_label_changes AFTER INSERT OR UPDATE OR DELETE ON message_semantic_labels
 FOR EACH ROW EXECUTE FUNCTION semantic_labels_changed();

CREATE FUNCTION labeling_exposure_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Labeling exposure records cannot be rewritten'; END $$;
CREATE TRIGGER labeling_exposure_no_rewrite BEFORE UPDATE ON labeling_exposures
 FOR EACH ROW EXECUTE FUNCTION labeling_exposure_immutable();

ALTER TABLE labeling_exposures ADD CONSTRAINT labeling_exposure_owned UNIQUE(attempt_id,account_id,message_id);
ALTER TABLE message_semantic_labels ADD CONSTRAINT semantic_label_owned_exposure
 FOREIGN KEY(attempt_id,account_id,message_id) REFERENCES labeling_exposures(attempt_id,account_id,message_id) ON DELETE CASCADE;
CREATE INDEX labeling_exposure_dedup ON labeling_exposures(account_id,message_id,content_hash);

-- Typed cache provenance avoids rebuilding/hashing JSON for every filtered mailbox row.
ALTER TABLE message_recommendations
 ADD COLUMN metadata_revision BIGINT, ADD COLUMN account_stamp TEXT, ADD COLUMN policy_source_revision BIGINT,
 ADD COLUMN history_revision BIGINT, ADD COLUMN history_sent BIGINT, ADD COLUMN history_received BIGINT,
 ADD COLUMN history_sent_replies BIGINT, ADD COLUMN history_received_replies BIGINT,
 ADD COLUMN history_last_sent TIMESTAMPTZ, ADD COLUMN history_last_received TIMESTAMPTZ,
 ADD COLUMN attention_source TEXT;
ALTER TABLE message_semantic_labels ADD COLUMN labels TEXT[] NOT NULL DEFAULT '{}', ADD COLUMN purpose TEXT NOT NULL DEFAULT 'UNKNOWN';
CREATE INDEX semantic_label_membership ON message_semantic_labels USING gin(labels);
-- The new route starts as a bounded trial, with one cumulative allowance across scopes/restarts.
ALTER TABLE classification_budget
 ADD COLUMN labeling_reported NUMERIC(24,12) NOT NULL DEFAULT 0,
 ADD COLUMN labeling_checkpoint NUMERIC(24,12) NOT NULL DEFAULT 0.20;
