-- The storage-review threshold is decimal 1 MB for both received and Sent mail.
DROP INDEX idx_received_large_messages;
CREATE INDEX idx_received_large_messages ON message_metadata(account_id,size_estimate)
 WHERE size_estimate>1000000;
