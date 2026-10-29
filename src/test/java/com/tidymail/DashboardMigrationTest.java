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

@Testcontainers(disabledWithoutDocker=false)
class DashboardMigrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine");
    Flyway flyway(String version) {
        var config=Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).locations("classpath:db/migration").cleanDisabled(false);
        if(version!=null)config.target(version);return config.load();
    }
    @Test void freshAndRetainedUpgradePreserveCredentialsCursorsMetadataAndHistory() {
        flyway("15").clean();assertThat(flyway("15").migrate().migrationsExecuted).isEqualTo(15);
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        var accounts=new UserAccountFixtures(jdbc);long account=accounts.createAccount("retained@example.test");accounts.createAccount("other@example.test");
        jdbc.update("""
          INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,received_at,labels,unread)
          SELECT ?,'google','retained-'||i,'sender'||(i%500)||'@example.test',now()-interval '1 hour',
          CASE WHEN i%5=0 THEN ARRAY[]::text[] ELSE ARRAY['INBOX'] END,i%2=0 FROM generate_series(1,10000) i
          """,account);
        jdbc.update("INSERT INTO mailbox_sync_state(account_id,provider,lifecycle,incremental_cursor_kind,incremental_cursor_value,backfill_status,backfill_page_token) VALUES(?,'google','BACKFILLING','GMAIL_HISTORY_ID','123','RUNNING','synthetic-cursor')",account);
        jdbc.update("""
          INSERT INTO action_scopes(account_id,action_type,target_kind,message_ids,scope_hash,message_count,target_summary,risk_summary)
          VALUES(?,'ARCHIVE','SENDER_SELECTION',ARRAY['retained-1'],repeat('a',64),1,'{}','{}')
          """,account);
        jdbc.update("""
          INSERT INTO user_actions(account_id,action_scope_id,action_type,message_ids,inverse_action_data,target_kind,target_id_json,affected_count,status,execution_mode,decision_source)
          SELECT account_id,id,'ARCHIVE',message_ids,'{}','SENDER_SELECTION','{}',1,status,'STAGED','SENDERS'
          FROM action_scopes CROSS JOIN unnest(ARRAY['APPLIED','PARTIAL','VERIFICATION_PENDING']) status WHERE account_id=?
          """,account);
        var tokens=jdbc.queryForList("SELECT id,encrypted_access_token,encrypted_refresh_token FROM user_accounts ORDER BY id");
        String metadata=jdbc.queryForObject("SELECT md5(string_agg(provider_message_id||from_address||labels::text||received_at::text,',' ORDER BY id)) FROM message_metadata",String.class);
        String cursor=jdbc.queryForObject("SELECT row_to_json(s)::text FROM mailbox_sync_state s",String.class);
        String history=jdbc.queryForObject("SELECT json_agg(a ORDER BY id)::text FROM user_actions a",String.class);
        assertThat(flyway("16").migrate().migrationsExecuted).isEqualTo(1);
        java.util.UUID retainedPreview=java.util.UUID.randomUUID(),missingPreview=java.util.UUID.randomUUID();
        for(var preview:java.util.List.of(retainedPreview,missingPreview))jdbc.update("""
          INSERT INTO dashboard_previews(public_id,account_id,scope_id,grouping_revision,filter_snapshot,selected_count,effective_count,expires_at)
          SELECT ?,account_id,id,0,'{}',1,1,now()+interval '1 hour' FROM action_scopes WHERE account_id=?
          """,preview,account);
        jdbc.update("""
          INSERT INTO dashboard_preview_targets(preview_id,account_id,ordinal,message_id,effective,reason)
          SELECT ?,account_id,0,dashboard_message_id,true,'Eligible' FROM message_metadata WHERE provider_message_id='retained-1'
          """,retainedPreview);
        jdbc.update("INSERT INTO dashboard_preview_targets(preview_id,account_id,ordinal,message_id,effective,reason) VALUES(?,?,0,?,true,'Eligible')",missingPreview,account,java.util.UUID.randomUUID());
        assertThat(flyway(null).migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT provider_message_id FROM dashboard_preview_targets WHERE preview_id=?",String.class,retainedPreview)).isEqualTo("retained-1");
        assertThat(jdbc.queryForObject("SELECT invalidated_at IS NOT NULL FROM dashboard_previews WHERE public_id=?",Boolean.class,missingPreview)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cleanup_jobs",Long.class)).isZero();
        assertThat(flyway(null).validateWithResult().validationSuccessful).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message_metadata",Long.class)).isEqualTo(10000);
        assertThat(jdbc.queryForObject("SELECT md5(string_agg(provider_message_id||from_address||labels::text||received_at::text,',' ORDER BY id)) FROM message_metadata",String.class)).isEqualTo(metadata);
        assertThat(jdbc.queryForObject("SELECT row_to_json(s)::text FROM mailbox_sync_state s",String.class)).isEqualTo(cursor);
        assertThat(jdbc.queryForObject("SELECT json_agg(a ORDER BY id)::text FROM user_actions a",String.class)).isEqualTo(history);
        var after=jdbc.queryForList("SELECT id,encrypted_access_token,encrypted_refresh_token FROM user_accounts ORDER BY id");
        for(int i=0;i<tokens.size();i++) {
            assertThat((byte[])after.get(i).get("encrypted_access_token")).isEqualTo((byte[])tokens.get(i).get("encrypted_access_token"));
            assertThat((byte[])after.get(i).get("encrypted_refresh_token")).isEqualTo((byte[])tokens.get(i).get("encrypted_refresh_token"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sender_units WHERE kind='MESSAGE'",Long.class)).isEqualTo(10000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message_metadata WHERE label_evidence_known",Long.class)).isEqualTo(8000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dashboard_previews",Long.class)).isEqualTo(2);
        assertThatThrownBy(()->jdbc.update("INSERT INTO sender_group_members VALUES(?,(SELECT min(id) FROM sender_units), (SELECT min(id) FROM sender_groups))",account+1)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        // A fresh installation also reaches V17 with all new tables and constraints.
        flyway(null).clean();assertThat(flyway(null).migrate().migrationsExecuted).isEqualTo(17);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_accounts",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dashboard_domain_registry WHERE kind='SHARED'",Long.class)).isPositive();
    }
}
