ALTER TABLE user_accounts ADD COLUMN owner_key VARCHAR(512);

UPDATE user_accounts
SET owner_key = lower(email) || ':' || provider
WHERE owner_key IS NULL;

ALTER TABLE user_accounts ALTER COLUMN owner_key SET NOT NULL;

CREATE INDEX idx_user_accounts_owner_id
    ON user_accounts (owner_key, id);
