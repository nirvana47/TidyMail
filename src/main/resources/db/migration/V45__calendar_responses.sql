-- Explicit Calendar reads are separate from mail evidence and cleanup recommendations.
CREATE TABLE calendar_response_checks (
 account_id BIGINT NOT NULL,
 provider_message_id TEXT NOT NULL,
 response_state TEXT NOT NULL CHECK(response_state IN ('RESPONDED','NEEDS_RESPONSE','CANCELLED','UNKNOWN')),
 checked_at TIMESTAMPTZ NOT NULL,
 facts JSONB NOT NULL,
 PRIMARY KEY(account_id,provider_message_id),
 FOREIGN KEY(account_id,provider_message_id) REFERENCES message_metadata(account_id,provider_message_id) ON DELETE CASCADE
);
CREATE INDEX idx_calendar_response_filter ON calendar_response_checks(account_id,response_state,checked_at);
