CREATE TABLE outlook_sync_round (
    account_id bigint PRIMARY KEY REFERENCES user_accounts(id) ON DELETE CASCADE,
    round_id uuid NOT NULL,
    inventory_complete boolean NOT NULL DEFAULT false,
    recent_complete boolean NOT NULL DEFAULT false
);

CREATE TABLE outlook_folders (
    account_id bigint NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    folder_id text NOT NULL,
    folder_path text NOT NULL,
    role text NOT NULL,
    cursor text,
    baseline uuid,
    finished_round uuid,
    PRIMARY KEY (account_id, folder_id)
);

CREATE TABLE outlook_reconcile (
    account_id bigint NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    message_id text NOT NULL,
    PRIMARY KEY (account_id, message_id)
);

ALTER TABLE message_metadata
    ADD COLUMN outlook_folder_id text,
    ADD COLUMN outlook_categories text[],
    ADD COLUMN outlook_web_link text;

CREATE INDEX idx_outlook_message_folder ON message_metadata(account_id, outlook_folder_id)
    WHERE outlook_folder_id IS NOT NULL;

ALTER TABLE cleanup_job_targets ADD COLUMN outlook_read_required boolean NOT NULL DEFAULT false,
    ADD COLUMN outlook_read_completed boolean NOT NULL DEFAULT false;

INSERT INTO recommendation_processing(account_id,enabled)
SELECT id,true FROM user_accounts WHERE provider='microsoft' ON CONFLICT DO NOTHING;

CREATE TRIGGER start_outlook_local_processing AFTER INSERT ON user_accounts
FOR EACH ROW WHEN(NEW.provider='microsoft') EXECUTE FUNCTION start_gmail_local_processing();

CREATE OR REPLACE FUNCTION dashboard_enqueue_classification() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.provider='google'
    AND (TG_OP='INSERT' OR NEW.classification_evidence_hash IS DISTINCT FROM OLD.classification_evidence_hash) THEN
   INSERT INTO classification_incoming(account_id,message_id,evidence_hash)
   VALUES(NEW.account_id,NEW.dashboard_message_id,NEW.classification_evidence_hash)
   ON CONFLICT(account_id,message_id) DO UPDATE SET evidence_hash=EXCLUDED.evidence_hash,queued_at=now(),retry_after=now();
 END IF;
 RETURN NEW;
END $$;
