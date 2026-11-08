package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tidymail.dto.ActionHistoryRow;
import com.tidymail.dto.HistoryQuery;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

class Phase12PrivacyTest {
    @Test
    void ACTN_10_historyQueryIsStrictBoundedAndCanonical() {
        LinkedMultiValueMap<String, String> values = new LinkedMultiValueMap<>();
        values.add("view", "history");
        values.add("account", "all");
        values.add("from", "2026-01-01");
        values.add("to", "2026-02-01");
        values.add("action", "archive");
        values.add("status", "partial");
        values.add("target", "example.com");
        values.add("page", "2");
        HistoryQuery query = HistoryQuery.parse(values);
        assertThat(query.canonicalParameters()).containsEntry("view", "history")
            .containsEntry("page", "2");

        values.add("status", "failed");
        assertThatThrownBy(() -> HistoryQuery.parse(values))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        HistoryQuery fromOnly = new HistoryQuery(
            "all", LocalDate.parse("2026-01-01"), null,
            "all", "all", "", 0);
        assertThat(fromOnly.to()).isEqualTo(LocalDate.parse("2027-01-02"));
        HistoryQuery toOnly = new HistoryQuery(
            "all", null, LocalDate.parse("2026-12-31"),
            "all", "all", "", 0);
        assertThat(toOnly.from()).isEqualTo(LocalDate.parse("2025-12-30"));
        assertThatThrownBy(() -> new HistoryQuery(
            "all", LocalDate.parse("2024-01-01"), LocalDate.parse("2026-01-01"),
            "all", "all", "", 0))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void ACTN_10_historyDtoAndTemplatesExcludeAuthorityAndSecretFields()
            throws Exception {
        assertThat(java.util.Arrays.stream(ActionHistoryRow.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName))
            .doesNotContain(
                "providerMessageId", "messageIds", "scopeId", "scopeHash",
                "targetFingerprint", "unsubscribeUri", "token", "leaseFingerprint",
                "idempotencyFingerprint", "internalActionId");
        String html = Files.readString(Path.of(
            "src/main/resources/templates/fragments/review-history-frag.html"));
        assertThat(html)
            .contains("No automatic undo", "noopener noreferrer",
                "opens in a new tab", "Legacy action - per-target outcome detail is unavailable.",
                "value=\"review\"", "value=\"in_progress\"", "value=\"undone\"",
                "#action-${row.compensatesActionPublicId}")
            .doesNotContain("href=\"#\"", "responseBody", "exceptionText",
                "scopeId", "providerMessageId", "Export CSV", "<canvas");

    }

    @Test
    void ACTN_07_privacyAllowlistCoversNewLedgerModelsSchemaTemplatesAndUrls()
            throws Exception {
        String productionV9 = Files.readString(Path.of(
            "src/main/resources/db/migration/V9__action_execution_history_and_undo.sql"));
        assertThat(productionV9.toLowerCase())
            .doesNotContain(
                "provider_message_id", "provider_thread_id", "unsubscribe_uri",
                "raw_header", "authorization_header", "cookie", "referrer",
                "response_body", "response_html", "exception_text", "dns_address",
                "csrf_token", "idempotency_token", "lease_token");

        String renderedSources = Files.readString(Path.of(
            "src/main/resources/templates/review.html"))
            + Files.readString(Path.of(
                "src/main/resources/templates/fragments/review-history-frag.html"));
        assertThat(renderedSources)
            .doesNotContain(
                "providerMessageId", "providerThreadId", "scopeId", "scopeHash",
                "targetFingerprint", "unsubscribeUri", "leaseFingerprint",
                "idempotencyFingerprint", "csrfToken", "href=\"#\"",
                "Export CSV", "<canvas");
    }

    @Test
    void ACTN_04_accessibilityAndResponsiveContractsAreBuildFailing()
            throws Exception {
        String page = Files.readString(Path.of(
            "src/main/resources/templates/review.html"));
        String history = Files.readString(Path.of(
            "src/main/resources/templates/fragments/review-history-frag.html"));
        String css = Files.readString(Path.of(
            "src/main/resources/static/css/workspace.css"));

        assertThat(page)
            .contains(
                "<a class=\"skip-link\"", "href=\"#workspace-main\"",
                "<main id=\"workspace-main\">", ">Action history</h1>");
        assertThat(history)
            .contains(
                "<caption>", "scope=\"col\"", "tabindex=\"0\"")
            .doesNotContain("href=\"#\"", ">Delete<");
        assertThat(history)
            .contains(
                "target=\"_blank\"", "rel=\"noopener noreferrer\"",
                "opens in a new tab");
        assertThat(css)
            .contains(
                "@media (max-width: 980px)", "@media (max-width: 720px)",
                "@media (max-width: 375px)",
                "@media (prefers-reduced-motion: reduce)",
                "min-height: 44px", "outline: 2px solid",
                "overflow-x: auto", "overscroll-behavior-inline: contain",
                "overflow-wrap: anywhere", "word-break: break-word")
            .doesNotContain("overflow-x: hidden");
    }
}
