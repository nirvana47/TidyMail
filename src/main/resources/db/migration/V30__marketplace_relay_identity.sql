-- Buyer/Seller Messaging aliases are correspondent relays, not generic Amazon organization identity.
-- Reviewed source: https://sellercentral.amazon.com/help/hub/reference/external/G202125900
CREATE FUNCTION dashboard_is_marketplace_relay(address TEXT) RETURNS BOOLEAN
LANGUAGE sql IMMUTABLE STRICT AS $$
 SELECT lower(split_part(address,'@',2))='marketplace.amazon.com'
$$;
INSERT INTO dashboard_domain_registry(domain,kind) VALUES('marketplace.amazon.com','SHARED')
 ON CONFLICT(domain) DO UPDATE SET kind='SHARED';

CREATE OR REPLACE FUNCTION dashboard_reviewed_organization(address TEXT) RETURNS TEXT
LANGUAGE sql IMMUTABLE STRICT AS $$
 SELECT domain FROM unnest(ARRAY['amazon.com','quora.com','nextdoor.com','costco.com','calendly.com']) domain
 WHERE (lower(split_part(address,'@',2))=domain OR lower(split_part(address,'@',2)) LIKE '%.'||domain)
 AND NOT dashboard_is_marketplace_relay(address) LIMIT 1
$$;

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
 AND old.account_id=member.account_id AND old.group_key NOT LIKE 'manual:%' AND old.group_key NOT LIKE 'separate:%'
 AND g.account_id=member.account_id AND g.group_key=new_key AND member.group_id<>g.id;
 GET DIAGNOSTICS changed=ROW_COUNT;
 IF changed>0 THEN
   INSERT INTO dashboard_account_settings(account_id,grouping_revision) VALUES(unit.account_id,1)
   ON CONFLICT(account_id) DO UPDATE SET grouping_revision=dashboard_account_settings.grouping_revision+1;
 END IF;
END $$;


SELECT dashboard_apply_reviewed_organization(u.id) FROM sender_units u
 WHERE u.kind IN ('ADDRESS','LIST') AND EXISTS(
 SELECT 1 FROM message_sender_units link JOIN message_metadata m
 ON m.account_id=link.account_id AND m.id=link.metadata_id
 WHERE link.account_id=u.account_id AND link.unit_id=u.id AND dashboard_is_marketplace_relay(m.dashboard_from_address));
