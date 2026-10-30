ALTER TABLE dashboard_account_settings ADD COLUMN classification_automatic BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE dashboard_classification_jobs ADD COLUMN automatic BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE dashboard_classification_targets ADD COLUMN automatic BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE dashboard_classification_targets ADD COLUMN priority INTEGER NOT NULL DEFAULT 0;
CREATE TABLE classification_incoming (
 account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
 message_id UUID NOT NULL, evidence_hash TEXT NOT NULL,
 queued_at TIMESTAMPTZ NOT NULL DEFAULT now(), retry_after TIMESTAMPTZ DEFAULT now(),
 PRIMARY KEY(account_id,message_id)
);
CREATE FUNCTION dashboard_enqueue_classification() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' OR NEW.classification_evidence_hash IS DISTINCT FROM OLD.classification_evidence_hash THEN
   INSERT INTO classification_incoming(account_id,message_id,evidence_hash)
   VALUES(NEW.account_id,NEW.dashboard_message_id,NEW.classification_evidence_hash)
   ON CONFLICT(account_id,message_id) DO UPDATE SET evidence_hash=EXCLUDED.evidence_hash,queued_at=now(),retry_after=now();
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER dashboard_classification_incoming AFTER INSERT OR UPDATE OF
 dashboard_from_address,from_display_name,subject_header,dashboard_unit_kind,labels,
 references_values,in_reply_to_values,list_unsubscribe_values ON message_metadata
 FOR EACH ROW EXECUTE FUNCTION dashboard_enqueue_classification();
