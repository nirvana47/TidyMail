-- Optional bounded evidence; existing classifications retain their original inputs/version.
ALTER TABLE message_metadata ADD COLUMN classification_judgments JSONB;
ALTER TABLE message_metadata
 ADD COLUMN snippet_text TEXT CHECK(octet_length(snippet_text)<=1024),
 ADD COLUMN snippet_state TEXT NOT NULL DEFAULT 'UNKNOWN'
 CHECK(snippet_state IN ('UNKNOWN','ABSENT','AVAILABLE','TRUNCATED'));

CREATE TABLE message_evidence (
 account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
 provider_message_id TEXT NOT NULL,
 evidence_version TEXT NOT NULL,
 evidence_hash TEXT NOT NULL,
 observed_at TIMESTAMPTZ NOT NULL,
 facts JSONB NOT NULL,
 PRIMARY KEY(account_id,provider_message_id),
 FOREIGN KEY(account_id,provider_message_id)
 REFERENCES message_metadata(account_id,provider_message_id) ON DELETE CASCADE
);
CREATE INDEX idx_received_large_messages ON message_metadata(account_id,size_estimate)
 WHERE size_estimate>5000000;

ALTER TABLE dashboard_preview_targets ADD COLUMN size_estimate BIGINT;
