package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class Phase13WorkspaceResponsiveTest {
    private static final Path TEMPLATES =
        Path.of("src/main/resources/templates");
    private static final List<String> PAGES = List.of(
        "senders.html", "unread.html", "review.html",
        "activity.html");

    @Test
    void allFivePagesHaveOneMainOneH1SkipViewportAndCurrentNavigation() throws Exception {
        for (String page : PAGES) {
            String html = read(page);
            assertThat(html).as(page)
                .contains("<meta name=\"viewport\"", "class=\"skip-link\"",
                    "href=\"#workspace-main\"", "<main id=\"workspace-main\"");
            assertThat(count(html, "<main")).as(page).isEqualTo(1);
            assertThat(count(html, "<h1")).as(page).isEqualTo(1);
        }
        String rail = read("fragments/workspace-rail.html");
        assertThat(rail).contains(
            "aria-current", "Senders", "Unread Only",
            "Action history");
    }

    @Test
    void activityRailAndFactsReflowAtApprovedBreakpoints() throws Exception {
        String page = read("activity.html");
        String fragment = read("fragments/activity-status.html");
        String rail = read("fragments/workspace-rail.html");
        String css = Files.readString(
            Path.of("src/main/resources/static/css/workspace.css"));

        assertThat(page).contains(
            "class=\"workspace-shell activity-page\"",
            "class=\"activity-account-context\"",
            "id=\"workspace-main\"");
        assertThat(fragment).contains(
            "class=\"activity-data-table activity-facts-table\"",
            "class=\"activity-data-table activity-lanes-table\"",
            "class=\"activity-data-table activity-events-table\"",
            "class=\"activity-events\"",
            "class=\"activity-actions\"");
        assertThat(rail).contains(
            "<span>Sync / Activity</span>",
            "workspace.route.value == 'activity'");
        assertThat(css).contains(
            "@media (max-width: 980px)",
            "grid-template-columns: repeat(6, minmax(0, 1fr))",
            ".activity-data-table",
            ".activity-table-wrap",
            "@media (max-width: 720px)",
            ".rail-navigation {\n"
                + "        grid-template-columns: repeat(2, minmax(0, 1fr))",
            ".activity-lanes-table thead,\n    .activity-events-table thead",
            "content: attr(data-label)",
            "grid-template-columns: minmax(112px, 38%) minmax(0, 1fr)",
            "@media (max-width: 375px)",
            ".activity-page main {\n        padding-inline: var(--space-2)",
            ".rail-navigation { grid-template-columns: 1fr; }",
            "overflow-wrap: anywhere",
            "min-height: 44px");
        assertThat(css).doesNotContain(
            ".rail-navigation a:last-child { grid-column: 1 / -1; }\n"
                + "    .activity");
    }

    @Test
    void noExportAndTopControlsMatchTheCurrentDesignContract() throws Exception {
        assertThat(read("senders.html"))
            .contains("Expand all")
            .doesNotContain("decision-filters", ".csv", "download", "Export");
        assertThat(read("unread.html"))
            .contains("Expand all")
            .doesNotContain("decision-filters", ".csv", "download", "Export");
        assertThat(read("fragments/review-history-frag.html"))
            .doesNotContain(".csv", "download", "Export");

    }

    @Test
    void templatesContainNoDecorativeLinksOrPhasePlaceholders() throws Exception {
        try (var files = Files.walk(TEMPLATES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".html")).toList()) {
                String html = Files.readString(file);
                assertThat(html).as(file.toString())
                    .doesNotContain("href=\"#\"", "Available in Phase 13",
                        "Share snapshot", "fully removed", "Configure view",
                        "onclick=", "javascript:");
            }
        }
    }

    @Test
    void cssProvidesNarrowTouchContainmentWrappingFocusAndReducedMotion()
            throws Exception {
        String css = Files.readString(
            Path.of("src/main/resources/static/css/workspace.css"));
        assertThat(css).contains(
            ":focus-visible", "@media (max-width: 720px)",
            "@media (max-width: 375px)", "min-height: 44px",
            "min-width: 44px", "overflow-wrap: anywhere",
            "overscroll-behavior-inline: contain",
            "@media (prefers-reduced-motion: reduce)",
            ".analytics-metric-grid",
            "grid-template-columns: repeat(4, minmax(0, 1fr))",
            "@media (max-width: 860px)",
            "grid-template-columns: repeat(2, minmax(0, 1fr))",
            ".analytics-two-column",
            ".analytics-ranked-panel",
            ".analytics-ranked-table",
            ".analytics-chart-layout",
            ".analytics-chart-y-axis",
            ".analytics-chart-date",
            ".analytics-chart-tooltip",
            ".analytics-chart-column.is-selected",
            ".analytics-chart-bar:hover",
            ".analytics-chart-bar:focus-visible",
            "linear-gradient(180deg, #bfd2ff");
        assertThat(css).doesNotContain(
            "min-width: 375px", "html, body {\n    margin: 0;\n"
                + "    max-width: 100%;\n    overflow-x: hidden");
    }

    private static String read(String relative) throws Exception {
        return Files.readString(TEMPLATES.resolve(relative));
    }

    private static int count(String value, String needle) {
        int count = 0;
        int position = 0;
        while ((position = value.indexOf(needle, position)) >= 0) {
            count++;
            position += needle.length();
        }
        return count;
    }
}
