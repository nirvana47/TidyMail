package com.tidymail.mail.outlook;

import com.tidymail.entity.UserAccount;
import com.tidymail.mail.CleanupProviderException;
import com.tidymail.mail.MailMessage;
import com.tidymail.repository.MailboxSyncStateRepository;
import com.tidymail.service.MailboxFreshnessService.SyncOutcome;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OutlookSyncService {
    private final OutlookMailService outlook;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MailboxSyncStateRepository states;

    public OutlookSyncService(OutlookMailService outlook, JdbcTemplate jdbc,
            TransactionTemplate tx, MailboxSyncStateRepository states) {
        this.outlook = outlook;
        this.jdbc = jdbc;
        this.tx = tx;
        this.states = states;
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OutlookSyncService.class);

    private record Round(UUID id, boolean inventory, boolean recent, boolean metadataComplete) {}
    private record Cursor(String folder, String link, UUID baseline) {}
    private record SizeScan(String link, boolean listed) {}

    /** One owned unit. Folder inventory, a recent batch, a delta page, or bounded reconciliations. */
    public SyncOutcome synchronize(UserAccount account, String lease) {
        long id = account.getId();
        try {
            return new SyncOutcome(unit(account, lease), false, false);
        } catch (RuntimeException error) {
            boolean reconnect = error instanceof CleanupProviderException failure && failure.status() == 401
                || "OUTLOOK_RECONNECT_REQUIRED".equals(error.getMessage());
            commit(id, lease, () -> jdbc.update("""
                UPDATE mailbox_sync_state SET error_code='OUTLOOK_SYNC_INTERRUPTED',
                    lifecycle=CASE WHEN ? THEN 'REAUTH_REQUIRED' ELSE lifecycle END
                WHERE account_id=?
                """, reconnect, id));
            throw error;
        }
    }

    private boolean unit(UserAccount account, String lease) {
        long id = account.getId();
        Round round = jdbc.query("""
            SELECT round_id,inventory_complete,recent_complete,metadata_complete FROM outlook_sync_round WHERE account_id=?
            """, rs -> rs.next() ? new Round(rs.getObject(1, UUID.class), rs.getBoolean(2), rs.getBoolean(3),
                rs.getBoolean(4)) : null, id);
        if (round != null && round.metadataComplete()) return sizeUnit(account, lease);
        if (round == null || !round.inventory()) {
            var inventory = outlook.inventory(account);
            commit(id, lease, () -> publishInventory(id, round, inventory));
            return true;
        }
        var roles = outlook.roles(id);
        if (!round.recent()) {
            var messages = outlook.recent(account, roles);
            commit(id, lease, () -> {
                save(id, messages, null);
                jdbc.update("UPDATE outlook_sync_round SET recent_complete=true WHERE account_id=?", id);
                jdbc.update("""
                    UPDATE mailbox_sync_state SET initial_batch_completed_at=COALESCE(initial_batch_completed_at,now())
                    WHERE account_id=?
                    """, id);
            });
            return true;
        }
        var cursor = jdbc.query("""
            SELECT folder_id,cursor,baseline FROM outlook_folders WHERE account_id=?
            AND role IN ('MAIL','INBOX','OUTLOOK_ARCHIVE','SENT')
            AND finished_round IS DISTINCT FROM ?
            ORDER BY CASE role WHEN 'INBOX' THEN 0 WHEN 'SENT' THEN 2 ELSE 1 END,folder_id LIMIT 1
            """, rs -> rs.next() ? new Cursor(rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class)) : null,
            id, round.id());
        if (cursor != null) {
            deltaPage(account, lease, round, cursor, roles);
            return true;
        }
        var pending = jdbc.queryForList("""
            SELECT message_id FROM outlook_reconcile WHERE account_id=? ORDER BY message_id LIMIT 10
            """, String.class, id);
        if (!pending.isEmpty()) {
            var observed = Instant.now();
            var actual = new LinkedHashMap<String,MailMessage>();
            for (String message : pending) actual.put(message, outlook.current(account, message, roles));
            commit(id, lease, () -> {
                actual.forEach((message, value) -> {
                    if (value == null) jdbc.update("""
                        DELETE FROM message_metadata WHERE account_id=? AND provider_message_id=?
                        AND last_synced_at<=? AND (cleanup_updated_at IS NULL OR cleanup_updated_at<=?)
                        """, id, message, Timestamp.from(observed), Timestamp.from(observed));
                    else outlook.save(id, value, null);
                    jdbc.update("DELETE FROM outlook_reconcile WHERE account_id=? AND message_id=?", id, message);
                });
            });
            return true;
        }
        commit(id, lease, () -> {
            jdbc.update("UPDATE outlook_sync_round SET metadata_complete=true WHERE account_id=?", id);
            jdbc.update("""
                UPDATE mailbox_sync_state SET backfill_status='COMPLETE',lifecycle='ACTIVE',
                    backfill_completed_at=COALESCE(backfill_completed_at,now()),incremental_last_success_at=now()
                WHERE account_id=?
                """, id);
        });
        return true;
    }

    // Size metadata is optional and has its own cursor. Failure here must not stall mail refresh.
    private boolean sizeUnit(UserAccount account, String lease) {
        long id = account.getId();
        var scan = jdbc.query("SELECT next_link,listed FROM outlook_size_scan WHERE account_id=?",
            rs -> rs.next() ? new SizeScan(rs.getString(1), rs.getBoolean(2))
                : new SizeScan(null, false), id);
        if (!scan.listed()) {
            OutlookMailService.SizePage page;
            try {
                page = outlook.listSizes(account, scan.link());
            } catch (RuntimeException error) {
                return deferSizes(id, lease, scan, error);
            }
            commit(id, lease, () -> {
                saveSizes(id, page.sizes());
                jdbc.update("""
                    INSERT INTO outlook_size_scan(account_id,next_link,listed) VALUES (?,?,?)
                    ON CONFLICT(account_id) DO UPDATE SET next_link=EXCLUDED.next_link,listed=EXCLUDED.listed
                    """, id, page.next(), page.next() == null);
            });
            return true;
        }

        // Catch mail delivered or moved during the listing, and new mail from later delta rounds.
        var targets = jdbc.queryForList("""
            SELECT provider_message_id FROM message_metadata
            WHERE account_id=? AND provider='microsoft' AND size_estimate IS NULL AND NOT outlook_size_checked
              AND NOT(labels && ARRAY['SENT','TRASH','SPAM','DRAFT','OUTLOOK_EXCLUDED'])
            ORDER BY id LIMIT 10
            """, String.class, id);
        if (targets.isEmpty()) {
            commit(id, lease, () -> jdbc.update("DELETE FROM outlook_sync_round WHERE account_id=?", id));
            return false;
        }
        var sizes = new LinkedHashMap<String,Long>();
        try {
            for (var target : targets) {
                sizes.put(target, outlook.currentSize(account, target));
            }
        } catch (RuntimeException error) {
            return deferSizes(id, lease, scan, error);
        }
        commit(id, lease, () -> saveSizes(id, sizes));
        return true;
    }

    private void saveSizes(long account, Map<String,Long> sizes) {
        jdbc.batchUpdate("""
            UPDATE message_metadata SET size_estimate=?,outlook_size_checked=true
            WHERE account_id=? AND provider='microsoft' AND provider_message_id=? AND size_estimate IS NULL
            """, sizes.entrySet().stream().map(size -> new Object[]{size.getValue(),account,size.getKey()}).toList());
    }

    private boolean deferSizes(long account, String lease, SizeScan scan, RuntimeException error) {
        int status = error instanceof CleanupProviderException failure ? failure.status() : 0;
        log.warn("Outlook size scan deferred for account {} (status {})", account, status);
        commit(account, lease, () -> {
            if (scan.link() != null && (status == 400 || status == 410))
                jdbc.update("UPDATE outlook_size_scan SET next_link=NULL WHERE account_id=?", account);
            jdbc.update("DELETE FROM outlook_sync_round WHERE account_id=?", account);
        });
        return false;
    }

    private void publishInventory(long account, Round round, List<OutlookMailService.Folder> inventory) {
        UUID generation = round == null ? UUID.randomUUID() : round.id();
        jdbc.update("""
            INSERT INTO outlook_sync_round(account_id,round_id,inventory_complete,recent_complete)
            VALUES (?,?,true,(SELECT initial_batch_completed_at IS NOT NULL FROM mailbox_sync_state WHERE account_id=?))
            ON CONFLICT(account_id) DO UPDATE SET inventory_complete=true
            """, account, generation, account);
        var old = outlook.roles(account);
        for (var folder : inventory) {
            String previous = old.remove(folder.id());
            if (previous != null && !previous.equals(folder.role())) enqueueFolder(account, folder.id());
            jdbc.update("""
                INSERT INTO outlook_folders(account_id,folder_id,folder_path,role) VALUES(?,?,?,?)
                ON CONFLICT(account_id,folder_id) DO UPDATE SET folder_path=EXCLUDED.folder_path,role=EXCLUDED.role,
                  cursor=CASE WHEN outlook_folders.role=EXCLUDED.role THEN outlook_folders.cursor END,
                  baseline=CASE WHEN outlook_folders.role=EXCLUDED.role THEN outlook_folders.baseline END,
                  finished_round=CASE WHEN outlook_folders.role=EXCLUDED.role THEN outlook_folders.finished_round END
                """, account, folder.id(), folder.path(), folder.role());
        }
        for (String absent : old.keySet()) {
            enqueueFolder(account, absent);
            jdbc.update("DELETE FROM outlook_folders WHERE account_id=? AND folder_id=?", account, absent);
        }
        jdbc.update("""
            UPDATE mailbox_sync_state SET
              backfill_status=CASE WHEN backfill_status='COMPLETE' THEN backfill_status ELSE 'RUNNING' END,
              lifecycle=CASE WHEN backfill_status='COMPLETE' THEN 'ACTIVE' ELSE 'BACKFILLING' END,
              backfill_started_at=COALESCE(backfill_started_at,now()),incremental_last_attempt_at=now()
            WHERE account_id=?
            """, account);
    }

    private void enqueueFolder(long account, String folder) {
        jdbc.update("""
            INSERT INTO outlook_reconcile(account_id,message_id)
            SELECT account_id,provider_message_id FROM message_metadata WHERE account_id=? AND outlook_folder_id=?
            ON CONFLICT DO NOTHING
            """, account, folder);
    }

    private void deltaPage(UserAccount account, String lease, Round round, Cursor cursor, Map<String,String> roles) {
        long id = account.getId();
        OutlookMailService.Delta page;
        try {
            page = outlook.delta(account, cursor.folder(), cursor.link(), roles);
        } catch (CleanupProviderException error) {
            if (error.status() == 404 || error.status() == 410 && cursor.link() != null) {
                // Expired cursor requires a full baseline. A missing folder requires a complete new inventory.
                // Neither error is evidence that a message was deleted.
                commit(id, lease, () -> {
                    jdbc.update("UPDATE outlook_folders SET cursor=NULL,baseline=NULL WHERE account_id=? AND folder_id=?",
                        id, cursor.folder());
                    if (error.status() == 404) jdbc.update("""
                        UPDATE outlook_sync_round SET inventory_complete=false WHERE account_id=?
                        """, id);
                });
            }
            throw error;
        }
        UUID baseline = cursor.link() == null ? UUID.randomUUID() : cursor.baseline();
        commit(id, lease, () -> {
            // Delta items can repeat or arrive out of order. Resolve equal-revision state
            // conflicts from the current mailbox before presenting them as a change.
            var fresh = new ArrayList<MailMessage>();
            var reconcile = new LinkedHashSet<>(page.reconcile());
            for (var message : page.messages()) {
                if (outlook.conflictingDelta(id, message)) reconcile.add(message.id());
                else fresh.add(message);
            }
            save(id, fresh, baseline);
            for (String message : reconcile) jdbc.update("""
                INSERT INTO outlook_reconcile(account_id,message_id) VALUES(?,?) ON CONFLICT DO NOTHING
                """, id, message);
            if (page.finished() && baseline != null) {
                // The completed baseline only identifies candidates. A mailbox-wide read distinguishes
                // actual deletions from moves and handles rows inserted while the baseline was underway.
                jdbc.update("""
                    INSERT INTO outlook_reconcile(account_id,message_id)
                    SELECT account_id,provider_message_id FROM message_metadata
                    WHERE account_id=? AND outlook_folder_id=? AND full_scan_generation IS DISTINCT FROM ?
                    ON CONFLICT DO NOTHING
                    """, id, cursor.folder(), baseline);
            }
            jdbc.update("""
                UPDATE outlook_folders SET cursor=?,baseline=?,finished_round=? WHERE account_id=? AND folder_id=?
                """, page.cursor(), page.finished() ? null : baseline, page.finished() ? round.id() : null, id, cursor.folder());
        });
    }

    private void save(long account, List<MailMessage> messages, UUID baseline) {
        messages.stream().sorted(Comparator.comparing(MailMessage::id))
            .forEach(message -> outlook.save(account, message, baseline));
    }

    private void commit(long account, String lease, Runnable work) {
        tx.executeWithoutResult(status -> {
            // Match the account-first lock order of the dashboard and local label worker.
            jdbc.queryForObject("SELECT id FROM user_accounts WHERE id=? FOR UPDATE", Long.class, account);
            var state = states.lockForCommit(account);
            if (!"microsoft".equals(state.provider()) || !lease.equals(state.backfillLeaseOwner())
                    || !state.backfillLeaseExpiresAt().isAfter(Instant.now()))
                throw new IllegalStateException("OUTLOOK_SYNC_LEASE_LOST");
            jdbc.update("""
                UPDATE mailbox_sync_state SET error_code=NULL,lifecycle=CASE WHEN lifecycle='REAUTH_REQUIRED'
                    THEN CASE WHEN backfill_status='COMPLETE' THEN 'ACTIVE' ELSE 'BACKFILLING' END ELSE lifecycle END
                WHERE account_id=? AND error_code IS NOT NULL
                """, account);
            work.run();
        });
    }
}
