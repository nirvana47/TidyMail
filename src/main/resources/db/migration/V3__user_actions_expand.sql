ALTER TABLE user_actions DROP CONSTRAINT IF EXISTS ck_user_actions_undo_undone_at;
ALTER TABLE user_actions ADD target_kind VARCHAR(32);
ALTER TABLE user_actions ADD target_id_json JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE user_actions ADD affected_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE user_actions ADD expires_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_user_actions_account_expires ON user_actions (account_id, expires_at DESC NULLS LAST);
CREATE INDEX IF NOT EXISTS idx_user_actions_account_target_kind ON user_actions (account_id, target_kind);
