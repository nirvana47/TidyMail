ALTER TABLE cleanup_jobs DROP CONSTRAINT cleanup_jobs_action_type_check;
ALTER TABLE cleanup_jobs ADD CHECK(action_type IN ('MARK_READ','ARCHIVE','TRASH','MARK_READ_ARCHIVE','ORGANIZE'));
-- Confirmed destination choice lives in the immutable job snapshot; only resolution changes.
CREATE TABLE cleanup_job_destinations (
 job_id UUID PRIMARY KEY REFERENCES cleanup_jobs(public_id) ON DELETE CASCADE,
 state TEXT NOT NULL CHECK(state IN ('INTENT','READY','UNKNOWN')),
 destination_id TEXT,
 CHECK((state='READY')=(destination_id IS NOT NULL))
);
