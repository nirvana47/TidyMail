-- Keep the known size with cleanup history after mailbox metadata is removed.
ALTER TABLE cleanup_job_targets ADD COLUMN size_estimate BIGINT
    CHECK (size_estimate IS NULL OR size_estimate >= 0);
