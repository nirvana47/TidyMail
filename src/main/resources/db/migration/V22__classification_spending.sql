-- Historical totals remain on jobs; the new allowance starts at zero newly reported spend.
CREATE TABLE classification_budget (
 id INTEGER PRIMARY KEY CHECK(id=1),
 reported NUMERIC(24,12) NOT NULL DEFAULT 0 CHECK(reported>=0),
 checkpoint NUMERIC(24,12) NOT NULL DEFAULT 1,
 generation BIGINT NOT NULL DEFAULT 0,
 owner_account BIGINT REFERENCES user_accounts(id) ON DELETE SET NULL
);
INSERT INTO classification_budget(id,owner_account) SELECT 1,min(id) FROM user_accounts;
CREATE TABLE classification_attempts (
 id UUID PRIMARY KEY,
 job_id UUID NOT NULL REFERENCES dashboard_classification_jobs(public_id) ON DELETE CASCADE,
 account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
 state TEXT NOT NULL CHECK(state IN ('DISPATCHED','REPORTED','UNKNOWN','ACKNOWLEDGED')),
 cost NUMERIC(24,12) CHECK(cost>=0),
 dispatched_at TIMESTAMPTZ NOT NULL DEFAULT now(), settled_at TIMESTAMPTZ,
 acknowledged_by BIGINT REFERENCES user_accounts(id) ON DELETE SET NULL
);
CREATE INDEX classification_attempts_unsettled ON classification_attempts(dispatched_at) WHERE state='DISPATCHED';
ALTER TABLE message_metadata ADD COLUMN classification_override_at TIMESTAMPTZ;
ALTER TABLE message_metadata ADD COLUMN classification_override_by BIGINT REFERENCES user_accounts(id) ON DELETE SET NULL;
ALTER TABLE dashboard_account_settings ADD COLUMN classification_revision BIGINT NOT NULL DEFAULT 0;
-- Conservatively flag pre-ledger in-flight work at upgrade rather than assuming it was free.
INSERT INTO classification_attempts(id,job_id,account_id,state)
 SELECT lease_token,public_id,account_id,'UNKNOWN' FROM dashboard_classification_jobs
 WHERE status='RUNNING' AND lease_token IS NOT NULL;

ALTER TABLE dashboard_classification_jobs ADD COLUMN grouped_done INTEGER NOT NULL DEFAULT 0;
ALTER TABLE classification_attempts
 ADD COLUMN message_count INTEGER NOT NULL DEFAULT 0,
 ADD COLUMN request_ms BIGINT,
 ADD COLUMN database_ms BIGINT,
 ADD COLUMN uncertain_count INTEGER;
ALTER TABLE classification_attempts ADD COLUMN benchmark_variant TEXT;
