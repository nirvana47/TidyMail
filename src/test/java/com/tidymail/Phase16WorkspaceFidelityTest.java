package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;

import com.tidymail.dto.AccountScopeOption;
import com.tidymail.dto.UnreadScopeCounts;
import com.tidymail.dto.WorkspacePageModel;
import com.tidymail.dto.WorkspaceRoute;
import com.tidymail.dto.WorkspaceState;
import com.tidymail.service.WorkspaceModelService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class Phase16WorkspaceFidelityTest {
    private static final Path TEMPLATES =
        Path.of("src/main/resources/templates");
    private static final Path FRAGMENTS = TEMPLATES.resolve("fragments");

    @Test
    void railLabelsAreExactTextOnlyWhileServerCountFactsRemainAvailable()
            throws Exception {
        String rail = Files.readString(FRAGMENTS.resolve("workspace-rail.html"));
        String workspaceModel = Files.readString(Path.of(
            "src/main/java/com/tidymail/service/WorkspaceModelService.java"));
        String pageModel = Files.readString(Path.of(
            "src/main/java/com/tidymail/dto/WorkspacePageModel.java"));

        assertThat(rail)
            .contains(
                "<span>Bulk Archive/Delete</span>",
                "<span>Unread Only</span>",
                "<span>Bulk Unsubscribe</span>",
                "<span>Review</span>",
                "<span>Analytics</span>",
                "th:href=\"@{/senders}\" aria-label=\"TidyMail home\"",
                "class=\"rail-account-menu\"",
                "class=\"rail-utility-menu\"",
                ">Settings</summary>",
                "Add new account",
                "/accounts/connect/google")
            .contains(
                "workspace.inboxUnreadCountAvailable()",
                "workspace.inboxUnreadCount",
                "workspace.queueCountAvailable()",
                "workspace.queuedActionCount")
            .doesNotContain(
                "rail-nav-badge",
                "Queued actions:",
                "Queued actions unavailable",
                "Active account",
                "Account and session",
                "href=\"/settings\"");
        assertThat(workspaceModel)
            .contains(
                "countByAccountIdInAndStatus",
                "unreadScopeCounts",
                WorkspaceModelService.class.getSimpleName());
        assertThat(pageModel)
            .contains(
                "queuedActionCount",
                "queueCountAvailable",
                "inboxUnreadCount",
                "inboxUnreadCountAvailable");
    }

    @Test
    void unavailableRailCountsRemainNonVisibleAndExplicitlyModeled()
            throws Exception {
        WorkspacePageModel unavailable = new WorkspacePageModel(
            WorkspaceRoute.REVIEW,
            "Owner",
            "owner@example.com",
            List.of(new AccountScopeOption("1", "owner@example.com", true)),
            List.of(1L),
            "1",
            "owner@example.com",
            null,
            UnreadScopeCounts.pending(),
            0,
            0,
            0,
            null,
            null,
            WorkspaceState.EMPTY,
            false,
            true,
            false);
        String rail = Files.readString(FRAGMENTS.resolve("workspace-rail.html"));

        assertThat(unavailable.queueCountAvailable()).isFalse();
        assertThat(unavailable.inboxUnreadCountAvailable()).isFalse();
        assertThat(rail)
            .contains(
                "workspace.queueCountAvailable()",
                "workspace.inboxUnreadCountAvailable()")
            .doesNotContain(
                "rail-nav-badge",
                "Queued actions:",
                "Unread: ",
                "Review: ");
    }

    @Test
    void workspacePagesUseSharedHeadersWithoutPersistentSubtitles()
            throws Exception {
        for (String page : List.of(
                "senders.html", "unread.html", "unsubscribe.html",
                "review.html", "analytics.html")) {
            String html = Files.readString(TEMPLATES.resolve(page));
            assertThat(html).as(page)
                  .contains(
                      "page-header",
                      "page-title-stack",
                      "class=\"page-help\"",
                      "class=\"page-help-icon\"",
                      "class=\"page-help-tooltip\" role=\"tooltip\"")
                .doesNotContain(
                      "<details class=\"page-help\"",
                      "<summary aria-label=\"How",
                    "class=\"route-heading\"",
                    "Open a category, scan the senders",
                    "Tidy up your unread emails",
                    "Handle one sender at a time",
                    "Approve staged exact scopes",
                    "Track message volume");
        }
    }

    @Test
    void unreadCopyIsExactAndStaleNotesAreRemovedWithoutChangingSemantics()
            throws Exception {
        String unreadPage = Files.readString(TEMPLATES.resolve("unread.html"));
        String unreadResults = Files.readString(
            FRAGMENTS.resolve("unread-results-frag.html"));
        String unreadSql = Files.readString(Path.of(
            "src/main/java/com/tidymail/repository/DecisionSurfaceSql.java"));

        assertThat(unreadPage)
            .contains(
                "<h1>Unread Only</h1>",
                "page-header",
                "aria-label=\"How Unread Only staging works\"",
                "@{/static/js/htmx-lite.js}")
            .contains(
                "name=\"surface\" value=\"UNREAD\"",
                "id=\"unread-bulk-stage\"",
                "Gmail Trash can typically be restored for up to 30 days")
            .doesNotContain(
                "Tidy up your unread emails",
                "Open a group of Inbox-unread mail and correct sender classification when needed.",
                "id=\"unread-search\"",
                "filter-summary",
                "workspace-download",
                "selection-preview");
        assertThat(unreadResults)
            .doesNotContain(
                "Counts and rows reflect the last completed metadata sync; provider changes appear after the next sync.",
                "Unread groups are not mutually exclusive. A sender can appear in more than one group.")
            .contains(
                "No unread senders are currently in this group.",
                "DecisionRowKey).unreadSender",
                "form=\"unread-bulk-stage\"");
        assertThat(unreadSql)
            .contains("AND m.unread = true", "hasLabel(\"m.labels\", \"INBOX\")")
            .doesNotContain("ARRAY_CONTAINS");
    }

    @Test
    void cleanupPagesUseRowAndFormFeedbackWithoutPersistentStatusPanel()
            throws Exception {
        String sendersPage = Files.readString(TEMPLATES.resolve("senders.html"));
        String unreadPage = Files.readString(TEMPLATES.resolve("unread.html"));
        String senderRows = Files.readString(FRAGMENTS.resolve("senders-results-frag.html"));
        String unreadRows = Files.readString(FRAGMENTS.resolve("unread-results-frag.html"));
        String script = Files.readString(Path.of(
            "src/main/resources/static/js/cleanup-categories.js"));
        String css = Files.readString(Path.of(
            "src/main/resources/static/css/workspace.css"));

        assertThat(sendersPage)
            .contains("data-cleanup-stage", "data-cleanup-feedback")
            .doesNotContain(
                "cleanup-action-result-frag :: status",
                "cleanup-action-status",
                "Ready to stage for Review",
                "selection-preview");
        assertThat(unreadPage)
            .contains(
                "data-cleanup-stage",
                "data-cleanup-feedback",
                "name=\"surface\" value=\"UNREAD\"",
                "Gmail Trash can typically be restored for up to 30 days")
            .doesNotContain(
                "cleanup-action-result-frag :: status",
                "cleanup-action-status",
                "Ready to stage for Review",
                "selection-preview");
        assertThat(senderRows)
            .contains("form=\"sender-bulk-stage\"")
            .doesNotContain("class=\"cleanup-row-state\"", "<span>Action</span>");
        assertThat(unreadRows)
            .contains("form=\"unread-bulk-stage\"")
            .doesNotContain("class=\"cleanup-row-state\"", "<span>Action</span>");
        assertThat(script)
            .contains(
                "reconcileCleanupResult(JSON.parse(event.detail.xhr.responseText), form)",
                "row.classList.add(\"is-staged\")",
                "staged.reused ? \"Already staged\" : \"Staged\"",
                "Action not staged. Select at least one sender before staging.",
                "data-cleanup-feedback")
            .doesNotContain(
                "document.getElementById(\"cleanup-action-status\")",
                "Ready to stage for Review",
                "Queued actions:");
        assertThat(css)
            .contains(".cleanup-row.is-staged", ".cleanup-stage-feedback")
            .doesNotContain(".cleanup-action-result");
    }

    @Test
    void bulkUnsubscribeUsesMockAlignedSenderListWithoutStatePanelOrGenericTable()
            throws Exception {
        String page = Files.readString(TEMPLATES.resolve("unsubscribe.html"));
        String results = Files.readString(
            FRAGMENTS.resolve("unsubscribe-results-frag.html"));
        String css = Files.readString(Path.of(
            "src/main/resources/static/css/workspace.css"));

        assertThat(page)
            .contains(
                "<h1>Bulk Unsubscribe</h1>",
                "class=\"unsubscribe-top-controls\"",
                "Sender or domain",
                "name=\"defaultAction\"")
            .doesNotContain(
                "fragments/workspace-state",
                "class=\"unsubscribe-settings\"",
                "<span>Settings</span>");
        assertThat(results)
            .contains(
                "class=\"unsubscribe-sender-list\"",
                "class=\"unsubscribe-list-head\"",
                "class=\"unsubscribe-row\"",
                "# emails",
                "Recommended action",
                "class=\"action-button\"",
                "Use menu",
                "name=\"action\" value=\"ARCHIVE\"",
                "name=\"action\" value=\"TRASH\"")
            .doesNotContain(
                "metric-strip",
                "unsubscribe-sender-table",
                "<table",
                "Sender / domain",
                "Email count");
        assertThat(css)
            .contains(
                ".unsubscribe-sender-list",
                ".unsubscribe-row",
                ".unsubscribe-list-head",
                ".action-button",
                ".unsubscribe-top-controls")
            .doesNotContain(".unsubscribe-sender-table");
    }

    @Test
    void reviewPageStartsWithHeaderTabsAndContextualOperationFeedbackOnly()
            throws Exception {
        String page = Files.readString(TEMPLATES.resolve("review.html"));
        String confirmation = Files.readString(
            FRAGMENTS.resolve("review-confirmation-frag.html"));
        String conflict = Files.readString(FRAGMENTS.resolve("review-conflict-frag.html"));
        String outcome = Files.readString(FRAGMENTS.resolve("review-outcome-frag.html"));

        assertThat(page)
            .contains(
                "<h1>Review</h1>",
                "aria-label=\"Review views\"",
                ">Queue</a>",
                ">History</a>",
                "review-confirmation-frag :: confirmation",
                "review-confirmation-frag :: unsubscribeConfirmation",
                "review-conflict-frag :: conflict",
                "review-outcome-frag :: outcome")
            .doesNotContain(
                "fragments/workspace-state",
                "Showing partial indexed data",
                "Metadata index is ready",
                "state-summary",
                "sync-form",
                "Queued</a>",
                "Approve staged exact scopes",
                "Approve selected or all eligible exact scopes");
        assertThat(page.indexOf("class=\"page-header\""))
            .isLessThan(page.indexOf("aria-label=\"Review views\""));
        assertThat(page.indexOf("aria-label=\"Review views\""))
            .isLessThan(page.indexOf("review-queue-frag :: queue"));
        assertThat(confirmation)
            .contains("id=\"review-confirmation\"", "Confirm execute", "Confirm undo",
                "Confirm unsubscribe request");
        assertThat(conflict).contains("id=\"review-conflict\"", "Review conflict");
        assertThat(outcome).contains("id=\"review-outcome\"", "Batch outcome recorded");
    }

    @Test
    void reviewQueueAndHistoryShareCompactTableHierarchyWithoutMetrics()
            throws Exception {
        String queue = Files.readString(FRAGMENTS.resolve("review-queue-frag.html"));
        String history = Files.readString(FRAGMENTS.resolve("review-history-frag.html"));
        String css = Files.readString(Path.of(
            "src/main/resources/static/css/workspace.css"));

        assertThat(queue)
            .contains(
                "class=\"review-data-view review-queue\"",
                ">Queue</h2>",
                "class=\"review-filter-form\"",
                "class=\"review-data-table-wrap table-scroll\"",
                "class=\"review-data-table review-table\"",
                "id=\"review-selection-actions\"",
                "selected > 1 ? \"Execute \" + selected : \"Execute\"",
                "selected > 1 ? \"Undo \" + selected : \"Undo\"")
            .doesNotContain(
                "class=\"metric-strip\"",
                "Queued action metrics",
                "Exact targets",
                "Conflicts to resolve",
                "Eligible to execute");
        assertThat(queue.indexOf(">Queue</h2>"))
            .isLessThan(queue.indexOf("class=\"review-filter-form\""));
        assertThat(queue.indexOf("class=\"review-filter-form\""))
            .isLessThan(queue.indexOf("class=\"review-data-table-wrap table-scroll\""));

        assertThat(history)
            .contains(
                "class=\"review-data-view\"",
                ">History</h2>",
                "class=\"review-filter-form review-filters\"",
                "class=\"review-data-table-wrap history-table-wrap\"",
                "class=\"review-data-table history-table\"",
                "Undo will target only the ${row.undoAppliedCount} messages recorded as applied.",
                "No automatic undo")
            .doesNotContain(
                "class=\"review-metrics\"",
                "History metrics",
                "Succeeded targets",
                "Failed targets");
        assertThat(history.indexOf(">History</h2>"))
            .isLessThan(history.indexOf("class=\"review-filter-form review-filters\""));
        assertThat(history.indexOf("class=\"review-filter-form review-filters\""))
            .isLessThan(history.indexOf("class=\"review-data-table-wrap history-table-wrap\""));

        assertThat(css)
            .contains(
                ".review-data-view",
                ".review-data-table-wrap",
                ".review-data-table",
                ".review-selection-bar[hidden]",
                ".review-data-table td::before")
            .doesNotContain(".review-metrics");
    }

    @Test
    void cleanupCategoryHeadersExposeSplitMetricsAndStayLazy()
            throws Exception {
        String senderRows = Files.readString(FRAGMENTS.resolve("senders-results-frag.html"));
        String unreadRows = Files.readString(FRAGMENTS.resolve("unread-results-frag.html"));
        String css = Files.readString(Path.of(
            "src/main/resources/static/css/workspace.css"));
        String script = Files.readString(Path.of(
            "src/main/resources/static/js/cleanup-categories.js"));

        assertThat(senderRows)
            .contains(
                "class=\"category-title\"",
                "class=\"category-meta\"",
                "category-sender-count",
                "category-size-metric",
                "0 KB")
            .contains(
                "<details class=\"cleanup-category\"",
                "data-category-url=${categoryUrls[category.key]}",
                "category-page-content",
                "Load more",
                "Load all")
            .doesNotContain("<details class=\"cleanup-category\" open");
        assertThat(unreadRows)
            .contains(
                "class=\"category-title\"",
                "class=\"category-meta\"",
                "category-sender-count",
                "category-unread-count")
            .contains(
                "data-category-url=${page.categoryUrls[category.key]}",
                "category-page-content")
            .doesNotContain("<details class=\"cleanup-category\" open");
        assertThat(css)
            .contains(
                "#sender-results.cleanup-categories",
                ".category-title",
                ".category-meta",
                ".category-size-metric",
                ".category-unread-count",
                ":focus-visible",
                "min-height: 44px")
            .doesNotContain(".category-count { border");
        assertThat(script)
            .contains(
                "content.replaceChildren()",
                "category.dataset.loadedCount = \"0\"",
                "loadInitialCategory(category, content)",
                "currentCleanupCategories()",
                "category.open = true");
    }

    @Test
    void cleanupSenderColumnsFollowAuthoritativeOrderAndSizeContract()
            throws Exception {
        String senderRows = Files.readString(FRAGMENTS.resolve("senders-results-frag.html"));
        String css = Files.readString(Path.of(
            "src/main/resources/static/css/workspace.css"));

        assertThat(senderRows)
            .contains(
                "class=\"cleanup-row cleanup-row-head\"",
                "<span>Sender</span>",
                "<span>Domain</span>",
                "<span>All Mail unread</span>",
                "<span>Size (Estimated)</span>",
                "class=\"cleanup-row-domain\" data-label=\"Domain\"",
                "class=\"cleanup-row-count\" data-label=\"All Mail unread\"",
                "class=\"cleanup-row-size\" data-label=\"Size (Estimated)\"")
            .doesNotContain("Message count", "Estimated ");
        assertThat(senderRows.indexOf("<span>Sender</span>"))
            .isLessThan(senderRows.indexOf("<span>Domain</span>"));
        assertThat(senderRows.indexOf("<span>Domain</span>"))
            .isLessThan(senderRows.lastIndexOf("<span>All Mail unread</span>"));
        assertThat(senderRows.lastIndexOf("<span>All Mail unread</span>"))
            .isLessThan(senderRows.indexOf("<span>Size (Estimated)</span>"));
        assertThat(senderRows)
            .contains(
                "class=\"cleanup-row\"",
                "data-sender-address=${row.senderAddress}",
                "name=\"rowKey\" th:value=\"${row.rowKey}\"")
            .doesNotContain("data-delete-source=\"closest\"");

        assertThat(css)
            .contains(
                ".cleanup-action",
                "border-radius: var(--radius-md)",
                ".cleanup-row-count",
                ".cleanup-row-size",
                ".cleanup-row-domain",
                ".cleanup-row-state",
                "@media (max-width: 720px)")
            .doesNotContain("overflow-x: hidden");
    }

    @Test
    void unreadSenderColumnsFollowAuthoritativeOrderAndSizeContract()
            throws Exception {
        String unreadRows = Files.readString(FRAGMENTS.resolve("unread-results-frag.html"));
        String css = Files.readString(Path.of(
            "src/main/resources/static/css/workspace.css"));

        assertThat(unreadRows)
            .contains(
                "<span>Sender</span>",
                "<span>Domain</span>",
                "<span>Inbox unread</span>",
                "<span>Size (Estimated)</span>",
                "class=\"cleanup-row-domain\" data-label=\"Domain\"",
                "class=\"cleanup-row-count\" data-label=\"Inbox unread\"",
                "class=\"cleanup-row-size\" data-label=\"Size (Estimated)\"",
                "DecisionRowKey).unreadSender")
            .doesNotContain("Message count", "Estimated ");
        assertThat(unreadRows.indexOf("<span>Sender</span>"))
            .isLessThan(unreadRows.indexOf("<span>Domain</span>"));
        assertThat(unreadRows.indexOf("<span>Domain</span>"))
            .isLessThan(unreadRows.indexOf("<span>Inbox unread</span>"));
        assertThat(unreadRows.indexOf("<span>Inbox unread</span>"))
            .isLessThan(unreadRows.indexOf("<span>Size (Estimated)</span>"));
        assertThat(css)
            .contains(
                ".cleanup-action",
                "border-radius: var(--radius-md)",
                ".cleanup-row-count",
                ".cleanup-row-size",
                ".cleanup-row-domain",
                ".cleanup-row-state",
                "@media (max-width: 720px)")
            .doesNotContain("overflow-x: hidden");
    }

    @Test
    void categoryManagementUsesMockCopyAndUserGeneratedTags()
            throws Exception {
        String senderRows = Files.readString(FRAGMENTS.resolve("senders-results-frag.html"));
        String unreadRows = Files.readString(FRAGMENTS.resolve("unread-results-frag.html"));
        String senderPage = Files.readString(TEMPLATES.resolve("senders.html"));
        String unreadPage = Files.readString(TEMPLATES.resolve("unread.html"));
        String script = Files.readString(Path.of(
            "src/main/resources/static/js/cleanup-categories.js"));
        String service = Files.readString(Path.of(
            "src/main/java/com/tidymail/service/SenderDecisionQueryService.java"));

        assertThat(senderRows)
            .contains(
                "User generated",
                "category.userGenerated")
            .doesNotContain("Correct classification", "Move selected", "Create category",
                "Select senders first, then choose where they belong.");
        assertThat(unreadRows)
            .doesNotContain("Correct classification", "Move selected", "Create category",
                "Select senders first, then choose where they belong.");
        assertThat(senderPage)
            .contains(
                "Change type",
                "Change categorization",
                "Lists and newsletters",
                "Receipts and transactions",
                "New categories appear with a User generated tag.",
                "hx-post=\"/api/sender-categories/organize\"",
                "data-selected-senders")
            .doesNotContain("Correct classification", "Save correction");
        assertThat(unreadPage)
            .contains(
                "id=\"unread-category-dialog\"",
                "Change type",
                "Change categorization",
                "Lists and newsletters",
                "Receipts and transactions",
                "New categories appear with a User generated tag.",
                "hx-post=\"/api/sender-categories/organize\"",
                "data-selected-senders")
            .doesNotContain("Correct classification", "Save correction");
        assertThat(script)
            .contains(
                "populateSelectedSenders(dialog, openButton)",
                "input[name='rowKey'][form='${formId}']:checked",
                "input.name = \"senderAddress\"",
                "input.value = senderAddress",
                "window.location.reload()");
        assertThat(service)
            .contains(
                "userGeneratedGroups",
                "customCategoryKey",
                "findAllByAccountIdOrderByCreatedAtAscIdAsc",
                "findAllByAccountIdAndCategoryIdOrderBySenderAddressAsc",
                "true,\n                false,\n                page")
            .doesNotContain("global");
    }

    @Test
    void analyticsUsesCompactHierarchyAlignedPanelsAndHoverOnlyCounts()
            throws Exception {
        String page = Files.readString(TEMPLATES.resolve("analytics.html"));
        String results = Files.readString(FRAGMENTS.resolve("analytics-results-frag.html"));
        String css = Files.readString(Path.of(
            "src/main/resources/static/css/workspace.css"));

        assertThat(page)
            .contains(
                "<h1>Analytics</h1>",
                "class=\"analytics-control-row\"",
                "<span>Time period</span>",
                "<span>Group by</span>",
                "analytics-apply",
                "type=\"submit\">Apply",
                "name=\"account\"",
                "name=\"action\"",
                "name=\"compare\"")
            .doesNotContain(
                "class=\"route-heading\"",
                "Track message volume",
                "analytics-filter-menu",
                ".csv",
                "snapshot",
                "download",
                "Export");
        assertThat(count(page, "<form class=\"analytics-control-row\"")).isEqualTo(1);

        assertThat(results)
            .contains(
                "Received",
                "Sent",
                "Read",
                "Archived/Deleted",
                "analytics-ranked-panel",
                "analytics-ranked-controls",
                "class=\"analytics-ranked-controls analytics-ranked-spacer\"",
                "class=\"compact-data-table analytics-ranked-table\"",
                "class=\"analytics-ranked-label\"",
                "class=\"analytics-ranked-count\"",
                "id=\"analytics-chart-axis\"",
                "class=\"analytics-chart-y-axis\"",
                "data-chart-y-tick=\"1\"",
                "class=\"analytics-chart-date\"",
                "th:text=\"${bucket.chartLabel()}\"",
                "class=\"analytics-chart-tooltip\"",
                "th:text=\"${bucket.tooltipDate()}\"",
                "updateYAxis(tab.dataset.metric)",
                "Date",
                "class=\"analytics-chart-bar\" type=\"button\"",
                "aria-pressed=\"false\"",
                "aria-label=|Archived/Deleted: ${bucket.value} on ${bucket.tooltipDate()}|",
                "No received messages in this range.",
                "No sent messages in this range.")
            .doesNotContain(
                "analytics-chart-value",
                "Exact chart values",
                "Metric chart values",
                "analytics-legend",
                "series-received",
                "series-cleaned",
                "label definition");

        assertThat(css)
            .contains(
                ".analytics-control-row",
                "grid-template-columns: minmax(360px, 1.4fr) minmax(180px, .65fr) minmax(112px, auto)",
                ".analytics-metric-grid",
                "grid-template-columns: repeat(4, minmax(0, 1fr))",
                "@media (max-width: 860px)",
                "grid-template-columns: repeat(2, minmax(0, 1fr))",
                ".analytics-ranked-panel",
                "grid-template-rows: auto 44px auto",
                ".analytics-ranked-table",
                "table-layout: fixed",
                ".analytics-chart-layout",
                ".analytics-chart-y-axis",
                ".analytics-chart-date",
                ".analytics-chart-tooltip",
                ".analytics-chart-column.is-selected",
                ".analytics-chart-bar:hover",
                ".analytics-chart-bar:focus-visible",
                "linear-gradient(180deg, #bfd2ff")
            .doesNotContain(".analytics-chart-value");
    }

    @Test
    void activityUsesApprovedTokensFocusAndReducedMotion() throws Exception {
        String page = Files.readString(TEMPLATES.resolve("activity.html"));
        String fragment = Files.readString(
            FRAGMENTS.resolve("activity-status.html"));
        String css = Files.readString(Path.of(
            "src/main/resources/static/css/workspace.css"));
        String activityCss = css.substring(css.indexOf(
            "/* Sync / Activity extends the existing flat workspace system. */"));

        assertThat(page).contains(
            "class=\"page-header\"",
            "class=\"page-title-stack\"",
            "class=\"page-help\" tabindex=\"0\"",
            "role=\"tooltip\"",
            "href=\"#workspace-main\"",
            "@{/static/js/htmx-lite.js}");
        assertThat(fragment).contains(
            "<section id=\"activity-status\"",
            "role=\"status\"",
            "aria-live=\"polite\"",
            "aria-atomic=\"false\"",
            "role=\"alert\" th:if=\"${status.alert}\"",
            "<table class=\"activity-data-table activity-facts-table\">",
            "<table class=\"activity-data-table activity-lanes-table\">",
            "<table class=\"activity-data-table activity-events-table\">",
            "<th scope=\"col\">Lane</th>",
            "<th scope=\"row\" data-label=\"Lane\"",
            "<time",
            "<progress",
            "outerHTML settle:120ms")
            .doesNotContain(
                "<article",
                "<details",
                "<img",
                "role=\"progressbar\"");
        assertThat(activityCss).contains(
            "var(--space-1)",
            "var(--space-2)",
            "var(--space-4)",
            "var(--tm-border)",
            "var(--tm-text-muted)",
            "var(--tm-primary)",
            ".activity-data-table",
            ".activity-table-wrap",
            "@media (prefers-reduced-motion: reduce)",
            "transition-duration: .01ms !important",
            "animation-iteration-count: 1 !important")
            .doesNotContain(
                "linear-gradient",
                "radial-gradient",
                "box-shadow:",
                "border-radius: 999px",
                "animation: infinite");
        assertThat(css).contains(
            ":focus-visible",
            "outline: 2px solid var(--tm-primary)",
            "outline-offset: 2px");
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
