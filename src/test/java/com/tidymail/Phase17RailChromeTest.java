package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Phase17RailChromeTest {
    private static final Path RAIL =
        Path.of("src/main/resources/templates/fragments/workspace-rail.html");
    private static final Path CSS =
        Path.of("src/main/resources/static/css/workspace.css");

    @Test
    void brandRemainsClickableHomeChromeWithVisibleFocus()
            throws Exception {
        String rail = Files.readString(RAIL);
        String css = Files.readString(CSS);

        assertThat(rail)
            .contains(
                "<a class=\"rail-brand\" th:href=\"@{/senders}\" aria-label=\"TidyMail home\">",
                "<strong>TidyMail</strong>",
                "<small>Inbox cleanup</small>");
        assertThat(css)
            .contains(
                ".rail-brand {",
                "text-decoration: none",
                ".rail-brand:hover",
                ".rail-brand:focus-visible",
                "outline: 2px solid var(--tm-primary)")
            .doesNotContain(".rail-brand { text-decoration: underline");
    }

    @Test
    void utilityMenuRowsSharePaddingAndDeleteDataKeepsPostCsrfConfirmation()
            throws Exception {
        String rail = Files.readString(RAIL);
        String css = Files.readString(CSS);

        assertThat(rail)
            .contains(
                "<a class=\"rail-menu-link\" href=\"mailto:feedback@example.com\">Feedback</a>",
                "class=\"rail-logout\"",
                "<summary class=\"rail-menu-row delete-data-summary\">Delete my data and logout</summary>",
                "method=\"post\" action=\"/logout/delete-data\"",
                "th:name=\"${_csrf.parameterName}\"",
                "th:value=\"${_csrf.token}\"",
                "name=\"confirmation\" value=\"DELETE\"",
                "window.confirm('Delete every connected account and all TidyMail data");
        assertThat(rail).doesNotContain("href=\"/logout/delete-data\"");
        assertThat(css)
            .contains(
                ".rail-menu-panel button,\n.rail-menu-link,\n.rail-menu-row",
                "padding: var(--space-2)",
                "justify-content: flex-start",
                ".delete-data-control summary::marker",
                ".delete-data-control summary::-webkit-details-marker",
                ".rail-menu-row:focus-visible")
            .doesNotContain(".delete-data-control summary {\n    padding-left");
    }

    @Test
    void collapseControlUsesHorizontalArrowAndSuppressesDefaultMarker()
            throws Exception {
        String rail = Files.readString(RAIL);
        String css = Files.readString(CSS);

        assertThat(rail)
            .contains(
                "class=\"rail-collapse-affordance rail-collapse-affordance-open\" aria-hidden=\"true\">‹</span>",
                "class=\"rail-collapse-affordance rail-collapse-affordance-closed\" aria-hidden=\"true\">›</span>",
                "<span class=\"rail-collapse-open\">Collapse rail</span>",
                "<span class=\"rail-collapse-closed\">Expand rail</span>");
        assertThat(css)
            .contains(
                ".rail-collapse-control::marker",
                ".rail-collapse-control::-webkit-details-marker",
                ".rail-collapse-affordance-open",
                ".rail-collapse-affordance-closed",
                ".workspace-rail-frame:not([open]) .rail-collapse-affordance-open",
                ".workspace-rail-frame:not([open]) .rail-collapse-affordance-closed")
            .doesNotContain("writing-mode: vertical-rl");
    }

    @Test
    void railLabelsStayVisibleTextOnlyWhileCountsRemainAccessible()
            throws Exception {
        String rail = Files.readString(RAIL);

        assertThat(rail)
            .contains(
                "<span>Bulk Archive/Delete</span>",
                "<span>Unread Only</span>",
                "<span>Bulk Unsubscribe</span>",
                "<span>Review</span>",
                "<span>Analytics</span>",
                "workspace.inboxUnreadCountAvailable()",
                "'Unread Only, ' + workspace.inboxUnreadCount + ' Inbox unread messages'",
                "workspace.queueCountAvailable()",
                "'Review, ' + workspace.queuedActionCount + ' queued actions'");
        assertThat(rail)
            .doesNotContain(
                "rail-nav-badge",
                "class=\"badge\"",
                "Queued actions:",
                "Unread: ",
                "Review: ");
    }
}
