package com.tidymail;

import static org.assertj.core.api.Assertions.*;
import com.tidymail.support.NativeTestPostgres;
import com.tidymail.support.UserAccountFixtures;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DashboardMigrationTest {
    static final NativeTestPostgres POSTGRES = NativeTestPostgres.cleanMigrationDatabase();

    Flyway flyway(String version) {
        var config = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").cleanDisabled(false);
        if (version != null)
            config.target(version);
        return config.load();
    }

    @Test
    void upgradeFromV49PreservesRulesMailAndKnownCompletedMailboxChecks() {
        flyway("49").clean();
        flyway("49").migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        long account = new UserAccountFixtures(jdbc).createAccount("rule-upgrade@example.test");
        jdbc.update("INSERT INTO automation_rule_suggestion_dismissals(account_id,sender_address,action,evidence_count) VALUES(?,'sender@example.test','ARCHIVE',5)", account);
        jdbc.update("INSERT INTO automation_rules(account_id,definition) VALUES(?, '{\"name\":\"Retained rule\"}'::jsonb)", account);
        jdbc.update("INSERT INTO message_metadata(account_id,provider,provider_message_id,labels) VALUES(?,'google','retained-rule-upgrade',ARRAY['INBOX'])", account);
        String savedRules = jdbc.queryForObject("SELECT to_jsonb(r)::text FROM automation_rules r WHERE account_id=?", String.class, account);
        String mail = jdbc.queryForObject("SELECT (SELECT jsonb_object_agg(key,value) FROM jsonb_each(to_jsonb(m)) WHERE key NOT LIKE 'classification_%' AND key <> 'classified_at')::text FROM message_metadata m WHERE account_id=?", String.class, account);
        long outlook = new UserAccountFixtures(jdbc).createAccount("sync-upgrade@example.test", "microsoft");
        jdbc.update("INSERT INTO mailbox_sync_state(account_id,provider,incremental_last_success_at) VALUES(?,'google',now()),(?,'microsoft',now())", account, outlook);
        assertThat(flyway(null).migrate().success).isTrue();
        assertThat(jdbc.queryForObject("SELECT mailbox_checked_at IS NULL FROM mailbox_sync_state WHERE account_id=?", Boolean.class, account)).isTrue();
        assertThat(jdbc.queryForObject("SELECT mailbox_checked_at=incremental_last_success_at FROM mailbox_sync_state WHERE account_id=?", Boolean.class, outlook)).isTrue();
        assertThat(jdbc.queryForObject("SELECT to_regclass('automation_rule_suggestion_dismissals') IS NULL", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT to_jsonb(r)::text FROM automation_rules r WHERE account_id=?", String.class, account)).isEqualTo(savedRules);
        assertThat(jdbc.queryForObject("SELECT (SELECT jsonb_object_agg(key,value) FROM jsonb_each(to_jsonb(m)) WHERE key NOT LIKE 'classification_%' AND key <> 'classified_at')::text FROM message_metadata m WHERE account_id=?", String.class, account)).isEqualTo(mail);
        assertThat(flyway(null).validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void gmailScopeUpgradeDiscardsListTokensAndPreservesIndexHistoryAndCleanup() {
        flyway("43").clean();
        flyway("43").migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        var fixtures = new UserAccountFixtures(jdbc);
        long gmail = fixtures.createAccount("gmail-upgrade@example.test");
        long outlook = fixtures.createAccount("outlook-upgrade@example.test", "microsoft");
        jdbc.update("""
            INSERT INTO mailbox_sync_state(account_id, provider, lifecycle, incremental_cursor_kind,
                incremental_cursor_value, backfill_page_token, backfill_status, initial_batch_completed_at,
                provider_total, provider_total_observed_at, rescan_status, rescan_anchor)
            VALUES (?, 'google', 'RESCANNING', 'GMAIL_HISTORY_ID', '1000', 'old-scope-token', 'RUNNING',
                now(), 63465, now(), 'RUNNING', 'old-rescan-token')
            """, gmail);
        jdbc.update("""
            INSERT INTO mailbox_sync_state(account_id, provider, incremental_cursor_kind, incremental_cursor_value,
                backfill_page_token, backfill_status)
            VALUES (?, 'microsoft', 'MICROSOFT_DELTA_LINK', 'delta', 'outlook-token', 'RUNNING')
            """, outlook);
        jdbc.update("""
            INSERT INTO message_metadata(account_id, provider, provider_message_id, labels, cleanup_updated_at)
            VALUES (?, 'google', 'retained', ARRAY['TRASH'], now())
            """, gmail);
        var data = jdbc.queryForObject("SELECT (SELECT jsonb_object_agg(key,value) FROM jsonb_each(to_jsonb(m)) WHERE key NOT LIKE 'classification_%' AND key <> 'classified_at')::text FROM message_metadata m WHERE account_id=?", String.class, gmail);
        var oauth = jdbc.queryForObject("SELECT to_jsonb(a)::text FROM user_accounts a WHERE id=?", String.class, gmail);
        var outlookState = jdbc.queryForObject("SELECT to_jsonb(s)::text FROM mailbox_sync_state s WHERE account_id=?", String.class, outlook);
        assertThat(flyway(null).migrate().success).isTrue();
        assertThat(jdbc.queryForMap("""
            SELECT gmail_backfill_phase, gmail_scope_total, backfill_page_token, backfill_status,
                incremental_cursor_value, initial_batch_completed_at IS NOT NULL AS ready,
                rescan_status, rescan_anchor, provider_total
            FROM mailbox_sync_state WHERE account_id=?
            """, gmail)).containsEntry("gmail_backfill_phase", "INBOX")
                .containsEntry("backfill_page_token", null).containsEntry("backfill_status", "RUNNING")
                .containsEntry("incremental_cursor_value", "1000").containsEntry("ready", true)
                .containsEntry("rescan_status", "REQUIRED").containsEntry("rescan_anchor", null)
                .containsEntry("provider_total", null).containsEntry("gmail_scope_total", null);
        assertThat(jdbc.queryForObject("SELECT (SELECT jsonb_object_agg(key,value) FROM jsonb_each(to_jsonb(m)) WHERE key NOT LIKE 'classification_%' AND key <> 'classified_at')::text FROM message_metadata m WHERE account_id=?", String.class, gmail)).isEqualTo(data);
        assertThat(jdbc.queryForObject("SELECT to_jsonb(a)::text FROM user_accounts a WHERE id=?", String.class, gmail)).isEqualTo(oauth);
        assertThat(jdbc.queryForObject("""
            SELECT (to_jsonb(s)-'gmail_backfill_phase'-'gmail_scope_total'-'mailbox_checked_at')::text
            FROM mailbox_sync_state s WHERE account_id=?
            """, String.class, outlook)).isEqualTo(outlookState);
    }





    @Test
    void freshAndRetainedUpgradePreserveCredentialsCursorsMetadataAndHistory() {
        flyway("15").clean();
        assertThat(flyway("15").migrate().migrationsExecuted).isEqualTo(15);
        var jdbc = new JdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        var accounts = new UserAccountFixtures(jdbc);
        long account = accounts.createAccount("retained@example.test");
        accounts.createAccount("other@example.test");
        jdbc.update(
                """
                        INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,received_at,labels,unread)
                        SELECT ?,'google','retained-'||i,'sender'||(i%500)||'@example.test',now()-interval '1 hour',
                        CASE WHEN i%5=0 THEN ARRAY[]::text[] ELSE ARRAY['INBOX'] END,i%2=0 FROM generate_series(1,10000) i
                        """,
                account);
        jdbc.update(
                "INSERT INTO mailbox_sync_state(account_id,provider,lifecycle,incremental_cursor_kind,incremental_cursor_value,backfill_status,backfill_page_token) VALUES(?,'google','BACKFILLING','GMAIL_HISTORY_ID','123','RUNNING','synthetic-cursor')",
                account);
        jdbc.update(
                """
                        INSERT INTO action_scopes(account_id,action_type,target_kind,message_ids,scope_hash,message_count,target_summary,risk_summary)
                        VALUES(?,'ARCHIVE','SENDER_SELECTION',ARRAY['retained-1'],repeat('a',64),1,'{}','{}')
                        """,
                account);
        jdbc.update(
                """
                        INSERT INTO user_actions(account_id,action_scope_id,action_type,message_ids,inverse_action_data,target_kind,target_id_json,affected_count,status,execution_mode,decision_source)
                        SELECT account_id,id,'ARCHIVE',message_ids,'{}','SENDER_SELECTION','{}',1,status,'STAGED','SENDERS'
                        FROM action_scopes CROSS JOIN unnest(ARRAY['APPLIED','PARTIAL','VERIFICATION_PENDING']) status WHERE account_id=?
                        """,
                account);
        var tokens = jdbc.queryForList(
                "SELECT id,encrypted_access_token,encrypted_refresh_token FROM user_accounts ORDER BY id");
        String metadata = jdbc.queryForObject(
                "SELECT md5(string_agg(provider_message_id||from_address||labels::text||received_at::text,',' ORDER BY id)) FROM message_metadata",
                String.class);
        String cursor = jdbc.queryForObject("SELECT incremental_cursor_value FROM mailbox_sync_state", String.class);
        String history = jdbc.queryForObject("SELECT json_agg(a ORDER BY id)::text FROM user_actions a", String.class);
        assertThat(flyway("16").migrate().migrationsExecuted).isEqualTo(1);
        java.util.UUID retainedPreview = java.util.UUID.randomUUID(), missingPreview = java.util.UUID.randomUUID();
        for (var preview : java.util.List.of(retainedPreview, missingPreview))
            jdbc.update(
                    """
                            INSERT INTO dashboard_previews(public_id,account_id,scope_id,grouping_revision,filter_snapshot,selected_count,effective_count,expires_at)
                            SELECT ?,account_id,id,0,'{}',1,1,now()+interval '1 hour' FROM action_scopes WHERE account_id=?
                            """,
                    preview, account);
        jdbc.update(
                """
                        INSERT INTO dashboard_preview_targets(preview_id,account_id,ordinal,message_id,effective,reason)
                        SELECT ?,account_id,0,dashboard_message_id,true,'Eligible' FROM message_metadata WHERE provider_message_id='retained-1'
                        """,
                retainedPreview);
        jdbc.update(
                "INSERT INTO dashboard_preview_targets(preview_id,account_id,ordinal,message_id,effective,reason) VALUES(?,?,0,?,true,'Eligible')",
                missingPreview, account, java.util.UUID.randomUUID());
        assertThat(flyway(null).migrate().success).isTrue();
        assertThat(jdbc.queryForObject("SELECT provider_message_id FROM dashboard_preview_targets WHERE preview_id=?",
                String.class, retainedPreview)).isEqualTo("retained-1");
        assertThat(jdbc.queryForObject("SELECT invalidated_at IS NOT NULL FROM dashboard_previews WHERE public_id=?",
                Boolean.class, missingPreview)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cleanup_jobs", Long.class)).isZero();
        assertThat(flyway(null).validateWithResult().validationSuccessful).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message_metadata", Long.class)).isEqualTo(10000);
        assertThat(jdbc.queryForObject(
                "SELECT md5(string_agg(provider_message_id||from_address||labels::text||received_at::text,',' ORDER BY id)) FROM message_metadata",
                String.class)).isEqualTo(metadata);
        assertThat(jdbc.queryForObject("SELECT incremental_cursor_value FROM mailbox_sync_state", String.class))
                .isEqualTo(cursor);
        assertThat(jdbc.queryForMap("SELECT backfill_page_token, gmail_backfill_phase FROM mailbox_sync_state"))
                .containsEntry("backfill_page_token", null).containsEntry("gmail_backfill_phase", "INBOX");
        assertThat(jdbc.queryForObject("SELECT json_agg(a ORDER BY id)::text FROM user_actions a", String.class))
                .isEqualTo(history);
        var after = jdbc.queryForList(
                "SELECT id,encrypted_access_token,encrypted_refresh_token FROM user_accounts ORDER BY id");
        for (int i = 0; i < tokens.size(); i++) {
            assertThat((byte[]) after.get(i).get("encrypted_access_token"))
                    .isEqualTo((byte[]) tokens.get(i).get("encrypted_access_token"));
            assertThat((byte[]) after.get(i).get("encrypted_refresh_token"))
                    .isEqualTo((byte[]) tokens.get(i).get("encrypted_refresh_token"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sender_units WHERE kind='MESSAGE'", Long.class))
                .isEqualTo(10000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message_metadata WHERE label_evidence_known", Long.class))
                .isEqualTo(8000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dashboard_previews", Long.class)).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO sender_group_members VALUES(?,(SELECT min(id) FROM sender_units), (SELECT min(id) FROM sender_groups))",
                account + 1)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        // A fresh installation also reaches the latest version with all tables and constraints.
        flyway(null).clean();
        assertThat(flyway(null).migrate().success).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_accounts", Long.class)).isZero();
        assertThat(
                jdbc.queryForObject("SELECT count(*) FROM dashboard_domain_registry WHERE kind='SHARED'", Long.class))
                .isPositive();
    }

    @Test
    void removingSavedGroupsRestoresDomainsAndPreservesMail() {
        flyway("50").clean();
        flyway("50").migrate();
        var jdbc = new JdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        long account = new UserAccountFixtures(jdbc).createAccount("regroup@example.test");
        for (String address : java.util.List.of("alerts@chase.com", "news@e.chase.com", "special@notify.chase.com"))
            jdbc.update(
                    """
                            INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,labels,label_evidence_known,
                                dashboard_from_address,dashboard_unit_kind,dashboard_unit_key)
                            VALUES(?,'google',?,?,ARRAY['INBOX'],true,?,'ADDRESS',?)
                            """,
                    account, address, address, address, address);
        long unit = jdbc.queryForObject("SELECT id FROM sender_units WHERE canonical_key='special@notify.chase.com'",
                Long.class);
        var correction = java.util.UUID.randomUUID();
        jdbc.update(
                "INSERT INTO sender_group_corrections(account_id,public_id,kind,unit_ids) VALUES(?,?,'SEPARATE',ARRAY[?]::bigint[])",
                account, correction, unit);
        jdbc.update("INSERT INTO sender_groups(account_id,group_key,label) VALUES(?,?,'special@notify.chase.com')",
                account, "separate:" + correction);
        jdbc.update(
                "UPDATE sender_group_members SET group_id=(SELECT id FROM sender_groups WHERE account_id=? AND group_key=?) WHERE unit_id=?",
                account, "separate:" + correction, unit);
        var messageIds = jdbc.queryForList("SELECT dashboard_message_id FROM message_metadata ORDER BY id");
        var unitIds = jdbc.queryForList("SELECT id,public_id FROM sender_units ORDER BY id");
        long split = jdbc.queryForObject("""
            INSERT INTO sender_units(account_id,kind,canonical_key,automatic_group_key,automatic_label,evidence_reason)
            VALUES(?,'SPLIT_ADDRESS','retired-split','domain:chase.com','Saved split','Retired manual split') RETURNING id
            """, Long.class, account);
        jdbc.update("INSERT INTO sender_address_units(account_id,source_unit_id,address,unit_id,active) VALUES(?,?, 'special@notify.chase.com',?,true)",account,unit,split);
        jdbc.update("INSERT INTO sender_group_members SELECT ?,?,id FROM sender_groups WHERE account_id=? AND group_key=?",account,split,account,"separate:"+correction);
        jdbc.update("INSERT INTO sender_separations VALUES(?,?,?)",account,Math.min(unit,split),Math.max(unit,split));
        assertThat(flyway(null).migrate().success).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT group_id) FROM sender_group_members", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sender_units WHERE automatic_group_key='domain:chase.com'",
                Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT g.group_key FROM sender_group_members m JOIN sender_groups g ON m.group_id=g.id WHERE m.unit_id=?",
                String.class, unit)).isEqualTo("domain:chase.com");
        assertThat(jdbc.queryForObject("SELECT grouping_revision FROM dashboard_account_settings WHERE account_id=?",
                Long.class, account)).isGreaterThan(0);
        assertThat(jdbc.queryForList("SELECT dashboard_message_id FROM message_metadata ORDER BY id"))
                .isEqualTo(messageIds);
        assertThat(jdbc.queryForList("SELECT id,public_id FROM sender_units ORDER BY id")).isEqualTo(unitIds);
        jdbc.update("UPDATE message_metadata SET dashboard_unit_key=dashboard_unit_key");
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT group_id) FROM sender_group_members", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sender_units WHERE kind='SPLIT_ADDRESS'", Long.class)).isZero();
        for (String table : java.util.List.of("sender_group_corrections","sender_separations","sender_address_units"))
            assertThat(jdbc.queryForObject("SELECT to_regclass(?) IS NULL",Boolean.class,table)).isTrue();
    }

    @Test
    void retainedV17DispatchKeepsItsIdentityAndPreconditionForVerificationAfterUpgrade() {
        flyway("17").clean();
        flyway("17").migrate();
        var jdbc = new JdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        long account = new UserAccountFixtures(jdbc).createAccount("upgrade-worker@example.test");
        long scope = jdbc.queryForObject(
                "INSERT INTO action_scopes(account_id,action_type,target_kind,message_ids,scope_hash,message_count,target_summary,risk_summary) VALUES(?,'MARK_READ','DASHBOARD_SELECTION',ARRAY['synthetic'],repeat('b',64),1,'{}','{}') RETURNING id",
                Long.class, account);
        var preview = java.util.UUID.randomUUID();
        var job = java.util.UUID.randomUUID();
        var message = java.util.UUID.randomUUID();
        var batch = java.util.UUID.randomUUID();
        jdbc.update(
                "INSERT INTO dashboard_previews(public_id,account_id,scope_id,grouping_revision,filter_snapshot,selected_count,effective_count,expires_at) VALUES(?,?,?,0,'{}',1,1,now()+interval '1 hour')",
                preview, account, scope);
        jdbc.update(
                "INSERT INTO cleanup_jobs(public_id,account_id,preview_id,scope_id,action_type,selected_count,snapshot,status,generation) VALUES(?,?,?,?,'MARK_READ',1,'{}','RUNNING',3)",
                job, account, preview, scope);
        jdbc.update(
                "INSERT INTO cleanup_batches(public_id,job_id,account_id,generation,state) VALUES(?,?,?,3,'DISPATCH_INTENT')",
                batch, job, account);
        jdbc.update(
                "INSERT INTO cleanup_job_targets(job_id,account_id,ordinal,provider_message_id,message_id,target_fingerprint,state,reason,pre_unread,batch_id) VALUES(?,?,0,'synthetic',?,repeat('c',64),'DISPATCH_INTENT','Request claimed',true,?)",
                job, account, message, batch);
        jdbc.update(
                "INSERT INTO cleanup_active_targets(account_id,target_fingerprint,job_id) VALUES(?,repeat('c',64),?)",
                account, job);
        assertThat(flyway(null).migrate().success).isTrue();
        assertThat(jdbc.queryForObject("SELECT pre_change_observed FROM cleanup_job_targets WHERE job_id=?",
                Boolean.class, job)).isTrue();
        assertThat(jdbc.queryForObject("SELECT state FROM cleanup_job_targets WHERE job_id=?", String.class, job))
                .isEqualTo("DISPATCH_INTENT");
        assertThat(jdbc.queryForObject("SELECT message_id FROM cleanup_job_targets WHERE job_id=?",
                java.util.UUID.class, job)).isEqualTo(message);
        assertThat(jdbc.queryForObject("SELECT generation FROM cleanup_jobs WHERE public_id=?", Long.class, job))
                .isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cleanup_active_targets", Long.class)).isEqualTo(1);
    }


    @Test void removingAiResultsPreservesMailNativeLabelsAndRules() {
        flyway("51").clean();flyway("51").migrate();
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        long account=new UserAccountFixtures(jdbc).createAccount("retirement@example.test");
        jdbc.update("INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,labels,label_evidence_known,dashboard_from_address,dashboard_unit_kind,dashboard_unit_key,classification_category,classification_override) VALUES(?,'google','preserved','a@example.test',ARRAY['INBOX','Label_123'],true,'a@example.test','ADDRESS','a@example.test','MARKETING','PERSONAL')",account);
        jdbc.update("INSERT INTO automation_rules(account_id,definition) VALUES(?, '{\"name\":\"Retained rule\"}'::jsonb)", account);
        jdbc.update("INSERT INTO message_recommendations(account_id,message_id,source_hash,policy_revision,engine_version,adapter_version,result,assessment,relationship) SELECT account_id,dashboard_message_id,'old-source','1','retired','retired','{}'::jsonb,'{}'::jsonb,'{}'::jsonb FROM message_metadata WHERE account_id=?",account);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message_recommendations WHERE account_id=?",Long.class,account)).isEqualTo(1);
        var before=jdbc.queryForList("SELECT dashboard_message_id,provider_message_id,labels::text FROM message_metadata");
        var rules=jdbc.queryForList("SELECT * FROM automation_rules");
        assertThat(flyway(null).migrate().success).isTrue();
        assertThat(jdbc.queryForList("SELECT dashboard_message_id,provider_message_id,labels::text FROM message_metadata")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM automation_rules")).isEqualTo(rules);
        for(String table:java.util.List.of("message_recommendations","message_semantic_labels","classification_attempts","dashboard_classification_jobs","labeling_work","recommendation_dirty"))
            assertThat(jdbc.queryForObject("SELECT to_regclass(?) IS NULL",Boolean.class,table)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='message_metadata' AND column_name LIKE 'classification_%'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM local_label_dirty WHERE account_id=?",Long.class,account)).isEqualTo(1);
    }

    @Test
    void finalMigrationSetPreservesUnresolvedDashboardTargetsAndTheirOverlapHolds() {
        flyway("38").clean(); flyway("38").migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        long account = new UserAccountFixtures(jdbc).createAccount("milestone-upgrade@example.test");
        long scope = jdbc.queryForObject("""
            INSERT INTO action_scopes(account_id,action_type,target_kind,message_ids,scope_hash,message_count,target_summary,risk_summary)
            SELECT ?,'MARK_READ','DASHBOARD_SELECTION',array_agg('unresolved-'||i ORDER BY i),repeat('d',64),50,'{}','{}'
            FROM generate_series(1,50) i RETURNING id
            """,Long.class,account);
        var preview = java.util.UUID.randomUUID();
        var job = java.util.UUID.randomUUID();
        jdbc.update("""
            INSERT INTO dashboard_previews(public_id,account_id,scope_id,grouping_revision,filter_snapshot,selected_count,effective_count,expires_at)
            VALUES(?,?,?,0,'{}',50,50,now()+interval '1 hour')
            """,preview,account,scope);
        jdbc.update("""
            INSERT INTO cleanup_jobs(public_id,account_id,preview_id,scope_id,action_type,selected_count,snapshot,status)
            VALUES(?,?,?,?,'MARK_READ',50,'{"filter":{"scope":"inbox"}}','VERIFYING')
            """,job,account,preview,scope);
        jdbc.update("""
            INSERT INTO cleanup_job_targets(job_id,account_id,ordinal,provider_message_id,message_id,target_fingerprint,state,reason,verification_attempts)
            SELECT ?,?,i-1,'unresolved-'||i,gen_random_uuid(),encode(digest('unresolved-'||i,'sha256'),'hex'),
                'AWAITING_VERIFICATION','Prior dispatch unresolved',6 FROM generate_series(1,50) i
            """,job,account);
        jdbc.update("""
            INSERT INTO cleanup_active_targets(account_id,target_fingerprint,job_id)
            SELECT account_id,target_fingerprint,job_id FROM cleanup_job_targets WHERE job_id=?
            """,job);
        var savedJob = jdbc.queryForMap("SELECT * FROM cleanup_jobs WHERE public_id=?",job);
        String originalTargets = """
            SELECT (to_jsonb(t)-'outlook_read_required'-'outlook_read_completed')::text
            FROM cleanup_job_targets t WHERE job_id=? ORDER BY ordinal
            """;
        var targets = jdbc.queryForList(originalTargets,String.class,job);
        var holds = jdbc.queryForList("SELECT * FROM cleanup_active_targets WHERE job_id=? ORDER BY target_fingerprint",job);
        assertThat(holds).hasSize(50);

        assertThat(flyway(null).migrate().success).isTrue();
        assertThat(jdbc.queryForMap("SELECT * FROM cleanup_jobs WHERE public_id=?",job)).isEqualTo(savedJob);
        assertThat(jdbc.queryForList(originalTargets,String.class,job)).isEqualTo(targets);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cleanup_job_targets WHERE outlook_read_required OR outlook_read_completed", Long.class)).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM cleanup_active_targets WHERE job_id=? ORDER BY target_fingerprint",job)).isEqualTo(holds);
        assertThat(flyway(null).validateWithResult().validationSuccessful).isTrue();
    }
}
