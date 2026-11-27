-- A completed mailbox check is distinct from a successfully committed page/chunk.
ALTER TABLE mailbox_sync_state ADD COLUMN mailbox_checked_at timestamptz;
CREATE TABLE mailbox_sync_events (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id bigint NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    kind varchar(32) NOT NULL CHECK (kind IN ('IMPORT_COMPLETE','CHECK_COMPLETE','RESCAN_COMPLETE')),
    occurred_at timestamptz NOT NULL
);
CREATE INDEX idx_mailbox_sync_events_account_newest ON mailbox_sync_events(account_id, occurred_at DESC, id DESC);

-- Outlook already records success only after all included folders and reconciliation finish.
-- Gmail's old timestamp can describe a partial chunk, so do not promote it to a completed check.
UPDATE mailbox_sync_state SET mailbox_checked_at=incremental_last_success_at WHERE provider='microsoft';
INSERT INTO mailbox_sync_events(account_id,kind,occurred_at)
SELECT account_id,'IMPORT_COMPLETE',backfill_completed_at FROM mailbox_sync_state
WHERE backfill_status='COMPLETE' AND backfill_completed_at IS NOT NULL;
INSERT INTO mailbox_sync_events(account_id,kind,occurred_at)
SELECT account_id,'CHECK_COMPLETE',mailbox_checked_at FROM mailbox_sync_state WHERE mailbox_checked_at IS NOT NULL;
