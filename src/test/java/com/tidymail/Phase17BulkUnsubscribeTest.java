package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tidymail.dto.UnsubscribeQuery;
import com.tidymail.mail.AttachmentState;
import com.tidymail.repository.UnsubscribeCandidateRepository;
import com.tidymail.service.DecisionRowKey;
import com.tidymail.service.UnsubscribeStatsService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Phase17BulkUnsubscribeTest {
    @Test
    void unsubscribePageLoadsHtmxAndRowsHaveNonSilentTargets() throws Exception {
        String page = readTemplate("unsubscribe.html");
        String results = readTemplate("fragments/unsubscribe-results-frag.html");

        assertThat(page)
            .contains("@{/static/js/htmx-lite.js}")
            .contains("id=\"unsubscribe-inspection-slot\"")
            .contains("id=\"unsubscribe-action-preview-slot\"")
            .contains("id=\"sender-stats-modal-slot\"")
            .contains("data-stats-modal-close", "htmx:responseError")
              .contains("data-sender-stats-trigger")
              .contains("stats-modal-error")
            .doesNotContain(".csv", "snapshot", "download", "Export");
          assertThat(Files.readString(Path.of(
              "src/main/resources/static/js/htmx-lite.js")))
              .contains("AbortController", "controller.abort()", "window.clearTimeout(timeout)");
          assertThat(Files.readString(Path.of(
              "src/main/resources/static/css/workspace.css")))
              .contains(".stats-modal-error");
        assertThat(results)
            .contains("data-sender-stats-trigger")
            .contains("hx-target=\"#sender-stats-modal-slot\"")
            .contains("hx-disabled-elt=\"this\"")
            .contains("Loading...")
            .contains("data-unsubscribe-inspection-trigger")
            .contains("hx-target=\"#unsubscribe-inspection-slot\"")
            .contains("Preparing...")
            .contains("th:href=\"@{/unsubscribe/senders/{rowKey}/stats")
            .contains("th:href=\"@{/unsubscribe/candidates/{key}");
    }

    @Test
    void statsFragmentIsAccessibleModalWithoutWorkspaceShellOrInlineStyles()
            throws Exception {
        String modal = readTemplate("fragments/email-stats-modal-frag.html");
        String fallback = readTemplate("unsubscribe-stats.html");
        String css = Files.readString(Path.of(
            "src/main/resources/static/css/workspace.css"));

        assertThat(modal)
            .contains("class=\"stats-modal-overlay\"")
            .contains("class=\"stats-modal\"")
            .contains("role=\"dialog\"")
            .contains("aria-modal=\"true\"")
            .contains("aria-labelledby=\"email-stats-title\"")
            .contains("data-stats-modal-close aria-label=\"Close\"")
            .contains("class=\"modal-action-row\"")
            .contains("class=\"stats-distribution\"")
            .contains("class=\"stats-chart\"")
            .contains("class=\"stats-chart-y-axis\"")
            .contains("class=\"stats-chart-bars\"")
            .contains("class=\"stats-chart-fill\"")
            .contains("heightPercent(emailStats.distributionAxisMax())")
            .contains("showDistributionLabel(bucketStatus.index)")
            .contains("data-local-date")
            .contains("class=\"table-scroll stats-email-table\"")
            .contains("Sender stats", "Incoming email distribution",
                "<th scope=\"col\">Subject</th>")
            .doesNotContain("Subject and snippet")
            .doesNotContain("<style", "fragments/workspace-state");
        assertThat(fallback).contains("Sender statistics")
            .contains("fragments/email-stats-modal-frag :: modal");
        assertThat(css)
            .contains(".stats-modal-overlay", ".stats-modal",
                ".stats-modal-header", ".modal-action-row",
                ".stats-distribution", ".stats-chart-y-axis", ".stats-chart-bars",
                ".stats-chart-fill", ".stats-email-table")
            .contains("li:hover .stats-chart-plot strong")
            .contains("text-overflow: ellipsis")
            .contains("@media (max-width: 720px)");
        assertThat(Files.readString(Path.of(
            "src/main/resources/static/js/htmx-lite.js")))
            .contains("formatLocalDates", "Intl.DateTimeFormat",
                "time[data-local-date]", "date.toLocaleString()");
    }

    @Test
    void statsServiceReturnsExactCountAndBoundedRecentMessages() {
        UnsubscribeCandidateRepository repository =
            mock(UnsubscribeCandidateRepository.class);
        UnsubscribeStatsService service =
            new UnsubscribeStatsService(repository);
        String rowKey = DecisionRowKey.sender(7L, "sender@example.com");
        UnsubscribeQuery query = UnsubscribeQuery.parse(Map.of(
            "account", List.of("7"),
            "from", List.of("2026-08-01"),
            "to", List.of("2026-08-31")));
        Instant start = query.startInclusive();
        Instant end = query.endExclusive();
        when(repository.countSenderEmails(
            7L, "sender@example.com", start, end)).thenReturn(227L);
        when(repository.countSenderEmailBuckets(
            eq(7L), eq("sender@example.com"), anyList())).thenReturn(List.of(
                new UnsubscribeCandidateRepository.SenderEmailBucketCount(
                    "2026-08-01 to 2026-08-31", start, end, 227L)));
        when(repository.findSenderEmails(
            7L, "sender@example.com", start, end, 25)).thenReturn(List.of(
                new UnsubscribeCandidateRepository.SenderEmailFact(
                    "Sender", "First", Instant.parse("2026-08-31T12:00:00Z"),
                    true, AttachmentState.PRESENT),
                new UnsubscribeCandidateRepository.SenderEmailFact(
                    "Sender", "Second", Instant.parse("2026-08-30T12:00:00Z"),
                    false, AttachmentState.UNKNOWN)));

        var modal = service.model(List.of(7L), query, rowKey);

        assertThat(modal.title()).isEqualTo("Stats for Sender");
        assertThat(modal.senderLabel()).isEqualTo("Sender");
        assertThat(modal.emailCount()).isEqualTo(227L);
        assertThat(modal.distribution()).extracting(bucket -> bucket.count())
            .containsExactly(227L);
        assertThat(modal.maxDistributionCount()).isEqualTo(227L);
        assertThat(modal.distributionAxisMax()).isEqualTo(250L);
        assertThat(modal.distributionAxisTicks())
            .containsExactly(250L, 200L, 150L, 100L, 50L, 0L);
        assertThat(modal.distribution().getFirst().heightPercent(
            modal.distributionAxisMax())).isEqualTo(91);
        assertThat(modal.showDistributionLabel(0)).isTrue();
        assertThat(modal.emails()).hasSize(2);
        assertThat(modal.emails().getFirst().subject()).isEqualTo("First");
        assertThat(modal.emails().getFirst().attachmentLabel()).isEqualTo("Present");
        assertThat(modal.hasUnknownAttachmentState()).isTrue();
        assertThat(modal.rangeLabel())
            .isEqualTo("Aug 1, 2026 to Aug 31, 2026");
        assertThat(modal.topActions()).hasSize(2);
        verify(repository).findSenderEmails(
            7L, "sender@example.com", start, end, 25);
    }

    @Test
    void repositorySourceUsesExactCountsAndBoundedSenderEmailPreview()
            throws Exception {
        String repository = Files.readString(Path.of(
            "src/main/java/com/tidymail/repository/UnsubscribeCandidateRepository.java"));
        String service = Files.readString(Path.of(
            "src/main/java/com/tidymail/service/UnsubscribeStatsService.java"));

        assertThat(repository)
            .contains("countSenderEmails", "countSenderEmailBuckets",
                "LIMIT :limit", "limit < 1 || limit > 50");
        assertThat(service)
            .contains(
                "repository.countSenderEmails",
                "repository.countSenderEmailBuckets",
                "repository.findSenderEmails")
            .doesNotContain("candidateForSender(");
    }

    private static String readTemplate(String relativePath) throws Exception {
        return Files.readString(
            Path.of("src/main/resources/templates").resolve(relativePath));
    }
}
