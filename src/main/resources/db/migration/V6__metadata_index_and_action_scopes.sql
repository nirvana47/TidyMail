CREATE TABLE message_metadata (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    provider VARCHAR(16) NOT NULL,
    provider_message_id VARCHAR(255) NOT NULL,
    provider_thread_id VARCHAR(255),
    from_address VARCHAR(320),
    from_display_name VARCHAR(512),
    from_domain VARCHAR(253),
    subject_header VARCHAR(2048),
    received_at TIMESTAMPTZ,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    unread BOOLEAN NOT NULL DEFAULT false,
    labels TEXT[] NOT NULL DEFAULT '{}',
    to_values TEXT[] NOT NULL DEFAULT '{}',
    cc_values TEXT[] NOT NULL DEFAULT '{}',
    bcc_values TEXT[] NOT NULL DEFAULT '{}',
    list_unsubscribe_values TEXT[] NOT NULL DEFAULT '{}',
    list_unsubscribe_post_values TEXT[] NOT NULL DEFAULT '{}',
    list_id_values TEXT[] NOT NULL DEFAULT '{}',
    references_values TEXT[] NOT NULL DEFAULT '{}',
    in_reply_to_values TEXT[] NOT NULL DEFAULT '{}',
    reply_to_values TEXT[] NOT NULL DEFAULT '{}',
    return_path_values TEXT[] NOT NULL DEFAULT '{}',
    delivered_to_values TEXT[] NOT NULL DEFAULT '{}',
    precedence_values TEXT[] NOT NULL DEFAULT '{}',
    auto_submitted_values TEXT[] NOT NULL DEFAULT '{}',
    message_id_values TEXT[] NOT NULL DEFAULT '{}',
    feedback_id_values TEXT[] NOT NULL DEFAULT '{}',
    CONSTRAINT uq_message_metadata_account_provider_message
        UNIQUE (account_id, provider_message_id)
);

CREATE INDEX idx_message_metadata_account_received
    ON message_metadata (account_id, received_at DESC, id DESC);
CREATE INDEX idx_message_metadata_account_sender_received
    ON message_metadata (account_id, from_address, received_at DESC);
CREATE INDEX idx_message_metadata_account_domain_received
    ON message_metadata (account_id, from_domain, received_at DESC);
CREATE INDEX idx_message_metadata_account_unread_received
    ON message_metadata (account_id, unread, received_at DESC);
CREATE INDEX idx_message_metadata_account_thread
    ON message_metadata (account_id, provider_thread_id);
CREATE INDEX idx_message_metadata_account_last_synced
    ON message_metadata (account_id, last_synced_at);

CREATE TABLE action_scopes (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    action_type VARCHAR(32) NOT NULL,
    target_kind VARCHAR(32) NOT NULL,
    message_ids TEXT[] NOT NULL DEFAULT '{}',
    scope_hash VARCHAR(64) NOT NULL,
    message_count INTEGER NOT NULL,
    target_summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    risk_summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_action_scopes_account_hash UNIQUE (account_id, scope_hash),
    CONSTRAINT uq_action_scopes_id_account UNIQUE (id, account_id)
);

ALTER TABLE user_actions ADD COLUMN action_scope_id BIGINT;
ALTER TABLE user_actions
    ADD CONSTRAINT fk_user_actions_scope_account
    FOREIGN KEY (action_scope_id, account_id)
    REFERENCES action_scopes(id, account_id);
CREATE INDEX idx_user_actions_account_scope
    ON user_actions (account_id, action_scope_id);

ALTER TABLE sync_progress DROP CONSTRAINT sync_progress_status_check;
UPDATE sync_progress SET status = 'COMPLETE' WHERE status = 'SUCCESS';
ALTER TABLE sync_progress
    ADD CONSTRAINT sync_progress_v6_status_check
    CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETE', 'PARTIAL', 'FAILED'));
ALTER TABLE sync_progress ADD COLUMN checkpoint_token VARCHAR(2048);
ALTER TABLE sync_progress ADD COLUMN pages_completed INTEGER NOT NULL DEFAULT 0;
ALTER TABLE sync_progress ADD COLUMN messages_upserted INTEGER NOT NULL DEFAULT 0;
ALTER TABLE sync_progress ADD COLUMN last_successful_at TIMESTAMPTZ;
