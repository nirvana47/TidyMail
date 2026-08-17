CREATE TABLE user_accounts (
    id BIGSERIAL PRIMARY KEY,
    email TEXT NOT NULL,
    provider TEXT NOT NULL CHECK (provider IN ('google', 'microsoft')),
    provider_account_id TEXT NOT NULL,
    display_name TEXT,
    avatar_url TEXT,
    encrypted_access_token BYTEA NOT NULL,
    encrypted_refresh_token BYTEA NOT NULL,
    token_issued_at TIMESTAMPTZ,
    access_expires_at TIMESTAMPTZ,
    scopes TEXT[] NOT NULL DEFAULT '{}',
    aes_key_id TEXT NOT NULL DEFAULT 'v1',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_accounts_email_provider UNIQUE (email, provider),
    CONSTRAINT uq_user_accounts_provider_provider_account_id UNIQUE (provider, provider_account_id)
);

CREATE TABLE sender_stats (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    from_address TEXT NOT NULL,
    from_display_name TEXT,
    from_domain TEXT NOT NULL,
    total_count INTEGER NOT NULL DEFAULT 0,
    unread_count INTEGER NOT NULL DEFAULT 0,
    has_list_unsubscribe BOOLEAN NOT NULL DEFAULT false,
    has_precedence_bulk BOOLEAN NOT NULL DEFAULT false,
    oldest_message_at TIMESTAMPTZ,
    newest_message_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_synced_at TIMESTAMPTZ,
    CONSTRAINT uq_sender_stats_account_from_address UNIQUE (account_id, from_address)
);

CREATE TABLE user_actions (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    action_type TEXT NOT NULL CHECK (action_type IN ('ARCHIVE', 'TRASH', 'MARK_READ', 'DELETE', 'UNSUBSCRIBE', 'UNDO')),
    sender_group_key TEXT,
    message_ids TEXT[] NOT NULL DEFAULT '{}',
    inverse_action_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    undone_at TIMESTAMPTZ,
    CONSTRAINT ck_user_actions_undo_undone_at CHECK ((action_type = 'UNDO') = (undone_at IS NOT NULL))
);

CREATE INDEX idx_sender_stats_account_total_count ON sender_stats (account_id, total_count DESC);
CREATE INDEX idx_user_actions_account_created ON user_actions (account_id, created_at DESC);
CREATE INDEX idx_user_accounts_provider_email ON user_accounts (provider, email);
