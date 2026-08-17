ALTER TABLE user_actions
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'APPLIED', 'CANCELLED'));

ALTER TABLE user_actions
    ADD COLUMN applied_at TIMESTAMPTZ;

-- Existing rows were executed immediately by the legacy UI. They must never
-- reappear as new review items after upgrading to the staged-action workflow.
UPDATE user_actions
SET status = 'APPLIED',
    applied_at = COALESCE(undone_at, created_at)
WHERE status = 'QUEUED';

CREATE INDEX idx_user_actions_account_status_created
    ON user_actions (account_id, status, created_at ASC);
