package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class Phase14InteractionFidelityTest {
    @Test
    void workspaceTemplatesRemoveExportsAndKeepProgressiveFallbacks()
            throws Exception {
        String senders = template("senders.html");
        String unread = template("unread.html");
        String reviewHistory = template("fragments/review-history-frag.html");

        for (String authenticatedTemplate : List.of(
                senders, unread, reviewHistory)) {
            assertThat(authenticatedTemplate).doesNotContain(
                ".csv", "snapshot", "download", "workspace-download",
                "analytics-download", "Export");
        }
        assertThat(senders).doesNotContain("selection-preview");
        assertThat(unread).doesNotContain("selection-preview");
    }

    @Test
    void categorySelectionUsesSenderAddressesWithoutStaging() throws Exception {
        String unreadRows = template("fragments/unread-results-frag.html");
        String script = Files.readString(Path.of("src/main/resources/static/js/cleanup-categories.js"));
        assertThat(unreadRows).contains("data-sender-address", "data-selection=\"unread-selection\"");
        assertThat(script).contains("populateSelectedSenders", "row?.dataset.senderAddress")
            .doesNotContain("reconcileCleanupResult", "prepareDeleteDialog");
    }

    private static String template(String relativePath) throws Exception {
        return Files.readString(
            Path.of("src/main/resources/templates").resolve(relativePath));
    }
}
