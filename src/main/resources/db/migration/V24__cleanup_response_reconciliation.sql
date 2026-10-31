-- Fence metadata reads that began before an acknowledged cleanup updated local labels.
ALTER TABLE message_metadata ADD COLUMN cleanup_updated_at TIMESTAMPTZ;
