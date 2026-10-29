-- Additive upgrade; confirmed identities and retained metadata remain untouched.
ALTER TABLE cleanup_jobs DROP CONSTRAINT cleanup_jobs_action_type_check;
ALTER TABLE cleanup_jobs ADD CHECK(action_type IN ('MARK_READ','ARCHIVE','TRASH'));
ALTER TABLE cleanup_jobs ADD COLUMN retry_reason TEXT;
ALTER TABLE cleanup_job_targets ADD COLUMN read_attempts INTEGER NOT NULL DEFAULT 0 CHECK(read_attempts>=0);
ALTER TABLE cleanup_job_targets ADD COLUMN verification_attempts INTEGER NOT NULL DEFAULT 0 CHECK(verification_attempts>=0);
-- V17 captured the Mark as Read precondition; preserve it for in-flight upgrade recovery.
ALTER TABLE cleanup_job_targets ADD COLUMN pre_change_observed BOOLEAN NOT NULL DEFAULT false;
UPDATE cleanup_job_targets SET pre_change_observed=COALESCE(pre_unread,false);
