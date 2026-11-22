package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import com.tidymail.job.SenderStatsVacuumJob;
import com.tidymail.support.PostgresJourneyTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class Phase8DeploymentTest extends PostgresJourneyTest {
    @Autowired SenderStatsVacuumJob vacuum;

    @Test
    void vacuumDeletesOnlyInactiveAccountsDerivedData() {
        long stale = userAccounts.createAccount("stale@example.test");
        long fresh = userAccounts.createAccount("fresh@example.test");
        long never = userAccounts.createAccount("never@example.test");
        jdbc.update("UPDATE user_accounts SET last_login_at=now()-interval '91 days' WHERE id=?", stale);
        jdbc.update("UPDATE user_accounts SET last_login_at=now()-interval '1 day' WHERE id=?", fresh);
        jdbc.update("UPDATE user_accounts SET last_login_at=NULL WHERE id=?", never);
        for (long account : List.of(stale, fresh, never)) {
            jdbc.update("INSERT INTO sender_stats(account_id,from_address,from_domain,total_count) SELECT ?, 'sender'||i||'@example.test','example.test',10 FROM generate_series(1,2) i", account);
            jdbc.update("INSERT INTO sync_progress(account_id,job_type,status) VALUES(?,'INCREMENTAL_SYNC','COMPLETE')", account);
            jdbc.update("INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,labels) VALUES(?,'google',?,'sender@example.test',ARRAY['INBOX'])", account, "retained-" + account);
        }

        vacuum.run();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM sender_stats WHERE account_id=?", Long.class, stale)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sync_progress WHERE account_id=?", Long.class, stale)).isZero();
        for (long account : List.of(fresh, never)) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sender_stats WHERE account_id=?", Long.class, account)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sync_progress WHERE account_id=?", Long.class, account)).isOne();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_accounts", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message_metadata", Long.class)).isEqualTo(3);
    }
}
