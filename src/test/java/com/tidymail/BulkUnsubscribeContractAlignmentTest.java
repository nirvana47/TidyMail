package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tidymail.dto.UnsubscribeQuery;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class BulkUnsubscribeContractAlignmentTest {

    @Test
    void queryKeepsExactRangeCategorySearchAndOneFallbackActionCanonical() {
        UnsubscribeQuery query = UnsubscribeQuery.parse(Map.of(
            "account", List.of("7"),
            "category", List.of("archived_deleted"),
            "from", List.of("2026-05-10"),
            "to", List.of("2026-08-10"),
            "q", List.of("example.com"),
            "defaultAction", List.of("delete"),
            "sort", List.of("read_desc"),
            "page", List.of("2")));

        assertThat(query.category()).isEqualTo(UnsubscribeQuery.Category.ARCHIVED_DELETED);
        assertThat(query.from()).isEqualTo(LocalDate.parse("2026-05-10"));
        assertThat(query.to()).isEqualTo(LocalDate.parse("2026-08-10"));
        assertThat(query.defaultAction()).isEqualTo(UnsubscribeQuery.DefaultAction.DELETE);
        assertThat(UnsubscribeQuery.parseCanonical(query.canonicalParameters()))
            .isEqualTo(query);
        assertThat(query.endExclusive()).isAfter(query.startInclusive());

        assertThatThrownBy(() -> UnsubscribeQuery.parse(Map.of(
            "defaultAction", List.of("archive_delete"))))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400 BAD_REQUEST");
    }

    @Test
    void templatesExposeIndividualWorkflowAndReuseSharedStatsModal() throws Exception {
        String page = Files.readString(
            Path.of("src/main/resources/templates/unsubscribe.html"));
        String results = Files.readString(
            Path.of("src/main/resources/templates/fragments/unsubscribe-results-frag.html"));
        String statsController = Files.readString(
            Path.of("src/main/java/com/tidymail/controller/UnsubscribeStatsController.java"));

        assertThat(page)
            .contains("Unhandled", "Unsubscribed", "Archived-Deleted", "Last week",
                "Last month", "Last 3 months", "Last year", "Sender or domain")
            .contains("name=\"defaultAction\"", "type=\"hidden\"")
            .contains("class=\"unsubscribe-top-controls\"")
            .doesNotContain("Archive/Delete", "fragments/workspace-state",
                "class=\"unsubscribe-settings\"", "<span>Settings</span>");
        assertThat(results)
            .contains("class=\"unsubscribe-sender-list\"", "class=\"unsubscribe-row\"",
                "Sender", "# emails", "Read %", "Recommended action", "Unsubscribe",
                "Use menu", "View stats", "Archive all", "Delete all",
                "name=\"action\" value=\"ARCHIVE\"",
                "name=\"action\" value=\"TRASH\"")
            .contains("/unsubscribe/sender-action-preview")
            .doesNotContain(">Archive/Delete<", "Unsubscribe successful",
                "metric-strip", "unsubscribe-sender-table", "<table");
        assertThat(statsController)
            .contains("UnsubscribeStatsService", "email-stats-modal-frag :: modal");
    }
}
