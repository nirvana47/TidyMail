CREATE INDEX idx_action_events_analytics_fact
    ON action_events (account_id, occurred_at, action_type, resulting_status, id);

CREATE INDEX idx_action_executions_analytics_fact
    ON action_executions (account_id, completed_at, status, id);

CREATE INDEX idx_unsubscribe_outcome_events_analytics_fact
    ON unsubscribe_outcome_events (account_id, occurred_at, status, id);
