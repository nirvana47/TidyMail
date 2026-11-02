-- Lookup is account-local, bounded by the reader, and never scans raw ICS/body text.
CREATE INDEX idx_message_evidence_calendar_lookup ON message_evidence USING gin(facts jsonb_path_ops);
