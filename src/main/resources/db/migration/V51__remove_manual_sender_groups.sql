-- Retire manual Combine/Split/Rename/Reset. Preserve automatic identities and fixed cleanup targets.
CREATE OR REPLACE FUNCTION dashboard_apply_reviewed_organization(p_unit_id BIGINT) RETURNS void LANGUAGE plpgsql AS $$
DECLARE unit sender_units; organization TEXT; consistent BOOLEAN; personal BOOLEAN; new_key TEXT; new_label TEXT; changed INTEGER;
BEGIN
 SELECT * INTO unit FROM sender_units WHERE id=p_unit_id;
 IF unit.kind NOT IN ('ADDRESS','LIST') THEN RETURN; END IF;
 IF unit.kind='ADDRESS' AND dashboard_reviewed_organization(unit.canonical_key) IS NULL
   AND unit.automatic_group_key NOT LIKE 'organization:%' AND NOT dashboard_is_marketplace_relay(unit.canonical_key) AND NOT EXISTS(
     SELECT 1 FROM message_metadata m WHERE m.account_id=unit.account_id
     AND m.dashboard_unit_kind=unit.kind AND m.dashboard_unit_key=unit.canonical_key
     AND (m.classification_kind IN ('PERSON','RELAY') OR m.classification_override='PERSONAL')) THEN RETURN; END IF;
 SELECT min(dashboard_reviewed_organization(m.dashboard_from_address)),
   bool_and(dashboard_reviewed_organization(m.dashboard_from_address) IS NOT NULL)
   AND count(DISTINCT dashboard_reviewed_organization(m.dashboard_from_address))=1,
   bool_or(m.classification_kind IN ('PERSON','RELAY') OR m.classification_override='PERSONAL' OR dashboard_is_marketplace_relay(m.dashboard_from_address))
 INTO organization,consistent,personal FROM message_sender_units link JOIN message_metadata m
 ON m.account_id=link.account_id AND m.id=link.metadata_id WHERE link.unit_id=p_unit_id AND link.account_id=unit.account_id;
 IF COALESCE(personal,false) THEN
   new_key:=lower(unit.kind)||':'||unit.canonical_key; new_label:=unit.canonical_key;
 ELSIF NOT COALESCE(consistent,false) THEN
   IF unit.automatic_group_key NOT LIKE 'organization:%' THEN RETURN; END IF;
   new_key:=lower(unit.kind)||':'||unit.canonical_key; new_label:=unit.canonical_key;
 ELSE new_key:='organization:'||organization; new_label:=organization;
 END IF;
 IF unit.automatic_group_key=new_key THEN RETURN; END IF;
 UPDATE sender_units SET automatic_group_key=new_key,automatic_label=new_label,
   evidence_reason=CASE WHEN personal THEN 'Personal or relay correspondence; atomic identity preserved'
   WHEN consistent THEN 'Reviewed organization domain; message types remain separate'
   ELSE 'Conflicting organization evidence; atomic sender retained' END WHERE id=p_unit_id;
 INSERT INTO sender_groups(account_id,group_key,label) VALUES(unit.account_id,new_key,new_label)
 ON CONFLICT(account_id,group_key) DO NOTHING;
 UPDATE sender_group_members member SET group_id=g.id FROM sender_groups g,sender_groups old
 WHERE member.account_id=unit.account_id AND member.unit_id=p_unit_id AND old.id=member.group_id
 AND old.account_id=member.account_id
 AND g.account_id=member.account_id AND g.group_key=new_key AND member.group_id<>g.id;
 GET DIAGNOSTICS changed=ROW_COUNT;
 IF changed>0 THEN
   INSERT INTO dashboard_account_settings(account_id,grouping_revision) VALUES(unit.account_id,1)
   ON CONFLICT(account_id) DO UPDATE SET grouping_revision=dashboard_account_settings.grouping_revision+1;
 END IF;
END $$;

INSERT INTO sender_groups(account_id,group_key,label)
 SELECT DISTINCT account_id,automatic_group_key,automatic_label FROM sender_units WHERE kind<>'SPLIT_ADDRESS'
 ON CONFLICT(account_id,group_key) DO UPDATE SET label=EXCLUDED.label;
UPDATE sender_group_members member SET group_id=g.id FROM sender_units u,sender_groups g
 WHERE u.account_id=member.account_id AND u.id=member.unit_id AND u.kind<>'SPLIT_ADDRESS'
 AND g.account_id=u.account_id AND g.group_key=u.automatic_group_key AND member.group_id<>g.id;

DROP TABLE sender_address_units;
DROP TABLE sender_separations;
DROP TABLE sender_group_corrections;
DELETE FROM sender_units WHERE kind='SPLIT_ADDRESS';
ALTER TABLE sender_units DROP CONSTRAINT sender_units_kind_check;
ALTER TABLE sender_units ADD CHECK (kind IN ('ADDRESS','LIST','MESSAGE'));
DELETE FROM sender_groups g WHERE NOT EXISTS(
 SELECT 1 FROM sender_group_members member WHERE member.account_id=g.account_id AND member.group_id=g.id);

-- Old unconfirmed reviews must not refer to the retired layout. Confirmed job IDs and targets do not change.
INSERT INTO dashboard_account_settings(account_id,grouping_revision)
 SELECT DISTINCT account_id,1 FROM sender_units
 ON CONFLICT(account_id) DO UPDATE SET grouping_revision=dashboard_account_settings.grouping_revision+1;
UPDATE dashboard_previews SET invalidated_at=now() WHERE invalidated_at IS NULL
 AND NOT EXISTS(SELECT 1 FROM cleanup_jobs j WHERE j.preview_id=dashboard_previews.public_id);
