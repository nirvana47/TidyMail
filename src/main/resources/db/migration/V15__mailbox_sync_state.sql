CREATE TABLE mailbox_sync_state (
    account_id BIGINT PRIMARY KEY REFERENCES user_accounts(id) ON DELETE CASCADE,
    provider VARCHAR(16) NOT NULL,
    lifecycle VARCHAR(32) NOT NULL DEFAULT 'NEW',
    incremental_cursor_kind VARCHAR(32),
    incremental_cursor_value VARCHAR(2048),
    incremental_last_attempt_at TIMESTAMPTZ,
    incremental_last_success_at TIMESTAMPTZ,
    backfill_page_token VARCHAR(2048),
    backfill_status VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED',
    backfill_started_at TIMESTAMPTZ,
    backfill_completed_at TIMESTAMPTZ,
    initial_batch_completed_at TIMESTAMPTZ,
    provider_total BIGINT,
    provider_total_observed_at TIMESTAMPTZ,
    rescan_status VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED',
    rescan_generation UUID,
    rescan_started_at TIMESTAMPTZ,
    rescan_anchor VARCHAR(2048),
    rescan_completed_at TIMESTAMPTZ,
    error_code VARCHAR(64),
    error_detail VARCHAR(200),
    freshness_lease_owner VARCHAR(128),
    freshness_lease_expires_at TIMESTAMPTZ,
    backfill_lease_owner VARCHAR(128),
    backfill_lease_expires_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT mailbox_sync_state_lifecycle_check CHECK (
        lifecycle IN (
            'NEW', 'BOOTSTRAPPING', 'BACKFILLING', 'ACTIVE',
            'RESCAN_REQUIRED', 'RESCANNING', 'REAUTH_REQUIRED', 'ERROR'
        )
    ),
    CONSTRAINT mailbox_sync_state_cursor_check CHECK (
        (incremental_cursor_kind IS NULL) = (incremental_cursor_value IS NULL)
        AND (incremental_cursor_kind IS NULL
            OR incremental_cursor_kind IN ('GMAIL_HISTORY_ID', 'MICROSOFT_DELTA_LINK'))
    ),
    CONSTRAINT mailbox_sync_state_backfill_check CHECK (
        backfill_status IN ('NOT_STARTED', 'RUNNING', 'COMPLETE', 'FAILED')
    ),
    CONSTRAINT mailbox_sync_state_rescan_check CHECK (
        rescan_status IN ('NOT_STARTED', 'REQUIRED', 'RUNNING', 'COMPLETE', 'FAILED')
    ),
    CONSTRAINT mailbox_sync_state_provider_total_check CHECK (
        provider_total IS NULL OR provider_total >= 0
    ),
    CONSTRAINT mailbox_sync_state_freshness_lease_check CHECK (
        (freshness_lease_owner IS NULL) = (freshness_lease_expires_at IS NULL)
    ),
    CONSTRAINT mailbox_sync_state_backfill_lease_check CHECK (
        (backfill_lease_owner IS NULL) = (backfill_lease_expires_at IS NULL)
    )
);

CREATE INDEX idx_mailbox_sync_state_lifecycle_account
    ON mailbox_sync_state (lifecycle, account_id);
CREATE INDEX idx_mailbox_sync_state_freshness_lease
    ON mailbox_sync_state (freshness_lease_expires_at, account_id)
    WHERE freshness_lease_owner IS NOT NULL;
CREATE INDEX idx_mailbox_sync_state_backfill_lease
    ON mailbox_sync_state (backfill_lease_expires_at, account_id)
    WHERE backfill_lease_owner IS NOT NULL;

ALTER TABLE message_metadata
    ADD COLUMN provider_revision NUMERIC(20, 0),
    ADD COLUMN full_scan_generation UUID,
    ADD COLUMN attachment_state VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN attachment_observed_at TIMESTAMPTZ,
    ADD COLUMN attachment_source VARCHAR(32),
    ADD CONSTRAINT message_metadata_provider_revision_check CHECK (
        provider_revision IS NULL
        OR (provider_revision >= 0 AND provider_revision <= 99999999999999999999)
    ),
    ADD CONSTRAINT message_metadata_attachment_state_check CHECK (
        attachment_state IN ('PRESENT', 'ABSENT', 'UNKNOWN')
    ),
    ADD CONSTRAINT message_metadata_attachment_provenance_check CHECK (
        (attachment_state = 'UNKNOWN'
            AND attachment_observed_at IS NULL AND attachment_source IS NULL)
        OR (attachment_state <> 'UNKNOWN'
            AND attachment_observed_at IS NOT NULL AND attachment_source IS NOT NULL)
    );

DO $$
BEGIN
    EXECUTE 'UPDATE message_metadata SET metadata_' || 'snippet = NULL';
    EXECUTE 'ALTER TABLE message_metadata DROP COLUMN metadata_' || 'snippet';
END
$$;

CREATE INDEX idx_message_metadata_account_generation_freshness
    ON message_metadata (
        account_id, full_scan_generation, last_synced_at, provider_revision
    );

ALTER TABLE sync_progress
    ADD COLUMN lane VARCHAR(32) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN phase VARCHAR(32) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN messages_fetched BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN messages_changed BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN messages_deleted BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN indexed_messages BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN provider_total_snapshot BIGINT,
    ADD COLUMN retry_code VARCHAR(64),
    ADD CONSTRAINT sync_progress_lane_check CHECK (
        lane IN ('LEGACY', 'FRESHNESS', 'BACKFILL', 'RESCAN')
    ),
    ADD CONSTRAINT sync_progress_factual_counts_check CHECK (
        messages_fetched >= 0 AND messages_changed >= 0
        AND messages_deleted >= 0 AND indexed_messages >= 0
        AND (provider_total_snapshot IS NULL OR provider_total_snapshot >= 0)
    );

CREATE INDEX idx_sync_progress_account_lane_newest
    ON sync_progress (account_id, lane, id DESC);

ALTER TABLE user_accounts
    ADD COLUMN oauth_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN oauth_error_code VARCHAR(64),
    ADD COLUMN oauth_error_at TIMESTAMPTZ,
    ADD CONSTRAINT user_accounts_oauth_status_check CHECK (
        oauth_status IN ('ACTIVE', 'REAUTH_REQUIRED')
    );
