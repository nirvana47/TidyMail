ALTER TABLE mailbox_sync_state
    ADD COLUMN gmail_backfill_phase VARCHAR(16) NOT NULL DEFAULT 'INBOX'
        CHECK (gmail_backfill_phase IN ('INBOX', 'ALL_MAIL', 'COMPLETE')),
    ADD COLUMN gmail_scope_total BIGINT CHECK (gmail_scope_total >= 0);

CREATE TABLE gmail_backfill_seen (
    account_id BIGINT NOT NULL REFERENCES mailbox_sync_state(account_id) ON DELETE CASCADE,
    provider_message_id VARCHAR(255) NOT NULL,
    deleted_by_history BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (account_id, provider_message_id)
);

-- Old list cursors are tied to an unfiltered listing. Re-enumerate IDs in the
-- new scope and reuse indexed metadata. Keep the first-batch and History checkpoints.
UPDATE mailbox_sync_state
SET backfill_page_token = NULL,
    backfill_status = CASE WHEN initial_batch_completed_at IS NULL THEN 'NOT_STARTED' ELSE 'RUNNING' END,
    backfill_started_at = NULL,
    backfill_completed_at = NULL,
    provider_total = NULL,
    provider_total_observed_at = NULL,
    version = version + 1
WHERE provider = 'google';

-- Restart any interrupted recovery using the new listing and a fresh History anchor.
UPDATE mailbox_sync_state
SET rescan_status = 'REQUIRED',
    lifecycle = 'RESCAN_REQUIRED',
    rescan_anchor = NULL,
    rescan_generation = NULL,
    rescan_started_at = NULL
WHERE provider = 'google' AND rescan_status = 'RUNNING';
