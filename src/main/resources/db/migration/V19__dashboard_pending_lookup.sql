CREATE INDEX idx_cleanup_pending_message ON cleanup_job_targets(account_id,message_id)
 WHERE state IN ('QUEUED','DISPATCH_INTENT','AWAITING_VERIFICATION');
