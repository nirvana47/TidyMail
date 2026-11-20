-- Results are observations, not a provider execution audit log.
CREATE TABLE automation_rule_results (
 rule_id uuid PRIMARY KEY REFERENCES automation_rules(id) ON DELETE CASCADE,
 account_id bigint NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
 destination_id text NOT NULL,
 started_at timestamptz NOT NULL DEFAULT now(),
 baseline_count bigint NOT NULL CHECK(baseline_count >= 0),
 current_count bigint NOT NULL CHECK(current_count >= 0),
 counted_at timestamptz NOT NULL DEFAULT now(),
 count_error boolean NOT NULL DEFAULT false
);
CREATE TABLE automation_rule_observations (
 rule_id uuid NOT NULL REFERENCES automation_rule_results(rule_id) ON DELETE CASCADE,
 provider_message_id text NOT NULL,
 received_at timestamptz NOT NULL,
 first_observed_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(rule_id,provider_message_id)
);
