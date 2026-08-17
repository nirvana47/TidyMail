package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Phase17CleanupCategoryTest {
    private static final Path TEMPLATES = Path.of("src/main/resources/templates");
    private static final Path FRAGMENTS = TEMPLATES.resolve("fragments");
    private static final Path CSS = Path.of("src/main/resources/static/css/workspace.css");
    private static final Path SCRIPT =
        Path.of("src/main/resources/static/js/cleanup-categories.js");

    @Test
    void cleanupHeaderPlacesHelpAndExpandControlInTitleRow() throws Exception {
        String senders = Files.readString(TEMPLATES.resolve("senders.html"));
        String unread = Files.readString(TEMPLATES.resolve("unread.html"));
        String css = Files.readString(CSS);

        assertHeaderContract(senders, "Bulk Archive/Delete",
            "How Bulk Archive/Delete staging works");
        assertHeaderContract(unread, "Unread Only", "How Unread Only staging works");
        assertThat(senders).doesNotContain("class=\"cleanup-page-actions\"");
        assertThat(unread).doesNotContain("class=\"cleanup-page-actions\"");
        assertThat(css)
            .contains(
                ".cleanup-header-control-row",
                  ".page-help-icon",
                  ".page-help-tooltip",
                ".cleanup-header-toggle",
                "min-height: 44px",
                "margin-left: auto");
    }

    @Test
    void categoryFragmentsUseRowOnlyContinuationsAndNoStaleActionHelpers()
            throws Exception {
        String senders = Files.readString(FRAGMENTS.resolve("senders-results-frag.html"));
        String unread = Files.readString(FRAGMENTS.resolve("unread-results-frag.html"));
        String controller = Files.readString(Path.of(
            "src/main/java/com/tidymail/controller/WorkspaceController.java"));

        assertThat(senders)
            .contains(
                "th:fragment=\"categoryChunk(",
                "th:fragment=\"categoryRows(",
                "<span>Sender</span>",
                "<span>Domain</span>",
                "<span>All Mail unread</span>",
                "<span>Size (Estimated)</span>",
                "category.senderCount == 0 ? '0 KB'",
                "data-category-url=${categoryUrls[category.key]}",
                "User generated")
            .doesNotContain(
                "<span>Action</span>",
                "data-label=\"Action\"",
                "Select senders first, then choose where they belong.",
                "Move selected",
                "Create category");
        assertThat(unread)
            .contains(
                "th:fragment=\"categoryChunk(",
                "th:fragment=\"categoryRows(",
                "<span>Sender</span>",
                "<span>Domain</span>",
                "<span>Inbox unread</span>",
                "<span>Size (Estimated)</span>")
            .doesNotContain(
                "<span>Action</span>",
                "data-label=\"Action\"",
                "Select senders first, then choose where they belong.",
                "Move selected",
                "Create category");
        assertThat(controller)
            .contains(
                "offset > 0",
                "fragments/senders-results-frag :: categoryRows",
                "fragments/unread-results-frag :: categoryRows");
    }

    @Test
    void expandAllUsesCurrentDomAndCategoryLocalFailures() throws Exception {
        String script = Files.readString(SCRIPT);

        assertThat(script)
            .contains(
                "currentCleanupCategories()",
                "document.querySelectorAll(\".cleanup-categories > details\")",
                "openCategory(category)",
                "loadInitialCategory(category, content)",
                "htmx:responseError",
                "showCategoryLoadError",
                "category.open = false")
            .doesNotContain(
                "const categories = Array.from",
                "categories.forEach((category) => {\n        category.addEventListener");
    }

    @Test
    void userCreatedCategoryFlowRemainsReachableThroughContextualDialog()
            throws Exception {
        String senders = Files.readString(TEMPLATES.resolve("senders.html"));
        String unread = Files.readString(TEMPLATES.resolve("unread.html"));

        assertThat(senders)
            .contains(
                "data-dialog-open=\"sender-category-dialog\"",
                "id=\"sender-category-dialog\"",
                "Create a new category",
                "New categories appear with a User generated tag.",
                "data-selected-senders");
        assertThat(unread)
            .contains(
                "data-dialog-open=\"unread-category-dialog\"",
                "id=\"unread-category-dialog\"",
                "Create a new category",
                "New categories appear with a User generated tag.",
                "data-selected-senders");
    }

    private static void assertHeaderContract(
            String html, String title, String helpLabel) {
        assertThat(html)
            .contains(
                "class=\"page-header cleanup-page-header\"",
                "class=\"cleanup-header-control-row\"",
                "<h1>" + title + "</h1>",
                "aria-label=\"" + helpLabel + "\"",
                 "class=\"page-help-icon\"",
                 "role=\"tooltip\"",
                "class=\"cleanup-header-toggle\" data-category-toggle-all",
                "Expand all");
         assertThat(html)
             .doesNotContain("<details class=\"page-help\"", "<summary aria-label=\"How");
        assertThat(html.indexOf("<h1>" + title + "</h1>"))
            .isLessThan(html.indexOf("class=\"cleanup-header-toggle\""));
    }
}
