package com.tidymail.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tidymail.dto.AnalyticsQuery.DateRange;
import com.tidymail.support.PostgresJourneyTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class AnalyticsMailboxRepositoryTest extends PostgresJourneyTest {
    private AnalyticsMailboxRepository repository;
    private long ownerId;
    private long foreignId;
      private long nextProviderMessageId;
    private final Instant start = Instant.parse("2026-08-01T00:00:00Z");
    private final Instant end = Instant.parse("2026-08-03T00:00:00Z");

    @BeforeEach
    void setUp() {
        ownerId = userAccounts.createAccount("analytics-owner@example.test");
        foreignId = userAccounts.createAccount("analytics-foreign@example.test");
        repository = new AnalyticsMailboxRepository(
            new NamedParameterJdbcTemplate(jdbc));
    }

    @Test
    void tabCountsAndRankingsUseOneAccountAndSelectedPeriod() {
        insert(ownerId, start.plusSeconds(10), "news@example.com", "example.com",
              true, 12L * 1024 * 1024, "ARRAY['INBOX']", "ARRAY[]::TEXT[]");
        insert(ownerId, start.plusSeconds(20), "news@example.com", "example.com",
              false, 1_000L, "ARRAY['INBOX']", "ARRAY[]::TEXT[]");
        insert(ownerId, start.plusSeconds(30), "owner@example.com", "example.com",
            false, 11L * 1024 * 1024, "ARRAY['SENT']", "ARRAY['friend@example.net']");
        insert(foreignId, start.plusSeconds(40), "foreign@example.com", "example.com",
              false, 1_000L, "ARRAY['INBOX']", "ARRAY[]::TEXT[]");
        insert(ownerId, end.plusSeconds(10), "late@example.com", "example.com",
              false, 1_000L, "ARRAY['INBOX']", "ARRAY[]::TEXT[]");

        var buckets = repository.countsByBuckets(
            ownerId, List.of(new DateRange(start, start.plusSeconds(86_400)),
                new DateRange(start.plusSeconds(86_400), end)));

        assertThat(buckets).extracting(
            AnalyticsMailboxRepository.MailboxBucket::received,
            AnalyticsMailboxRepository.MailboxBucket::sent,
            AnalyticsMailboxRepository.MailboxBucket::read)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(2L, 1L, 1L),
                org.assertj.core.groups.Tuple.tuple(0L, 0L, 0L));
        assertThat(repository.topReceivedEmails(ownerId, new DateRange(start, end), 10))
            .singleElement().satisfies(row -> {
                assertThat(row.label()).isEqualTo("news@example.com");
                assertThat(row.count()).isEqualTo(2);
            });
        assertThat(repository.topReceivedDomains(ownerId, new DateRange(start, end), 10))
            .singleElement().satisfies(row -> assertThat(row.count()).isEqualTo(2));
        assertThat(repository.topSentEmails(ownerId, new DateRange(start, end), 10))
            .singleElement().satisfies(row ->
                assertThat(row.label()).isEqualTo("friend@example.net"));
        assertThat(repository.sizeMetrics(ownerId, new DateRange(start, end)))
            .hasValueSatisfying(sizes -> {
                assertThat(sizes.unreadInboxBytes()).isEqualTo(12L * 1024 * 1024);
                assertThat(sizes.inboxOverTenMb()).isEqualTo(1);
                assertThat(sizes.sentOverTenMb()).isEqualTo(1);
            });
    }

    @Test
    void rankingLimitCannotExceedTen() {
        assertThatThrownBy(() ->
            repository.topReceivedEmails(ownerId, new DateRange(start, end), 11))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("10");
    }

    @Test
    void missingFullMessageSizeMakesAllSizeCardsUnavailable() {
        insert(ownerId, start.plusSeconds(10), "news@example.com", "example.com",
              true, null, "ARRAY['INBOX']", "ARRAY[]::TEXT[]");

        assertThat(repository.sizeMetrics(ownerId, new DateRange(start, end))).isEmpty();
    }

    private void insert(
            long accountId,
            Instant receivedAt,
            String from,
            String domain,
            boolean unread,
            Long sizeEstimate,
            String labels,
            String recipients) {
        jdbc.update("""
            INSERT INTO message_metadata(
                  account_id,provider,provider_message_id,received_at,from_address,
                  from_domain,unread,size_estimate,labels,to_values)
              VALUES (?, 'gmail', ?, ?, ?, ?, ?, ?, %s, %s)
            """.formatted(labels, recipients),
              accountId, "analytics-" + ++nextProviderMessageId,
              java.sql.Timestamp.from(receivedAt), from, domain, unread, sizeEstimate);
    }
}
