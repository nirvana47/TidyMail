-- Local relationship evidence only. No classification invalidation or paid-work enqueue.
CREATE TABLE correspondent_backfill (
 account_id BIGINT PRIMARY KEY REFERENCES user_accounts(id) ON DELETE CASCADE,
 last_message_id BIGINT NOT NULL DEFAULT 0,
 complete BOOLEAN NOT NULL DEFAULT false
);
CREATE TABLE correspondent_dirty (
 account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
 message_id BIGINT NOT NULL,
 PRIMARY KEY(account_id,message_id)
);
-- Retain deleted messages' contributions until the bounded worker removes them.
CREATE TABLE correspondent_contributions (
 account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
 message_id BIGINT NOT NULL,
 address TEXT NOT NULL,
 sent BOOLEAN NOT NULL, received BOOLEAN NOT NULL, reply BOOLEAN NOT NULL,
 observed_at TIMESTAMPTZ,
 PRIMARY KEY(account_id,message_id,address)
);
CREATE INDEX correspondent_contributions_address ON correspondent_contributions(account_id,address);
CREATE INDEX correspondent_last_sent ON correspondent_contributions(account_id,address,observed_at DESC) WHERE sent AND observed_at IS NOT NULL;
CREATE INDEX correspondent_last_received ON correspondent_contributions(account_id,address,observed_at DESC) WHERE received AND observed_at IS NOT NULL;
CREATE TABLE correspondent_summary (
 account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
 address TEXT NOT NULL,
 sent_count BIGINT NOT NULL, received_count BIGINT NOT NULL,
 sent_reply_count BIGINT NOT NULL, received_reply_count BIGINT NOT NULL,
 last_sent_at TIMESTAMPTZ, last_received_at TIMESTAMPTZ,
 revision BIGINT NOT NULL DEFAULT 1,
 PRIMARY KEY(account_id,address)
);
-- Eligibility for a future scoped review, deliberately separate from classification_incoming.
CREATE TABLE correspondent_reconsideration (
 account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
 address TEXT NOT NULL,
 revision BIGINT NOT NULL,
 PRIMARY KEY(account_id,address)
);
CREATE INDEX correspondent_backfill_messages ON message_metadata(account_id,id);
CREATE FUNCTION correspondent_enqueue() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN
   -- Account deletion already cascades all local evidence.
   IF EXISTS(SELECT 1 FROM user_accounts WHERE id=OLD.account_id) THEN
     INSERT INTO correspondent_dirty VALUES(OLD.account_id,OLD.id) ON CONFLICT DO NOTHING;
   END IF;
   RETURN OLD;
 END IF;
 IF TG_OP='UPDATE' AND ROW(NEW.labels,NEW.label_evidence_known,NEW.dashboard_from_address,
     NEW.to_values,NEW.cc_values,NEW.bcc_values,NEW.delivered_to_values,NEW.in_reply_to_values,NEW.references_values,NEW.received_at)
   IS NOT DISTINCT FROM ROW(OLD.labels,OLD.label_evidence_known,OLD.dashboard_from_address,
     OLD.to_values,OLD.cc_values,OLD.bcc_values,OLD.delivered_to_values,OLD.in_reply_to_values,OLD.references_values,OLD.received_at)
 THEN RETURN NEW; END IF;
 INSERT INTO correspondent_dirty VALUES(NEW.account_id,NEW.id) ON CONFLICT DO NOTHING;
 RETURN NEW;
END $$;
CREATE TRIGGER correspondent_changes AFTER INSERT OR DELETE OR UPDATE OF
 labels,label_evidence_known,dashboard_from_address,to_values,cc_values,bcc_values,delivered_to_values,
 in_reply_to_values,references_values,received_at ON message_metadata
 FOR EACH ROW EXECUTE FUNCTION correspondent_enqueue();

CREATE FUNCTION correspondent_reset_coverage() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='user_accounts' THEN
   INSERT INTO correspondent_backfill(account_id) VALUES(NEW.id)
   ON CONFLICT(account_id) DO UPDATE SET last_message_id=0,complete=false;
 ELSE
   INSERT INTO correspondent_backfill(account_id) SELECT id FROM user_accounts WHERE id=COALESCE(NEW.account_id,OLD.account_id)
   ON CONFLICT(account_id) DO UPDATE SET last_message_id=0,complete=false;
 END IF;
 RETURN NULL;
END $$;
CREATE TRIGGER correspondent_owner_change AFTER UPDATE OF email ON user_accounts
 FOR EACH ROW WHEN (OLD.email IS DISTINCT FROM NEW.email) EXECUTE FUNCTION correspondent_reset_coverage();
CREATE TRIGGER correspondent_alias_change AFTER INSERT OR UPDATE OR DELETE ON dashboard_verified_aliases
 FOR EACH ROW EXECUTE FUNCTION correspondent_reset_coverage();
