package com.tidymail.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SyntheticMailboxPiiGuardTest extends PostgresJourneyTest {

    private static final List<String> DISALLOWED_TOKENS = List.of(
        "@gmail.com",
        "@outlook.com",
        "@hotmail.com",
        "@yahoo.com",
        "@icloud.com",
        "bytedance",
        "costco"
    );

    @Test
    void syntheticMailboxContainsRealisticVolumeWithoutRealPii() {
        SyntheticMailboxGenerator.SeedResult result = seedSyntheticMailbox();

        assertThat(result.rowCount()).isGreaterThanOrEqualTo(10_000);
        assertThat(count("SELECT COUNT(*) FROM message_metadata"))
            .isEqualTo(result.rowCount());
        assertThat(count("""
            SELECT COUNT(*)
              FROM message_metadata
             WHERE from_address !~ '^sender[0-9]+@example[0-9]+[.]test$|^owner-[0-9]+@mailbox[.]test$'
                OR from_domain !~ '^example[0-9]+[.]test$|^mailbox[.]test$'
                OR from_display_name !~ '^Synthetic Sender [0-9]+$|^Synthetic Owner$'
                OR subject_header !~ '^Synthetic message [0-9]+ from sender [0-9]+$'
                OR provider_message_id !~ '^synthetic-[0-9]+-[0-9]+$'
                OR provider_thread_id !~ '^synthetic-thread-[0-9]+-[0-9]+$'
            """)).isZero();

        long bulk = count("""
            SELECT COUNT(*) FROM message_metadata
             WHERE cardinality(list_unsubscribe_values) > 0
            """);
        long nonBulk = count("""
            SELECT COUNT(*) FROM message_metadata
             WHERE cardinality(list_unsubscribe_values) = 0
            """);
        assertThat(bulk).isPositive();
        assertThat(nonBulk).isPositive();
        assertThat(bulk + nonBulk).isEqualTo(result.rowCount());
        assertThat(count("""
            SELECT COUNT(*)
              FROM (
                  SELECT provider_thread_id
                    FROM message_metadata
                   GROUP BY provider_thread_id
                  HAVING COUNT(DISTINCT from_address)
                      FILTER (WHERE from_address !~ '^owner-') > 1
              ) incoherent_threads
            """)).isZero();
        assertThat(count("""
            SELECT COUNT(*)
              FROM message_metadata
             WHERE 'SENT'=ANY(labels)
               AND (from_address !~ '^owner-'
                    OR 'INBOX'=ANY(labels)
                    OR 'UNREAD'=ANY(labels))
            """)).isZero();

        for (String token : DISALLOWED_TOKENS) {
            Long matches = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM message_metadata m
                 WHERE lower(to_jsonb(m)::text) LIKE ?
                """, Long.class, "%" + token + "%");
            assertThat(matches).as(token).isZero();
        }
    }

    @Test
    void fixedSeedProducesIdenticalMailboxRows() {
        seedSyntheticMailbox();
        String first = mailboxChecksum();

        resetState();
        seedSyntheticMailbox();
        String second = mailboxChecksum();

        assertThat(second).isEqualTo(first);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private String mailboxChecksum() {
        return jdbc.queryForObject("""
            SELECT md5(string_agg(to_jsonb(m)::text, E'\n' ORDER BY provider_message_id))
              FROM message_metadata m
            """, String.class);
    }
}
