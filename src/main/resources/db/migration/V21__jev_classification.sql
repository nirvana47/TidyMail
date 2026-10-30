-- Optional, metadata-only Jev classification. No mailbox actions or identity merges.
ALTER TABLE message_metadata
 ADD COLUMN classification_category TEXT,
 ADD COLUMN classification_override TEXT CHECK(classification_override IN ('PERSONAL','MARKETING','NEWSLETTER','RECEIPT','SECURITY','NOTIFICATION','OTHER','UNCERTAIN')),
 ADD COLUMN classification_kind TEXT,
 ADD COLUMN classification_stream TEXT,
 ADD COLUMN classification_confidence DOUBLE PRECISION,
 ADD COLUMN classification_hash TEXT,
 ADD COLUMN classification_evidence_hash TEXT,
 ADD COLUMN classification_version TEXT,
 ADD COLUMN classified_at TIMESTAMPTZ;
ALTER TABLE message_metadata ADD CONSTRAINT classification_category_valid CHECK
 (classification_category IN ('PERSONAL','MARKETING','NEWSLETTER','RECEIPT','SECURITY','NOTIFICATION','OTHER','UNCERTAIN'));
ALTER TABLE message_metadata ADD CONSTRAINT classification_kind_valid CHECK
 (classification_kind IN ('PERSON','AUTOMATED','RELAY','UNKNOWN'));
ALTER TABLE message_metadata ADD CONSTRAINT classification_stream_valid CHECK
 (classification_stream IN ('GENERAL','VOICE','AI_PRODUCT','DEVICE_SUPPORT','SECURITY','BANKING','SHOPPING','TRAVEL','SOCIAL','WORK'));
CREATE TABLE dashboard_classification_jobs (
 public_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
 status TEXT NOT NULL CHECK(status IN ('QUEUED','RUNNING','COMPLETE','CANCELLED','FAILED')),
 total INTEGER NOT NULL DEFAULT 0, done INTEGER NOT NULL DEFAULT 0, skipped INTEGER NOT NULL DEFAULT 0,
 attempts INTEGER NOT NULL DEFAULT 0, lease_token UUID, lease_until TIMESTAMPTZ,
 next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(), error_message TEXT,
 cost_usd NUMERIC(16,8) NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(public_id,account_id)
);
CREATE UNIQUE INDEX idx_classification_active_account ON dashboard_classification_jobs(account_id)
 WHERE status IN ('QUEUED','RUNNING');
CREATE TABLE dashboard_classification_targets (
 job_id UUID NOT NULL, account_id BIGINT NOT NULL, message_id UUID NOT NULL,
 state TEXT NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','DONE','SKIPPED')),
 PRIMARY KEY(job_id,message_id),
 FOREIGN KEY(job_id,account_id) REFERENCES dashboard_classification_jobs(public_id,account_id) ON DELETE CASCADE
);
CREATE INDEX idx_classification_pending ON dashboard_classification_targets(job_id,message_id) WHERE state='PENDING';

-- Fingerprint exactly the evidence used by this version of the classifier.
-- Reply IDs themselves and arbitrary custom labels are never sent externally.
CREATE FUNCTION dashboard_classification_hash(m message_metadata) RETURNS TEXT LANGUAGE sql IMMUTABLE AS $$
 SELECT md5(jsonb_build_array(m.dashboard_from_address,left(COALESCE(m.from_display_name,''),120),
   left(COALESCE(m.subject_header,''),300),m.dashboard_unit_kind,
   m.labels && ARRAY['CATEGORY_PERSONAL'],m.labels && ARRAY['CATEGORY_PROMOTIONS'],
   m.labels && ARRAY['CATEGORY_SOCIAL'],m.labels && ARRAY['CATEGORY_UPDATES'],m.labels && ARRAY['CATEGORY_FORUMS'],
   cardinality(m.references_values)>0 OR cardinality(m.in_reply_to_values)>0,
   cardinality(m.list_unsubscribe_values)>0)::text)
$$;

-- Compute evidence once on metadata writes, rather than hashing every dashboard row.
UPDATE message_metadata m SET classification_evidence_hash=dashboard_classification_hash(m);
CREATE FUNCTION dashboard_refresh_classification_hash() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 NEW.classification_evidence_hash:=dashboard_classification_hash(NEW);
 RETURN NEW;
END $$;
CREATE TRIGGER dashboard_classification_evidence BEFORE INSERT OR UPDATE OF
 dashboard_from_address,from_display_name,subject_header,dashboard_unit_kind,labels,
 references_values,in_reply_to_values,list_unsubscribe_values ON message_metadata
 FOR EACH ROW EXECUTE FUNCTION dashboard_refresh_classification_hash();
