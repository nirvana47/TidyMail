ALTER TABLE message_recommendations
 ADD COLUMN local_rules_version TEXT,
 ADD COLUMN local_label_reasons JSONB,
 ADD COLUMN local_calendar_end TIMESTAMPTZ;

-- Enroll retained Gmail accounts with no saved preference and new Gmail connections.
INSERT INTO recommendation_processing(account_id,enabled)
 SELECT id,true FROM user_accounts WHERE provider='google'
 ON CONFLICT DO NOTHING;

CREATE FUNCTION start_gmail_local_processing() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO recommendation_processing(account_id,enabled) VALUES(NEW.id,true) ON CONFLICT DO NOTHING;
 RETURN NEW;
END $$;
CREATE TRIGGER start_gmail_local_processing AFTER INSERT ON user_accounts
 FOR EACH ROW WHEN(NEW.provider='google') EXECUTE FUNCTION start_gmail_local_processing();

-- An observed series update can change the timing label on earlier emails.
-- Use the existing calendar lookup index and dirty queue, including on deletion.
CREATE FUNCTION local_calendar_changed() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE uid TEXT;
DECLARE prior JSONB;
DECLARE current_facts JSONB;
BEGIN
 IF TG_OP<>'INSERT' THEN prior=OLD.facts->'calendars'; END IF;
 IF TG_OP<>'DELETE' THEN current_facts=NEW.facts->'calendars'; END IF;
 IF prior IS NOT DISTINCT FROM current_facts THEN RETURN NULL; END IF;
 FOR uid IN
   SELECT DISTINCT event->>'uidHash'
   FROM jsonb_array_elements(COALESCE(prior,'[]'::jsonb) || COALESCE(current_facts,'[]'::jsonb)) part,
        jsonb_array_elements(COALESCE(part->'events','[]'::jsonb)) event
   WHERE event->>'uidHash' IS NOT NULL
 LOOP
   INSERT INTO recommendation_dirty(account_id,message_id)
   SELECT m.account_id,m.dashboard_message_id FROM message_evidence e
   JOIN message_metadata m ON m.account_id=e.account_id AND m.provider_message_id=e.provider_message_id
   WHERE e.account_id=COALESCE(NEW.account_id,OLD.account_id)
     AND e.provider_message_id<>COALESCE(NEW.provider_message_id,OLD.provider_message_id)
     AND e.facts @> jsonb_build_object('calendars',jsonb_build_array(
         jsonb_build_object('events',jsonb_build_array(jsonb_build_object('uidHash',uid)))))
   ORDER BY m.id
   ON CONFLICT DO NOTHING;
 END LOOP;
 RETURN NULL;
END $$;
CREATE TRIGGER local_calendar_changes AFTER INSERT OR UPDATE OR DELETE ON message_evidence
 FOR EACH ROW EXECUTE FUNCTION local_calendar_changed();
