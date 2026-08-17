package com.tidymail.service;

import com.tidymail.dto.ActionHistoryModel;
import com.tidymail.dto.ActionHistoryRow;
import com.tidymail.dto.ActionOutcomeSummary;
import com.tidymail.dto.HistoryQuery;
import com.tidymail.dto.WorkspacePageModel;
import com.tidymail.repository.ActionHistoryRepository;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Builds the action-history view model for the Review page, aggregating past
 * cleanup actions, their outcomes, events, undo eligibility, and pagination.
 *
 * <p>Product functionality supported: the <em>Review</em> flow's history tab,
 * which shows what was done and whether it can be undone.
 */
@Service
public class ActionHistoryService {
    private static final DateTimeFormatter PRIMARY_TIME =
        DateTimeFormatter.ofPattern("MMM d, HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DETAIL_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private final ActionHistoryRepository history;
    private final ProviderDeepLinkService providerLinks;
    private final Clock clock = Clock.systemUTC();

    public ActionHistoryService(
            ActionHistoryRepository history,
            ProviderDeepLinkService providerLinks) {
        this.history = history;
        this.providerLinks = providerLinks;
    }

    /** Builds the history model for the given workspace and query. */
    public ActionHistoryModel history(
            WorkspacePageModel workspace, HistoryQuery query) {
        List<Long> accountIds = workspace.selectedAccountIds();
        long total = history.count(accountIds, query);
        int totalPages = total == 0 ? 0
            : (int) ((total + HistoryQuery.PAGE_SIZE - 1) / HistoryQuery.PAGE_SIZE);
        if (query.page() > 0 && query.page() >= totalPages) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "invalid_history_page");
        }
        List<ActionHistoryRepository.HistoryRecord> records =
            history.page(accountIds, query);
        Map<Long, List<ActionHistoryRepository.EventRecord>> events = history.events(
            records.stream().map(
                ActionHistoryRepository.HistoryRecord::actionId).toList());
        Map<Long, Map<String, Integer>> reasons = history.reasons(
            records.stream().map(ActionHistoryRepository.HistoryRecord::executionId)
                .filter(java.util.Objects::nonNull).toList());
        List<ActionHistoryRow> rows = records.stream()
            .map(record -> row(record, events.getOrDefault(record.actionId(), List.of()),
                record.executionId() == null ? Map.of()
                    : reasons.getOrDefault(record.executionId(), Map.of())))
            .toList();
        Map<String, String> canonical = query.canonicalParameters();
        return new ActionHistoryModel(
            query, history.metrics(accountIds, query), rows, total, totalPages,
            canonical, url(canonical),
            url(Map.of("view", "history", "account", query.account())),
            query.page() == 0 ? "" : url(withPage(query, query.page() - 1)),
            query.page() + 1 >= totalPages ? ""
                : url(withPage(query, query.page() + 1)));
    }

    /** Maps a history record and its events/reasons to a display row. */
    private ActionHistoryRow row(
            ActionHistoryRepository.HistoryRecord record,
            List<ActionHistoryRepository.EventRecord> eventRecords,
            Map<String, Integer> rawReasons) {
        boolean legacy = "LEGACY".equals(record.source())
            || record.executionId() == null;
        ActionOutcomeSummary outcome = legacy ? null : new ActionOutcomeSummary(
            value(record.requested()), value(record.accepted()),
            value(record.succeeded()), value(record.failed()), value(record.pending()),
            value(record.notDispatched()), record.status(), record.source(),
            safeReasons(rawReasons),
            eventRecords.stream().map(event -> new ActionOutcomeSummary.Event(
                visible(event.type()), visible(event.status()), event.occurredAt()))
                .toList());
        boolean reversible = List.of("ARCHIVE", "TRASH", "MARK_READ")
            .contains(record.action());
        boolean undoEligible = reversible && record.appliedCount() > 0
            && record.undoCount() == 0 && record.undoEligibleUntil() != null
            && clock.instant().isBefore(record.undoEligibleUntil())
            && List.of("APPLIED", "PARTIAL").contains(record.status());
        String unavailable = undoEligible ? null
            : !reversible ? "This action has no mechanical undo."
            : legacy ? "Undo unavailable - applied target detail was not recorded."
            : record.appliedCount() == 0
                ? "Undo unavailable - no applied targets were recorded."
                : record.undoCount() > 0
                    ? "Undo unavailable - this action was already used for undo."
                    : "Undo unavailable - the eligibility window expired.";
        String external = "UNSUBSCRIBE".equals(record.action())
            ? providerLinks.accountPage(record.provider(), record.accountEmail())
                .orElse(null)
            : null;
        String countLabel = "%d requested / %d applied".formatted(
            value(record.requested()), record.appliedCount());
        String availability = availability(
            undoEligible, record.action(), record.compensatesActionPublicId(),
            external, unavailable);
        return new ActionHistoryRow(
            record.actionPublicId(), accountLabel(
                record.accountEmail(), record.provider()), record.targetLabel(),
            visible(record.action()), visible(record.mode()), visible(record.status()),
            record.occurredAt(), PRIMARY_TIME.format(record.occurredAt()),
            DETAIL_TIME.format(record.occurredAt()), countLabel, availability,
            outcome, legacy, undoEligible,
            record.appliedCount(), unavailable,
            "UNSUBSCRIBE".equals(record.action()),
            record.unsubscribeOutcome() == null
                ? null : visible(record.unsubscribeOutcome()),
            external,
            "PROTECT".equals(record.action()), null,
            record.compensatesActionPublicId());
    }

    /** Summarizes the follow-up availability for a history row. */
    private static String availability(
            boolean undoEligible,
            String action,
            java.util.UUID compensatesActionPublicId,
            String external,
            String unavailable) {
        if (undoEligible) return "Undo available";
        if ("UNSUBSCRIBE".equals(action)) {
            return external == null ? "Manual follow-up" : "Provider options";
        }
        if ("PROTECT".equals(action)) return "Protection follow-up";
        if (compensatesActionPublicId != null) return "Compensation linked";
        return unavailable == null ? "No follow-up" : "Details available";
    }

    /** Groups and limits raw failure reasons into safe display labels. */
    private static Map<String, Integer> safeReasons(Map<String, Integer> raw) {
        Map<String, Integer> grouped = new LinkedHashMap<>();
        raw.forEach((code, count) -> grouped.merge(reason(code), count, Integer::sum));
        return grouped.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()))
            .limit(ActionOutcomeSummary.MAX_REASONS)
            .collect(Collectors.collectingAndThen(Collectors.toMap(
                Map.Entry::getKey, Map.Entry::getValue,
                Integer::sum, LinkedHashMap::new), Collections::unmodifiableMap));
    }

    /** Maps a failure reason code to a human-readable label. */
    private static String reason(String code) {
        return switch (code) {
            case "PROVIDER_REJECTED" -> "Provider rejected the request";
            case "RESULT_UNKNOWN_AFTER_DISPATCH", "LEASE_EXPIRED" ->
                "Result needs verification";
            case "FRESH_CONSENT_REQUIRED" -> "Fresh confirmation required";
            case "STALE_ACTION" -> "Action facts changed";
            default -> "Request could not be completed";
        };
    }

    /** Formats an enum-style value as a human-readable label. */
    private static String visible(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    /** Formats an account label with its provider name. */
    private static String accountLabel(String email, String providerValue) {
        String provider = switch (providerValue) {
            case "google" -> "Google";
            case "microsoft" -> "Microsoft Outlook";
            default -> visible(providerValue);
        };
        return email + " (" + provider + ")";
    }

    /** Returns the integer value, defaulting to zero when null. */
    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    /** Returns the query parameters with the given page applied. */
    private static Map<String, String> withPage(HistoryQuery query, int page) {
        LinkedHashMap<String, String> values =
            new LinkedHashMap<>(query.withoutPage());
        if (page > 0) values.put("page", String.valueOf(page));
        return values;
    }

    /** Builds a /review URL from the given query parameters. */
    private static String url(Map<String, String> parameters) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/review");
        parameters.forEach(builder::queryParam);
        return builder.build().encode().toUriString();
    }
}
