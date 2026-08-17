ALTER TABLE sender_stats ADD COLUMN reply_count INTEGER NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_sender_stats_account_reply_count ON sender_stats (account_id, reply_count DESC);
CREATE INDEX IF NOT EXISTS idx_sender_stats_account_bulk_flags ON sender_stats (account_id, has_list_unsubscribe, has_precedence_bulk);
