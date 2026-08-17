ALTER TABLE user_accounts ADD last_login_at TIMESTAMPTZ;
COMMENT ON COLUMN user_accounts.last_login_at IS 'set on OAuth2 login success event';
CREATE INDEX IF NOT EXISTS idx_user_accounts_last_login_at ON user_accounts (last_login_at NULLS LAST);
CREATE TABLE sync_progress (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    job_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING','SUCCESS','FAILED')),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    messages_scanned INTEGER NOT NULL DEFAULT 0,
    error_message TEXT
);
CREATE INDEX IF NOT EXISTS idx_sync_progress_account_job_desc ON sync_progress (account_id, job_type, id DESC);
