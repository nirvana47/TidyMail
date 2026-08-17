package com.tidymail.support;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Random;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class SyntheticMailboxGenerator {

    public static final long DEFAULT_SEED = 18_2026L;
    public static final int DEFAULT_ROW_COUNT = 10_000;
    private static final int BATCH_SIZE = 1_000;
    private static final int SENDER_COUNT = 240;
    private static final Instant BASE_TIME = Instant.parse("2026-01-01T12:00:00Z");

    private static final String INSERT = """
        INSERT INTO message_metadata(
            account_id, provider, provider_message_id, provider_thread_id,
            from_address, from_display_name, from_domain, subject_header,
            received_at, first_seen_at, last_synced_at, unread, size_estimate,
            has_attachments, provider_revision, full_scan_generation,
            attachment_state, attachment_observed_at, attachment_source,
            labels, to_values, cc_values,
            bcc_values, list_unsubscribe_values, list_unsubscribe_post_values,
            list_id_values, references_values, in_reply_to_values, reply_to_values,
            return_path_values, delivered_to_values, precedence_values,
            auto_submitted_values, message_id_values, feedback_id_values,
            unsubscribe_dkim_pass, unsubscribe_dkim_covers_list_unsubscribe,
            unsubscribe_dkim_covers_list_unsubscribe_post, unsubscribe_auth_reason
        )
        VALUES (
            ?, 'google', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
        )
        """;

    private final JdbcTemplate jdbc;
    private final UserAccountFixtures accounts;

    public SyntheticMailboxGenerator(JdbcTemplate jdbc, UserAccountFixtures accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    public SeedResult seed() {
        return seed(DEFAULT_SEED, DEFAULT_ROW_COUNT);
    }

    public SeedResult seed(int rowCount) {
        return seed(DEFAULT_SEED, rowCount);
    }

    public SeedResult seed(long seed, int rowCount) {
        if (rowCount < DEFAULT_ROW_COUNT) {
            throw new IllegalArgumentException("Synthetic mailboxes require at least 10,000 rows");
        }
        long accountId = accounts.createAccount("owner-" + seed + "@mailbox.test");
        Random random = new Random(seed);
        jdbc.execute((Connection connection) -> {
            insertRows(connection, accountId, seed, rowCount, random);
            return null;
        });
        return new SeedResult(accountId, rowCount, seed);
    }

    private static void insertRows(
            Connection connection,
            long accountId,
            long seed,
            int rowCount,
            Random random) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            int threadSender = -1;
            for (int row = 0; row < rowCount; row++) {
                int sender = row % 3 == 0 ? skewedSender(random) : threadSender;
                threadSender = sender;
                int domain = sender % 24;
                boolean sent = row % 19 == 0;
                boolean bulk = !sent && sender % 5 == 0;
                boolean unread = !sent && random.nextDouble() < 0.38;
                boolean inbox = !sent && random.nextDouble() < 0.62;
                boolean attachment = random.nextDouble() < 0.11;
                Instant received = BASE_TIME
                    .minus(random.nextInt(730), ChronoUnit.DAYS)
                    .minus(random.nextInt(1_440), ChronoUnit.MINUTES);

                String externalAddress = "sender" + sender + "@example" + domain + ".test";
                String ownerAddress = "owner-" + seed + "@mailbox.test";
                String address = sent ? ownerAddress : externalAddress;
                String displayName = sent ? "Synthetic Owner" : "Synthetic Sender " + sender;
                String addressDomain = sent ? "mailbox.test" : "example" + domain + ".test";
                String recipient = sent ? externalAddress : ownerAddress;
                String providerId = "synthetic-" + seed + "-" + row;
                String threadId = "synthetic-thread-" + seed + "-" + (row / 3);
                String[] labels = labels(inbox, unread, bulk, sent);
                String[] empty = new String[0];
                String[] unsubscribe = bulk
                    ? new String[] {"https://unsubscribe.example" + domain + ".test/s/" + sender}
                    : empty;
                String[] unsubscribePost = bulk
                    ? new String[] {"List-Unsubscribe=One-Click"}
                    : empty;
                String[] listId = bulk
                    ? new String[] {"synthetic-list-" + sender + ".example" + domain + ".test"}
                    : empty;
                String[] messageId = new String[] {
                    "<synthetic-" + seed + "-" + row + "@example" + domain + ".test>"
                };

                int parameter = 1;
                statement.setLong(parameter++, accountId);
                statement.setString(parameter++, providerId);
                statement.setString(parameter++, threadId);
                statement.setString(parameter++, address);
                statement.setString(parameter++, displayName);
                statement.setString(parameter++, addressDomain);
                statement.setString(parameter++, "Synthetic message " + row + " from sender " + sender);
                statement.setTimestamp(parameter++, Timestamp.from(received));
                statement.setTimestamp(parameter++, Timestamp.from(received.plusSeconds(5)));
                statement.setTimestamp(parameter++, Timestamp.from(BASE_TIME));
                statement.setBoolean(parameter++, unread);
                statement.setLong(parameter++, 1_024L + random.nextInt(2_000_000));
                statement.setBoolean(parameter++, attachment);
                statement.setBigDecimal(parameter++, new BigDecimal(Long.toUnsignedString(
                    seed + row + 1)));
                statement.setObject(parameter++, UUID.nameUUIDFromBytes(
                    ("generation-" + seed + "-" + row / BATCH_SIZE)
                        .getBytes(StandardCharsets.US_ASCII)));
                statement.setString(parameter++, attachment ? "PRESENT" : "ABSENT");
                statement.setTimestamp(parameter++, Timestamp.from(BASE_TIME));
                statement.setString(parameter++, "SYNTHETIC");
                parameter = setArray(connection, statement, parameter, labels);
                parameter = setArray(connection, statement, parameter, new String[] {recipient});
                parameter = setArray(connection, statement, parameter, empty);
                parameter = setArray(connection, statement, parameter, empty);
                parameter = setArray(connection, statement, parameter, unsubscribe);
                parameter = setArray(connection, statement, parameter, unsubscribePost);
                parameter = setArray(connection, statement, parameter, listId);
                parameter = setArray(connection, statement, parameter,
                    row % 3 == 0 ? empty : new String[] {"<synthetic-ref-" + (row - 1) + ">"});
                parameter = setArray(connection, statement, parameter, empty);
                parameter = setArray(connection, statement, parameter, new String[] {address});
                parameter = setArray(connection, statement, parameter, new String[] {address});
                parameter = setArray(connection, statement, parameter,
                    new String[] {"owner-" + seed + "@mailbox.test"});
                parameter = setArray(connection, statement, parameter,
                    bulk ? new String[] {"bulk"} : empty);
                parameter = setArray(connection, statement, parameter,
                    bulk ? new String[] {"auto-generated"} : empty);
                parameter = setArray(connection, statement, parameter, messageId);
                parameter = setArray(connection, statement, parameter, empty);
                statement.setBoolean(parameter++, bulk);
                statement.setBoolean(parameter++, bulk);
                statement.setBoolean(parameter++, bulk);
                statement.setString(parameter, bulk ? "TRUSTED_PASS" : null);
                statement.addBatch();

                if ((row + 1) % BATCH_SIZE == 0) {
                    statement.executeBatch();
                }
            }
            if (rowCount % BATCH_SIZE != 0) {
                statement.executeBatch();
            }
        }
    }

    private static int setArray(
            Connection connection,
            PreparedStatement statement,
            int parameter,
            String[] values) throws SQLException {
        Array array = connection.createArrayOf("text", values);
        statement.setArray(parameter, array);
        return parameter + 1;
    }

    private static int skewedSender(Random random) {
        return Math.min(SENDER_COUNT - 1,
            (int) (Math.pow(random.nextDouble(), 2.6) * SENDER_COUNT));
    }

    private static String[] labels(boolean inbox, boolean unread, boolean bulk, boolean sent) {
        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        if (sent) {
            labels.add("SENT");
            return labels.toArray(String[]::new);
        }
        if (inbox) labels.add("INBOX");
        if (unread) labels.add("UNREAD");
        if (bulk) labels.add("CATEGORY_PROMOTIONS");
        return labels.toArray(String[]::new);
    }

    public record SeedResult(long accountId, int rowCount, long seed) {}
}
