package com.tidymail;

import static org.assertj.core.api.Assertions.*;
import com.tidymail.support.UserAccountFixtures;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = false)
class DashboardMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    Flyway flyway(String version) {
        var config = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").cleanDisabled(false);
        if (version != null)
            config.target(version);
        return config.load();
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
        String cursor = jdbc.queryForObject("SELECT row_to_json(s)::text FROM mailbox_sync_state s", String.class);
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
        assertThat(flyway(null).migrate().migrationsExecuted).isEqualTo(5);
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
        assertThat(jdbc.queryForObject("SELECT row_to_json(s)::text FROM mailbox_sync_state s", String.class))
                .isEqualTo(cursor);
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
        // A fresh installation also reaches V21 with all new tables and constraints.
        flyway(null).clean();
        assertThat(flyway(null).migrate().migrationsExecuted).isEqualTo(21);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_accounts", Long.class)).isZero();
        assertThat(
                jdbc.queryForObject("SELECT count(*) FROM dashboard_domain_registry WHERE kind='SHARED'", Long.class))
                .isPositive();
    }

    @Test
    void organizationUpgradeRegroupsRetainedAddressesAndPreservesManualMembership() {
        flyway("19").clean();
        flyway("19").migrate();
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
        assertThat(flyway(null).migrate().migrationsExecuted).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT group_id) FROM sender_group_members", Long.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sender_units WHERE automatic_group_key='domain:chase.com'",
                Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT g.group_key FROM sender_group_members m JOIN sender_groups g ON m.group_id=g.id WHERE m.unit_id=?",
                String.class, unit)).isEqualTo("separate:" + correction);
        assertThat(jdbc.queryForObject("SELECT grouping_revision FROM dashboard_account_settings WHERE account_id=?",
                Long.class, account)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT dashboard_message_id FROM message_metadata ORDER BY id"))
                .isEqualTo(messageIds);
        assertThat(jdbc.queryForList("SELECT id,public_id FROM sender_units ORDER BY id")).isEqualTo(unitIds);
        jdbc.update("UPDATE message_metadata SET dashboard_unit_key=dashboard_unit_key");
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT group_id) FROM sender_group_members", Long.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sender_group_corrections WHERE active", Long.class))
                .isEqualTo(1);
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
        assertThat(flyway(null).migrate().migrationsExecuted).isEqualTo(4);
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

}
