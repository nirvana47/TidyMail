-- Compact source revisions let filters reject stale results without hashing full metadata per row.
CREATE TABLE recommendation_message_versions (
 account_id BIGINT NOT NULL,
 message_id UUID NOT NULL,
 revision BIGINT NOT NULL DEFAULT 1,
 PRIMARY KEY(account_id,message_id),
 FOREIGN KEY(account_id,message_id) REFERENCES message_metadata(account_id,dashboard_message_id) ON DELETE CASCADE
);
CREATE FUNCTION recommendation_dirty_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO recommendation_message_versions(account_id,message_id) VALUES(NEW.account_id,NEW.message_id)
 ON CONFLICT(account_id,message_id) DO UPDATE SET revision=recommendation_message_versions.revision+1;
 RETURN NEW;
END $$;
-- BEFORE INSERT also runs on conflict, recording another change while a message is already queued.
CREATE TRIGGER recommendation_dirty_revision BEFORE INSERT ON recommendation_dirty
 FOR EACH ROW EXECUTE FUNCTION recommendation_dirty_version();
ALTER TABLE message_recommendations ADD COLUMN source_stamp TEXT;
CREATE INDEX recommendation_action ON message_recommendations(account_id,(result->>'action'));
CREATE INDEX recommendation_importance ON message_recommendations(account_id,(result->>'importance'));
CREATE TABLE recommendation_processing (
 account_id BIGINT PRIMARY KEY REFERENCES user_accounts(id) ON DELETE CASCADE,
 enabled BOOLEAN NOT NULL DEFAULT false,
 last_run_at TIMESTAMPTZ,
 lease_token UUID,
 lease_until TIMESTAMPTZ,
 error_code TEXT
);
