CREATE TABLE dashboard_pattern_reviews (
 id UUID PRIMARY KEY, account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
 version TEXT NOT NULL, months INTEGER NOT NULL CHECK(months IN (12,24)),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), applied_at TIMESTAMPTZ,
 snapshot JSONB NOT NULL, UNIQUE(account_id,id)
);
CREATE TABLE dashboard_pattern_labels (
 account_id BIGINT NOT NULL, message_id UUID NOT NULL, review_id UUID NOT NULL,
 source_id UUID NOT NULL, source_hash TEXT NOT NULL, evidence_hash TEXT NOT NULL,
 label TEXT NOT NULL, template TEXT NOT NULL,
 PRIMARY KEY(account_id,message_id),
 FOREIGN KEY(account_id,message_id) REFERENCES message_metadata(account_id,dashboard_message_id) ON DELETE CASCADE,
 FOREIGN KEY(account_id,source_id) REFERENCES message_metadata(account_id,dashboard_message_id) ON DELETE CASCADE,
 FOREIGN KEY(account_id,review_id) REFERENCES dashboard_pattern_reviews(account_id,id) ON DELETE CASCADE
);
-- Source/target hashes include full subject, snippet and identity; labels never survive changed evidence silently.
CREATE FUNCTION dashboard_pattern_hash(m message_metadata) RETURNS TEXT LANGUAGE sql IMMUTABLE AS $$
 SELECT md5(jsonb_build_array(m.dashboard_from_address,m.dashboard_unit_kind,m.dashboard_unit_key,
 m.subject_header,m.snippet_text,m.snippet_state,m.classification_override,m.classification_kind,
 m.classification_hash,m.classification_evidence_hash,m.classification_category,m.classification_version)::text)
$$;
