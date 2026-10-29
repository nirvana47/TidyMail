-- Durable dashboard work is separate from legacy whole-scope execution/Undo.
ALTER TABLE dashboard_preview_targets ADD COLUMN provider_message_id TEXT;
UPDATE dashboard_preview_targets t SET provider_message_id=m.provider_message_id
 FROM message_metadata m WHERE m.account_id=t.account_id AND m.dashboard_message_id=t.message_id;
UPDATE dashboard_previews p SET invalidated_at=now() WHERE EXISTS
 (SELECT 1 FROM dashboard_preview_targets t WHERE t.preview_id=p.public_id AND t.provider_message_id IS NULL);
CREATE TABLE cleanup_jobs (
 public_id UUID PRIMARY KEY DEFAULT gen_random_uuid(), account_id BIGINT NOT NULL,
 preview_id UUID NOT NULL UNIQUE, scope_id BIGINT NOT NULL,
 action_type TEXT NOT NULL CHECK(action_type='MARK_READ'),
 selected_count INTEGER NOT NULL CHECK(selected_count>0), snapshot JSONB NOT NULL,
 status TEXT NOT NULL DEFAULT 'QUEUED' CHECK(status IN
 ('QUEUED','RUNNING','VERIFYING','COMPLETED','COMPLETED_WITH_EXCEPTIONS','CANCELLING','CANCELLED')),
 cancel_requested_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), settled_at TIMESTAMPTZ,
 worker_id UUID, generation BIGINT NOT NULL DEFAULT 0, lease_expires_at TIMESTAMPTZ,
 next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(public_id,account_id), CHECK((worker_id IS NULL)=(lease_expires_at IS NULL)),
 FOREIGN KEY(preview_id,account_id) REFERENCES dashboard_previews(public_id,account_id) ON DELETE CASCADE,
 FOREIGN KEY(scope_id,account_id) REFERENCES action_scopes(id,account_id) ON DELETE CASCADE
);
CREATE INDEX idx_cleanup_runnable ON cleanup_jobs(next_attempt_at,lease_expires_at,created_at)
 WHERE status IN ('QUEUED','RUNNING','VERIFYING','CANCELLING','CANCELLED');
CREATE INDEX idx_cleanup_activity ON cleanup_jobs(account_id,created_at DESC);
CREATE TABLE cleanup_batches (
 public_id UUID PRIMARY KEY, job_id UUID NOT NULL, account_id BIGINT NOT NULL,
 generation BIGINT NOT NULL, state TEXT NOT NULL CHECK(state IN ('DISPATCH_INTENT','SETTLED','AWAITING_VERIFICATION')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), completed_at TIMESTAMPTZ,
 UNIQUE(public_id,job_id,account_id),
 FOREIGN KEY(job_id,account_id) REFERENCES cleanup_jobs(public_id,account_id) ON DELETE CASCADE
);
CREATE TABLE cleanup_job_targets (
 job_id UUID NOT NULL, account_id BIGINT NOT NULL, ordinal INTEGER NOT NULL CHECK(ordinal>=0),
 provider_message_id TEXT NOT NULL, message_id UUID NOT NULL, target_fingerprint TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('QUEUED','DISPATCH_INTENT','CHANGED','FAILED','SKIPPED','AWAITING_VERIFICATION','CANCELLED_BEFORE_DISPATCH')),
 reason TEXT NOT NULL, pre_unread BOOLEAN, batch_id UUID,
 observed_at TIMESTAMPTZ, PRIMARY KEY(job_id,ordinal), UNIQUE(job_id,provider_message_id),
 UNIQUE(job_id,account_id,target_fingerprint),
 FOREIGN KEY(job_id,account_id) REFERENCES cleanup_jobs(public_id,account_id) ON DELETE CASCADE,
 FOREIGN KEY(batch_id,job_id,account_id) REFERENCES cleanup_batches(public_id,job_id,account_id)
);
CREATE INDEX idx_cleanup_target_states ON cleanup_job_targets(job_id,state,ordinal);
CREATE TABLE cleanup_active_targets (
 account_id BIGINT NOT NULL, target_fingerprint TEXT NOT NULL, job_id UUID NOT NULL,
 PRIMARY KEY(account_id,target_fingerprint),
 FOREIGN KEY(job_id,account_id,target_fingerprint)
 REFERENCES cleanup_job_targets(job_id,account_id,target_fingerprint) ON DELETE CASCADE
);
CREATE TABLE cleanup_job_events (
 id BIGSERIAL PRIMARY KEY, job_id UUID NOT NULL, account_id BIGINT NOT NULL,
 status TEXT NOT NULL, counts JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(job_id,account_id) REFERENCES cleanup_jobs(public_id,account_id) ON DELETE CASCADE
);
-- Confirmed identity and target membership are immutable; mutable outcomes remain writable.
CREATE FUNCTION cleanup_fixed_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='cleanup_jobs' THEN
   IF (NEW.public_id,NEW.account_id,NEW.preview_id,NEW.scope_id,NEW.action_type,NEW.selected_count,NEW.snapshot)
      IS DISTINCT FROM (OLD.public_id,OLD.account_id,OLD.preview_id,OLD.scope_id,OLD.action_type,OLD.selected_count,OLD.snapshot)
   THEN RAISE EXCEPTION 'Confirmed cleanup identity is immutable'; END IF;
 ELSE
   IF (NEW.job_id,NEW.account_id,NEW.ordinal,NEW.provider_message_id,NEW.message_id,NEW.target_fingerprint)
      IS DISTINCT FROM (OLD.job_id,OLD.account_id,OLD.ordinal,OLD.provider_message_id,OLD.message_id,OLD.target_fingerprint)
   THEN RAISE EXCEPTION 'Confirmed cleanup target is immutable'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER cleanup_job_identity BEFORE UPDATE ON cleanup_jobs FOR EACH ROW EXECUTE FUNCTION cleanup_fixed_identity();
CREATE TRIGGER cleanup_target_identity BEFORE UPDATE ON cleanup_job_targets FOR EACH ROW EXECUTE FUNCTION cleanup_fixed_identity();
