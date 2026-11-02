-- Reviewed sender domains are identity evidence, independent of semantic completeness.
-- Atomic list/address units and correction history remain intact.
CREATE FUNCTION dashboard_reviewed_organization(address TEXT) RETURNS TEXT
LANGUAGE sql IMMUTABLE STRICT AS $$
 SELECT domain FROM unnest(ARRAY['amazon.com','quora.com','nextdoor.com','costco.com','calendly.com']) domain
 WHERE lower(split_part(address,'@',2))=domain
 OR lower(split_part(address,'@',2)) LIKE '%.'||domain LIMIT 1
$$;

CREATE FUNCTION dashboard_apply_reviewed_organization(p_unit_id BIGINT) RETURNS void LANGUAGE plpgsql AS $$
DECLARE unit sender_units; organization TEXT; consistent BOOLEAN; personal BOOLEAN; new_key TEXT; new_label TEXT; changed INTEGER;
BEGIN
 SELECT * INTO unit FROM sender_units WHERE id=p_unit_id;
 IF unit.kind NOT IN ('ADDRESS','LIST') THEN RETURN; END IF;
 IF unit.kind='ADDRESS' AND dashboard_reviewed_organization(unit.canonical_key) IS NULL
   AND unit.automatic_group_key NOT LIKE 'organization:%' THEN RETURN; END IF;
 SELECT min(dashboard_reviewed_organization(m.dashboard_from_address)),
   bool_and(dashboard_reviewed_organization(m.dashboard_from_address) IS NOT NULL)
   AND count(DISTINCT dashboard_reviewed_organization(m.dashboard_from_address))=1,
   bool_or(m.classification_kind IN ('PERSON','RELAY') OR m.classification_override='PERSONAL')
 INTO organization,consistent,personal FROM message_sender_units link JOIN message_metadata m
 ON m.account_id=link.account_id AND m.id=link.metadata_id WHERE link.unit_id=p_unit_id AND link.account_id=unit.account_id;
 IF COALESCE(personal,false) AND organization IS NOT NULL THEN
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
 AND old.account_id=member.account_id AND old.group_key NOT LIKE 'manual:%' AND old.group_key NOT LIKE 'separate:%'
 AND g.account_id=member.account_id AND g.group_key=new_key AND member.group_id<>g.id;
 GET DIAGNOSTICS changed=ROW_COUNT;
 IF changed>0 THEN
   INSERT INTO dashboard_account_settings(account_id,grouping_revision) VALUES(unit.account_id,1)
   ON CONFLICT(account_id) DO UPDATE SET grouping_revision=dashboard_account_settings.grouping_revision+1;
 END IF;
END $$;

SELECT dashboard_apply_reviewed_organization(id) FROM sender_units WHERE kind IN ('ADDRESS','LIST');

CREATE FUNCTION dashboard_reviewed_metadata_unit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE unit_id BIGINT;
BEGIN
 SELECT link.unit_id INTO unit_id FROM message_sender_units link WHERE link.account_id=NEW.account_id AND link.metadata_id=NEW.id;
 IF unit_id IS NOT NULL THEN PERFORM dashboard_apply_reviewed_organization(unit_id); END IF;
 RETURN NEW;
END $$;
-- Runs after dashboard_metadata_unit, which creates the atomic link.
CREATE TRIGGER dashboard_reviewed_metadata_unit AFTER INSERT OR UPDATE OF classification_kind,classification_override,dashboard_unit_kind,dashboard_unit_key,
 raw_from_values,dashboard_from_address ON message_metadata FOR EACH ROW EXECUTE FUNCTION dashboard_reviewed_metadata_unit();
