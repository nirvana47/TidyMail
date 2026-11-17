CREATE TABLE automation_rule_suggestion_dismissals (
 account_id bigint NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
 sender_address text NOT NULL,
 action text NOT NULL,
 evidence_count bigint NOT NULL,
 dismissed_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(account_id,sender_address,action)
);
