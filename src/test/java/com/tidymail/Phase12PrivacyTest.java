package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tidymail.dto.ActionHistoryRow;
import com.tidymail.dto.ActionOutcomeSummary;
import com.tidymail.dto.HistoryQuery;
import com.tidymail.action.ActionType;
import com.tidymail.entity.ActionCommandToken;
import com.tidymail.entity.ActionConflict;
import com.tidymail.entity.ActionEvent;
import com.tidymail.entity.ActionExecution;
import com.tidymail.entity.ActionExecutionTarget;
import com.tidymail.entity.ActionIntent;
import com.tidymail.service.ActionCompatibility;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

class Phase12PrivacyTest {
    @Test
    void ACTN_09_removeProtectionConflictsWithOpposingAndDuplicateDecisions() {
        assertThat(ActionCompatibility.compatible(
            ActionType.REMOVE_PROTECTION, ActionType.PROTECT)).isFalse();
        assertThat(ActionCompatibility.compatible(
            ActionType.REMOVE_PROTECTION, ActionType.REMOVE_PROTECTION)).isFalse();
    }

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
    void ACTN_07_outcomeArithmeticFailsClosed() {
        assertThatThrownBy(() -> new ActionOutcomeSummary(
            4, 2, 2, 1, 0, 0, "PARTIAL", "SENDERS", Map.of(), List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        ActionOutcomeSummary valid = new ActionOutcomeSummary(
            4, 2, 2, 1, 1, 0, "PARTIAL", "SENDERS",
            Map.of("Provider rejected the request", 1),
            List.of(new ActionOutcomeSummary.Event(
                "Execution completed", "Partial", Instant.EPOCH)));
        assertThat(valid.visibleStatus()).isEqualTo("Partially applied");
        assertThat(new ActionOutcomeSummary(
            2, 2, 2, 0, 0, 0, "UNDONE", "SENDERS",
            Map.of(), List.of()).visibleStatus()).isEqualTo("Undone");
        assertThat(new ActionOutcomeSummary(
            2, 1, 0, 0, 1, 1, "PARTIALLY_UNDONE", "SENDERS",
            Map.of(), List.of()).visibleStatus()).isEqualTo("Partially undone");
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
        assertThat(Files.readString(Path.of(
            "src/main/resources/templates/fragments/undo-confirmation-frag.html")))
            .contains("name=\"account\"", "undoConfirmation.accountId");
        assertThat(Files.readString(Path.of(
            "src/main/resources/templates/fragments/compensation-frag.html")))
            .contains("name=\"account\"", "compensationConfirmation.accountId");
    }

    @Test
    void ACTN_07_privacyAllowlistCoversNewLedgerModelsSchemaTemplatesAndUrls()
            throws Exception {
        List<Class<?>> ledgerTypes = List.of(
            ActionIntent.class, ActionCommandToken.class, ActionExecution.class,
            ActionExecutionTarget.class, ActionEvent.class, ActionConflict.class,
            ActionHistoryRow.class);
        List<String> forbiddenFields = List.of(
            "providerMessageId", "providerThreadId", "messageIds", "scopeHash",
            "unsubscribeUri", "rawHeader", "authorization", "cookie", "referrer",
            "responseBody", "responseHtml", "exceptionText", "dnsAddress",
            "csrfToken", "idempotencyToken", "leaseToken", "arbitraryJson");
        ledgerTypes.forEach(type -> assertThat(
            java.util.Arrays.stream(type.getDeclaredFields())
                .map(java.lang.reflect.Field::getName).toList())
            .as(type.getSimpleName())
            .doesNotContainAnyElementsOf(forbiddenFields));

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
                "src/main/resources/templates/fragments/review-history-frag.html"))
            + Files.readString(Path.of(
                "src/main/resources/templates/fragments/review-queue-frag.html"));
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
        String queue = Files.readString(Path.of(
            "src/main/resources/templates/fragments/review-queue-frag.html"));
        String history = Files.readString(Path.of(
            "src/main/resources/templates/fragments/review-history-frag.html"));
        String outcome = Files.readString(Path.of(
            "src/main/resources/templates/fragments/review-outcome-frag.html"));
        String css = Files.readString(Path.of(
            "src/main/resources/static/css/workspace.css"));

        assertThat(page)
            .contains(
                "<a class=\"skip-link\"", "href=\"#workspace-main\"",
                "<main id=\"workspace-main\">", "<h1>Review</h1>",
                "aria-label=\"Review views\"", "aria-current");
        assertThat(queue + history)
            .contains(
                "<fieldset", "<legend", "<caption>",
                "scope=\"col\"", "aria-live=\"polite\"",
                "tabindex=\"0\"", "type=\"checkbox\"")
            .doesNotContain("href=\"#\"", ">Delete<");
        assertThat(outcome)
            .contains("receipt.failed > 0 ? 'alert' : 'status'",
                "receipt.failed > 0 ? 'assertive' : 'polite'",
                "tabindex=\"-1\"");
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
