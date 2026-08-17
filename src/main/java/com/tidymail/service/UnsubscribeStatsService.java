package com.tidymail.service;

import com.tidymail.dto.EmailStatsModal;
import com.tidymail.dto.UnsubscribeQuery;
import com.tidymail.repository.UnsubscribeCandidateRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Builds the email-stats modal for an unsubscribe candidate sender: email count,
 * time distribution, a preview of recent emails, and bulk cleanup actions.
 *
 * <p>Product functionality supported: the <em>Unsubscribe</em> flow's sender
 * stats modal and cleanup entry points.
 */
@Service
public class UnsubscribeStatsService {
    private static final int MAX_DISTRIBUTION_BUCKETS = 14;
    private static final int EMAIL_PREVIEW_LIMIT = 25;
    private final UnsubscribeCandidateRepository repository;

    public UnsubscribeStatsService(UnsubscribeCandidateRepository repository) {
        this.repository = repository;
    }

    /** Builds the email-stats modal for the sender under the query. */
    public EmailStatsModal model(
            Collection<Long> authorizedAccountIds,
            UnsubscribeQuery query,
            String senderRowKey) {
        DecisionRowKey.Locator locator =
            DecisionRowKey.decode(com.tidymail.dto.SelectionSurface.SENDERS, senderRowKey);
        if (!authorizedAccountIds.contains(locator.accountId())) {
            throw new IllegalArgumentException("Sender is outside the authorized scope");
        }
        long emailCount = repository.countSenderEmails(
            locator.accountId(), locator.target(),
            query.startInclusive(), query.endExclusive());
        List<UnsubscribeCandidateRepository.SenderEmailBucket> buckets =
            bucketDefinitions(query);
        List<EmailStatsModal.DistributionBucket> distribution =
            repository.countSenderEmailBuckets(
                    locator.accountId(), locator.target(), buckets).stream()
                .map(bucket -> new EmailStatsModal.DistributionBucket(
                    bucket.label(),
                    bucket.startInclusive(),
                    bucket.endExclusive(),
                    bucket.count()))
                .toList();
        List<UnsubscribeCandidateRepository.SenderEmailFact> facts =
            repository.findSenderEmails(
                locator.accountId(), locator.target(),
                query.startInclusive(), query.endExclusive(), EMAIL_PREVIEW_LIMIT);
        List<EmailStatsModal.EmailRow> emails = facts.stream()
            .map(fact -> new EmailStatsModal.EmailRow(
                fact.sender(),
                fact.subject(),
                fact.receivedAt(),
                fact.unread(),
                fact.attachmentState()))
            .toList();
        List<EmailStatsModal.TopAction> actions =
            actions(query, senderRowKey);
        String senderLabel = facts.isEmpty() ? locator.target() : facts.getFirst().sender();
        return new EmailStatsModal(
            UUID.nameUUIDFromBytes(
                ("unsubscribe-stats:" + senderRowKey + ":" + query.from() + ":" + query.to())
                    .getBytes(StandardCharsets.UTF_8)),
            "Stats for " + senderLabel,
            senderLabel,
            "Unsubscribe",
            "Sender stats",
            emailCount,
            query.account(),
            pageUrl(query),
            actions,
            distribution,
            emails);
    }

    /** Builds the archive/delete cleanup actions for the sender. */
    private static List<EmailStatsModal.TopAction> actions(
            UnsubscribeQuery query,
            String senderRowKey) {
        ArrayList<EmailStatsModal.TopAction> result = new ArrayList<>(2);
        result.add(cleanupAction(
            query, senderRowKey, UnsubscribeQuery.DefaultAction.ARCHIVE));
        result.add(cleanupAction(
            query, senderRowKey, UnsubscribeQuery.DefaultAction.DELETE));
        return List.copyOf(result);
    }

    /** Builds a single cleanup action URL. */
    private static EmailStatsModal.TopAction cleanupAction(
            UnsubscribeQuery query,
            String senderRowKey,
            UnsubscribeQuery.DefaultAction action) {
        UriComponentsBuilder url =
            UriComponentsBuilder.fromPath("/unsubscribe/sender-action-preview");
        query.canonicalParameters().forEach(url::queryParam);
        url.queryParam("rowKey", senderRowKey);
        url.queryParam("action", action == UnsubscribeQuery.DefaultAction.ARCHIVE
            ? "ARCHIVE" : "TRASH");
        return new EmailStatsModal.TopAction(
            action.label() + " all",
            url.build().encode().toUriString(),
            action == UnsubscribeQuery.DefaultAction.DELETE ? "warning" : "neutral",
            "post");
    }

    /** Builds the unsubscribe page URL for the query. */
    private static String pageUrl(UnsubscribeQuery query) {
        UriComponentsBuilder url = UriComponentsBuilder.fromPath("/unsubscribe");
        query.canonicalParameters().forEach(url::queryParam);
        return url.build().encode().toUriString();
    }

    /** Builds the time-distribution bucket definitions for the range. */
    private static List<UnsubscribeCandidateRepository.SenderEmailBucket>
            bucketDefinitions(UnsubscribeQuery query) {
        long selectedDays = java.time.temporal.ChronoUnit.DAYS.between(
            query.from(), query.to()) + 1;
        long width = Math.max(
            1, (selectedDays + MAX_DISTRIBUTION_BUCKETS - 1)
                / MAX_DISTRIBUTION_BUCKETS);
        ArrayList<UnsubscribeCandidateRepository.SenderEmailBucket> result =
            new ArrayList<>();
        LocalDate cursor = query.from();
        while (!cursor.isAfter(query.to())) {
            LocalDate next = cursor.plusDays(width);
            if (next.isAfter(query.to().plusDays(1))) next = query.to().plusDays(1);
            Instant start = cursor.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant end = next.atStartOfDay(ZoneOffset.UTC).toInstant();
            result.add(new UnsubscribeCandidateRepository.SenderEmailBucket(
                width == 1 ? cursor.toString() : cursor + " to " + next.minusDays(1),
                start,
                end));
            cursor = next;
        }
        return List.copyOf(result);
    }

}
