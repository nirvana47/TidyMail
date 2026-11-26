-- Remove the unused model client/pipeline and local AI results. Native labels/folders/rules are untouched.
DO $$ DECLARE f record; BEGIN
 FOR f IN SELECT p.oid::regprocedure signature FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
 WHERE n.nspname='public' AND (p.proname LIKE 'recommendation\_%' ESCAPE '\'
  OR p.proname LIKE 'labeling\_%' ESCAPE '\'
  OR p.proname IN ('semantic_labels_changed','dashboard_classification_hash','dashboard_refresh_classification_hash',
      'dashboard_enqueue_classification','dashboard_pattern_hash','start_gmail_local_processing','local_calendar_changed'))
 LOOP EXECUTE 'DROP FUNCTION IF EXISTS '||f.signature||' CASCADE'; END LOOP;
END $$;
DROP TRIGGER IF EXISTS dashboard_reviewed_metadata_unit ON message_metadata;
DROP TABLE IF EXISTS dashboard_classification_targets,dashboard_classification_jobs,classification_incoming,
 labeling_work,labeling_exposures,labeling_scopes,message_semantic_labels,classification_attempts,classification_budget,
 dashboard_pattern_labels,dashboard_pattern_reviews,recommendation_corrections,recommendation_policies,
 recommendation_dirty,recommendation_backfill,recommendation_address_dirty,recommendation_processing,
 recommendation_message_versions,message_recommendations CASCADE;
ALTER TABLE message_metadata
 DROP COLUMN IF EXISTS classification_category, DROP COLUMN IF EXISTS classification_override,
 DROP COLUMN IF EXISTS classification_kind, DROP COLUMN IF EXISTS classification_stream,
 DROP COLUMN IF EXISTS classification_confidence, DROP COLUMN IF EXISTS classification_hash,
 DROP COLUMN IF EXISTS classification_evidence_hash, DROP COLUMN IF EXISTS classification_version,
 DROP COLUMN IF EXISTS classified_at, DROP COLUMN IF EXISTS classification_override_at,
 DROP COLUMN IF EXISTS classification_override_by, DROP COLUMN IF EXISTS classification_judgments;
ALTER TABLE dashboard_account_settings DROP COLUMN IF EXISTS classification_automatic;

-- Automatic domains/list identities no longer consume model guesses or stored model identity decisions.
CREATE OR REPLACE FUNCTION dashboard_apply_reviewed_organization(p_unit_id BIGINT) RETURNS void LANGUAGE plpgsql AS $$
DECLARE unit sender_units; organization text; key_value text; label_value text;
BEGIN
 SELECT * INTO unit FROM sender_units WHERE id=p_unit_id;
 IF unit.kind NOT IN ('ADDRESS','LIST') THEN RETURN; END IF;
 IF unit.kind='ADDRESS' THEN organization:=dashboard_organization_domain(split_part(unit.canonical_key,'@',2));

 END IF;
 key_value:=COALESCE('domain:'||organization,lower(unit.kind)||':'||unit.canonical_key);
 label_value:=COALESCE(organization,unit.canonical_key);
 UPDATE sender_units SET automatic_group_key=key_value,automatic_label=label_value,
  evidence_reason=CASE WHEN organization IS NULL THEN 'Exact sender or mailing-list identity' ELSE 'Sender email domain' END WHERE id=unit.id;
 INSERT INTO sender_groups(account_id,group_key,label) VALUES(unit.account_id,key_value,label_value)
  ON CONFLICT(account_id,group_key) DO UPDATE SET label=EXCLUDED.label;
 UPDATE sender_group_members member SET group_id=g.id FROM sender_groups g
  WHERE member.account_id=unit.account_id AND member.unit_id=unit.id AND g.account_id=unit.account_id AND g.group_key=key_value;
END $$;
CREATE TRIGGER dashboard_reviewed_metadata_unit AFTER INSERT OR UPDATE OF dashboard_unit_kind,dashboard_unit_key,
 raw_from_values,dashboard_from_address ON message_metadata FOR EACH ROW EXECUTE FUNCTION dashboard_reviewed_metadata_unit();
SELECT dashboard_apply_reviewed_organization(id) FROM sender_units;
DELETE FROM sender_groups g WHERE NOT EXISTS(SELECT 1 FROM sender_group_members m WHERE m.account_id=g.account_id AND m.group_id=g.id);
UPDATE dashboard_account_settings SET grouping_revision=grouping_revision+1,classification_revision=classification_revision+1;
UPDATE dashboard_previews p SET invalidated_at=now() WHERE invalidated_at IS NULL
 AND NOT EXISTS(SELECT 1 FROM cleanup_jobs j WHERE j.preview_id=p.public_id);

-- Independent, local-only header/provider labels are rebuilt, not copied from AI predictions.
CREATE TABLE message_local_labels (
 account_id bigint NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
 message_id uuid NOT NULL,input_hash text NOT NULL,version text NOT NULL,reasons jsonb NOT NULL,
 PRIMARY KEY(account_id,message_id),
 FOREIGN KEY(message_id,account_id) REFERENCES message_metadata(dashboard_message_id,account_id) ON DELETE CASCADE
);
CREATE TABLE local_label_dirty (
 account_id bigint NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,message_id uuid NOT NULL,
 PRIMARY KEY(account_id,message_id),
 FOREIGN KEY(message_id,account_id) REFERENCES message_metadata(dashboard_message_id,account_id) ON DELETE CASCADE
);
CREATE FUNCTION local_mail_label_hash(m message_metadata) RETURNS text LANGUAGE sql IMMUTABLE AS $$
 SELECT md5(jsonb_build_array(m.dashboard_from_address,m.from_display_name,m.subject_header,m.provider,
  'OUTLOOK_CALENDAR_INVITE'=ANY(m.labels))::text)
$$;
CREATE FUNCTION local_mail_label_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO local_label_dirty(account_id,message_id) VALUES(NEW.account_id,NEW.dashboard_message_id) ON CONFLICT DO NOTHING;
 RETURN NEW;
END $$;
CREATE TRIGGER local_mail_labels_changed AFTER INSERT OR UPDATE OF dashboard_from_address,from_display_name,subject_header,labels
 ON message_metadata FOR EACH ROW EXECUTE FUNCTION local_mail_label_changed();
INSERT INTO local_label_dirty SELECT account_id,dashboard_message_id FROM message_metadata;
