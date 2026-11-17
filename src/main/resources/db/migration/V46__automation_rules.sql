CREATE TABLE automation_rules (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 account_id bigint NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
 definition jsonb NOT NULL,
 version bigint NOT NULL DEFAULT 1,
 state text NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','ACTIVE','PAUSED','ATTENTION','REMOVED')),
 remote_id text,
 remote_snapshot jsonb,
 pending_operation text,
 operation_snapshot jsonb,
 detail text,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 checked_at timestamptz,
 UNIQUE(account_id,remote_id)
);
CREATE INDEX automation_rules_account ON automation_rules(account_id,updated_at DESC);
CREATE TABLE automation_rule_previews (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 rule_id uuid NOT NULL REFERENCES automation_rules(id) ON DELETE CASCADE,
 account_id bigint NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
 version bigint NOT NULL,
 snapshot jsonb NOT NULL,
 inventory_hash text,
 expires_at timestamptz NOT NULL DEFAULT now()+interval '15 minutes'
);
CREATE TABLE automation_rule_events (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 rule_id uuid NOT NULL REFERENCES automation_rules(id) ON DELETE CASCADE,
 account_id bigint NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
 event text NOT NULL,
 definition jsonb NOT NULL,
 occurred_at timestamptz NOT NULL DEFAULT now()
);
