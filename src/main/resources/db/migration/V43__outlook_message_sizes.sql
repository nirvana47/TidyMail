ALTER TABLE outlook_sync_round ADD COLUMN metadata_complete boolean NOT NULL DEFAULT false;

CREATE TABLE outlook_size_scan (
    account_id bigint PRIMARY KEY REFERENCES user_accounts(id) ON DELETE CASCADE,
    next_link text,
    listed boolean NOT NULL DEFAULT false
);

ALTER TABLE message_metadata ADD COLUMN outlook_size_checked boolean NOT NULL DEFAULT false;
