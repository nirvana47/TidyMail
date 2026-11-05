-- Cascading metadata deletion must not recreate recommendation work for a missing message.
-- Deleting only a label still invalidates the surviving message's recommendation.
CREATE OR REPLACE FUNCTION semantic_labels_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO recommendation_dirty(account_id,message_id)
 SELECT account_id,dashboard_message_id FROM message_metadata
 WHERE account_id=COALESCE(NEW.account_id,OLD.account_id)
   AND dashboard_message_id=COALESCE(NEW.message_id,OLD.message_id)
 ON CONFLICT DO NOTHING;
 RETURN NULL;
END $$;
