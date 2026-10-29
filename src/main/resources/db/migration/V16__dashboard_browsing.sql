-- Additive dashboard foundations. Credentials, cursors and legacy history are untouched.
ALTER TABLE message_metadata ADD COLUMN dashboard_message_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE message_metadata ADD COLUMN raw_from_values TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE message_metadata ADD COLUMN label_evidence_known BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE message_metadata ADD COLUMN dashboard_from_address VARCHAR(320);
ALTER TABLE message_metadata ADD COLUMN dashboard_unit_kind VARCHAR(16);
ALTER TABLE message_metadata ADD COLUMN dashboard_unit_key TEXT;
ALTER TABLE message_metadata ADD COLUMN dashboard_identity_reason VARCHAR(128);
ALTER TABLE message_metadata ADD CONSTRAINT uq_metadata_dashboard_owned UNIQUE (account_id,dashboard_message_id);
ALTER TABLE message_metadata ADD CONSTRAINT uq_metadata_id_owned UNIQUE (id,account_id);
UPDATE message_metadata SET label_evidence_known=true WHERE cardinality(labels)>0;

CREATE TABLE dashboard_account_settings (
 account_id BIGINT PRIMARY KEY REFERENCES user_accounts(id) ON DELETE CASCADE,
 display_timezone VARCHAR(64) NOT NULL DEFAULT 'UTC',
 grouping_revision BIGINT NOT NULL DEFAULT 0,
 rule_version INTEGER NOT NULL DEFAULT 1
);
CREATE TABLE dashboard_verified_aliases (
 account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
 address VARCHAR(320) NOT NULL, verification_source VARCHAR(128) NOT NULL,
 verified_at TIMESTAMPTZ NOT NULL, PRIMARY KEY(account_id,address)
);
CREATE TABLE dashboard_domain_registry (
 domain VARCHAR(253) PRIMARY KEY, kind VARCHAR(16) NOT NULL CHECK(kind IN ('SHARED','DEDICATED')),
 rule_version INTEGER NOT NULL DEFAULT 1, reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO dashboard_domain_registry(domain,kind) SELECT domain,'SHARED'
 FROM unnest(ARRAY['gmail.com','googlemail.com','outlook.com','hotmail.com','live.com',
 'yahoo.com','icloud.com','me.com','aol.com','proton.me','protonmail.com']) domain;
CREATE TABLE sender_units (
 id BIGSERIAL PRIMARY KEY, public_id UUID NOT NULL DEFAULT gen_random_uuid(),
 account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
 kind VARCHAR(16) NOT NULL CHECK(kind IN ('ADDRESS','LIST','MESSAGE')),
 canonical_key TEXT NOT NULL, automatic_group_key TEXT NOT NULL,
 automatic_label TEXT NOT NULL, evidence_reason VARCHAR(128) NOT NULL,
 UNIQUE(account_id,kind,canonical_key), UNIQUE(id,account_id), UNIQUE(account_id,public_id)
);
CREATE TABLE sender_groups (
 id BIGSERIAL PRIMARY KEY, public_id UUID NOT NULL DEFAULT gen_random_uuid(),
 account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
 group_key TEXT NOT NULL, label TEXT NOT NULL,
 UNIQUE(account_id,group_key), UNIQUE(id,account_id), UNIQUE(account_id,public_id)
);
CREATE TABLE sender_group_members (
 account_id BIGINT NOT NULL, unit_id BIGINT NOT NULL, group_id BIGINT NOT NULL,
 PRIMARY KEY(account_id,unit_id),
 FOREIGN KEY(unit_id,account_id) REFERENCES sender_units(id,account_id) ON DELETE CASCADE,
 FOREIGN KEY(group_id,account_id) REFERENCES sender_groups(id,account_id) ON DELETE CASCADE
);
CREATE INDEX idx_group_members_group ON sender_group_members(account_id,group_id,unit_id);
CREATE TABLE message_sender_units (
 account_id BIGINT NOT NULL, metadata_id BIGINT NOT NULL, unit_id BIGINT NOT NULL,
 PRIMARY KEY(account_id,metadata_id),
 FOREIGN KEY(metadata_id,account_id) REFERENCES message_metadata(id,account_id) ON DELETE CASCADE,
 FOREIGN KEY(unit_id,account_id) REFERENCES sender_units(id,account_id) ON DELETE CASCADE
);
CREATE INDEX idx_message_units_unit ON message_sender_units(account_id,unit_id,metadata_id);
CREATE TABLE sender_group_corrections (
 id BIGSERIAL PRIMARY KEY, public_id UUID NOT NULL DEFAULT gen_random_uuid(),
 account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
 kind VARCHAR(16) NOT NULL CHECK(kind IN ('MERGE','SEPARATE')),
 unit_ids BIGINT[] NOT NULL CHECK(cardinality(unit_ids)>0), label VARCHAR(160),
 active BOOLEAN NOT NULL DEFAULT true, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 reset_at TIMESTAMPTZ, UNIQUE(account_id,public_id)
);
CREATE TABLE sender_separations (
 account_id BIGINT NOT NULL, low_unit_id BIGINT NOT NULL, high_unit_id BIGINT NOT NULL,
 PRIMARY KEY(account_id,low_unit_id,high_unit_id), CHECK(low_unit_id<high_unit_id),
 FOREIGN KEY(low_unit_id,account_id) REFERENCES sender_units(id,account_id) ON DELETE CASCADE,
 FOREIGN KEY(high_unit_id,account_id) REFERENCES sender_units(id,account_id) ON DELETE CASCADE
);

-- Retained identities are lossy: deliberately create isolated message units.
INSERT INTO sender_units(account_id,kind,canonical_key,automatic_group_key,automatic_label,evidence_reason)
 SELECT account_id,'MESSAGE',provider_message_id,'message:'||provider_message_id,
 COALESCE(NULLIF(from_display_name,''),NULLIF(from_address,''),'Unknown sender'),
 'Retained identity needs fresh metadata' FROM message_metadata;
INSERT INTO sender_groups(account_id,group_key,label)
 SELECT account_id,automatic_group_key,automatic_label FROM sender_units;
INSERT INTO sender_group_members SELECT u.account_id,u.id,g.id FROM sender_units u
 JOIN sender_groups g ON g.account_id=u.account_id AND g.group_key=u.automatic_group_key;
INSERT INTO message_sender_units SELECT m.account_id,m.id,u.id FROM message_metadata m
 JOIN sender_units u ON u.account_id=m.account_id AND u.kind='MESSAGE' AND u.canonical_key=m.provider_message_id;

CREATE FUNCTION dashboard_assign_unit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE k TEXT; kind_value TEXT; group_key_value TEXT; label_value TEXT; unit_value BIGINT; group_value BIGINT;
BEGIN
 PERFORM id FROM user_accounts WHERE id=NEW.account_id FOR KEY SHARE;
 kind_value:=COALESCE(NEW.dashboard_unit_kind,'MESSAGE');
 k:=COALESCE(NEW.dashboard_unit_key,NEW.provider_message_id);
 group_key_value:=lower(kind_value)||':'||k;
 label_value:=CASE WHEN kind_value='MESSAGE' THEN
   COALESCE(NULLIF(NEW.from_display_name,''),NULLIF(NEW.from_address,''),'Unknown sender') ELSE k END;
 IF kind_value='ADDRESS' AND EXISTS(SELECT 1 FROM dashboard_domain_registry
    WHERE domain=split_part(k,'@',2) AND kind='DEDICATED') THEN
   group_key_value:='domain:'||split_part(k,'@',2); label_value:=split_part(k,'@',2);
 END IF;
 INSERT INTO sender_units(account_id,kind,canonical_key,automatic_group_key,automatic_label,evidence_reason)
 VALUES(NEW.account_id,kind_value,k,group_key_value,label_value,
   COALESCE(NEW.dashboard_identity_reason,'Retained identity needs fresh metadata'))
 ON CONFLICT(account_id,kind,canonical_key) DO UPDATE SET canonical_key=EXCLUDED.canonical_key
 RETURNING id INTO unit_value;
 INSERT INTO sender_groups(account_id,group_key,label) VALUES(NEW.account_id,group_key_value,label_value)
 ON CONFLICT(account_id,group_key) DO UPDATE SET group_key=EXCLUDED.group_key RETURNING id INTO group_value;
 INSERT INTO sender_group_members VALUES(NEW.account_id,unit_value,group_value)
 ON CONFLICT(account_id,unit_id) DO NOTHING; -- preserve corrections across sync
 INSERT INTO message_sender_units VALUES(NEW.account_id,NEW.id,unit_value)
 ON CONFLICT(account_id,metadata_id) DO UPDATE SET unit_id=EXCLUDED.unit_id;
 RETURN NEW;
END $$;
CREATE TRIGGER dashboard_metadata_unit AFTER INSERT OR UPDATE OF dashboard_unit_kind,dashboard_unit_key,
 raw_from_values,dashboard_from_address ON message_metadata FOR EACH ROW EXECUTE FUNCTION dashboard_assign_unit();

CREATE TABLE dashboard_previews (
 public_id UUID PRIMARY KEY DEFAULT gen_random_uuid(), account_id BIGINT NOT NULL,
 scope_id BIGINT NOT NULL, grouping_revision BIGINT NOT NULL, filter_snapshot JSONB NOT NULL,
 selected_count INTEGER NOT NULL CHECK(selected_count>0), effective_count INTEGER NOT NULL CHECK(effective_count>=0),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), expires_at TIMESTAMPTZ NOT NULL, invalidated_at TIMESTAMPTZ,
 UNIQUE(public_id,account_id), CHECK(effective_count<=selected_count),
 FOREIGN KEY(scope_id,account_id) REFERENCES action_scopes(id,account_id) ON DELETE CASCADE
);
CREATE TABLE dashboard_preview_targets (
 preview_id UUID NOT NULL, account_id BIGINT NOT NULL, ordinal INTEGER NOT NULL CHECK(ordinal>=0),
 message_id UUID NOT NULL, subject_header TEXT, sender_address TEXT, received_at TIMESTAMPTZ,
 effective BOOLEAN NOT NULL, reason TEXT NOT NULL,
 PRIMARY KEY(preview_id,ordinal), UNIQUE(preview_id,message_id),
 FOREIGN KEY(preview_id,account_id) REFERENCES dashboard_previews(public_id,account_id) ON DELETE CASCADE
);
