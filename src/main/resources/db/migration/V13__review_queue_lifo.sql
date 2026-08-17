CREATE SEQUENCE user_actions_queue_sequence_seq;

ALTER TABLE user_actions
    ADD COLUMN queued_at TIMESTAMPTZ,
    ADD COLUMN "sequence" BIGINT;

UPDATE user_actions
SET queued_at = created_at,
    "sequence" = id;

SELECT setval(
    'user_actions_queue_sequence_seq',
    COALESCE((SELECT MAX("sequence") FROM user_actions), 0) + 1,
    false
);

ALTER TABLE user_actions
    ALTER COLUMN queued_at SET DEFAULT now(),
    ALTER COLUMN queued_at SET NOT NULL,
    ALTER COLUMN "sequence" SET DEFAULT nextval('user_actions_queue_sequence_seq'),
    ALTER COLUMN "sequence" SET NOT NULL;

ALTER SEQUENCE user_actions_queue_sequence_seq
    OWNED BY user_actions."sequence";

ALTER TABLE user_actions
    ADD CONSTRAINT uq_user_actions_queue_sequence UNIQUE ("sequence");

CREATE INDEX idx_user_actions_review_lifo
    ON user_actions (account_id, status, queued_at DESC, "sequence" DESC);
