-- Add a message-purpose choice; retain every existing label and model version.
ALTER TABLE message_metadata DROP CONSTRAINT message_metadata_classification_override_check;
ALTER TABLE message_metadata ADD CONSTRAINT message_metadata_classification_override_check CHECK
 (classification_override IN ('PERSONAL','MARKETING','NEWSLETTER','RECEIPT','SECURITY','NOTIFICATION','SOCIAL','OTHER','UNCERTAIN'));
ALTER TABLE message_metadata DROP CONSTRAINT classification_category_valid;
ALTER TABLE message_metadata ADD CONSTRAINT classification_category_valid CHECK
 (classification_category IN ('PERSONAL','MARKETING','NEWSLETTER','RECEIPT','SECURITY','NOTIFICATION','SOCIAL','OTHER','UNCERTAIN'));
