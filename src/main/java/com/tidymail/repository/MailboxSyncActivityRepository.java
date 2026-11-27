package com.tidymail.repository;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Records user-visible milestones inside the sync unit's lease-validated transaction. */
@Repository
public class MailboxSyncActivityRepository {
    private final JdbcTemplate jdbc;

    public MailboxSyncActivityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void checked(long account, Instant at) {
        jdbc.update("UPDATE mailbox_sync_state SET mailbox_checked_at=? WHERE account_id=?", Timestamp.from(at), account);
        record(account, "CHECK_COMPLETE", at);
    }

    public void record(long account, String kind, Instant at) {
        jdbc.update("INSERT INTO mailbox_sync_events(account_id,kind,occurred_at) VALUES(?,?,?)",
            account, kind, Timestamp.from(at));
        // Bound history per account; no provider identifiers or message contents are stored.
        jdbc.update("""
            DELETE FROM mailbox_sync_events WHERE account_id=? AND id IN (
                SELECT id FROM mailbox_sync_events WHERE account_id=? ORDER BY occurred_at DESC,id DESC OFFSET 100)
            """, account, account);
    }
}
