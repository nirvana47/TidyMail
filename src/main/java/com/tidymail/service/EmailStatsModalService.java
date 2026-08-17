package com.tidymail.service;

import com.tidymail.dto.EmailStatsModal;
import com.tidymail.repository.ReviewActionQueryRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Builds the email-stats modal for a review action, listing the affected emails
 * (or sender history for unsubscribes), their time distribution, and the actions
 * available for that action.
 *
 * <p>Product functionality supported: the <em>Review</em> flow's email-stats
 * modal and its execute/undo entry points.
 */
@Service
public class EmailStatsModalService {
    private static final int MAX_BUCKETS = 6;
    private final ReviewActionQueryRepository repository;

    public EmailStatsModalService(ReviewActionQueryRepository repository) {
        this.repository = repository;
    }

    /** Builds the email-stats modal for the given review action. */
    public EmailStatsModal review(
            List<Long> accountIds, String accountQuery, UUID actionPublicId) {
        var source = repository.emailStatsSource(accountIds, actionPublicId);
        boolean senderHistory = "UNSUBSCRIBE".equals(source.actionType());
        List<EmailStatsModal.EmailRow> emails = senderHistory
            ? repository.senderHistory(source)
            : repository.affectedEmails(source);
        String sourceLabel =
            ReviewActionQueryRepository.sourceLabel(source.decisionSource());
        return new EmailStatsModal(
            source.actionPublicId(),
            "Emails for " + source.targetLabel(),
            source.targetLabel(),
            sourceLabel,
            senderHistory ? "Sender history" : "Affected emails",
            emails.size(),
            accountQuery,
            "/review?view=queued&account=" + accountQuery,
            actions(source, accountQuery),
            buckets(emails),
            emails);
    }

    /** Determines the available top actions for the action's state. */
    private static List<EmailStatsModal.TopAction> actions(
            ReviewActionQueryRepository.EmailStatsSource source,
            String accountQuery) {
        String base = "/review/actions/" + source.actionPublicId();
        ArrayList<EmailStatsModal.TopAction> result = new ArrayList<>();
        if ("UNSUBSCRIBE".equals(source.actionType())) {
            result.add(new EmailStatsModal.TopAction(
                "Execute", "/review/unsubscribe/" + source.actionPublicId()
                    + "/confirm?account=" + accountQuery, "primary", "post"));
        } else if ("QUEUED".equals(source.status())
                && !"REVIEW".equals(source.actionType())) {
            result.add(new EmailStatsModal.TopAction(
                "Execute", base + "/execute/confirm?account=" + accountQuery,
                "primary", "post"));
        }
        if (List.of("QUEUED", "BLOCKED").contains(source.status())) {
            result.add(new EmailStatsModal.TopAction(
                "Undo", base + "/undo-staged/confirm?account=" + accountQuery,
                "warning", "post"));
        }
        return List.copyOf(result);
    }

    /** Groups the emails' received times into a bounded time distribution. */
    static List<EmailStatsModal.DistributionBucket> buckets(
            List<EmailStatsModal.EmailRow> emails) {
        List<Instant> received = emails.stream()
            .map(EmailStatsModal.EmailRow::receivedAt)
            .filter(value -> value != null).sorted().toList();
        if (received.isEmpty()) return List.of();

        Instant first = received.getFirst().truncatedTo(ChronoUnit.DAYS);
        Instant end = received.getLast().atZone(ZoneOffset.UTC)
            .toLocalDate().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        long days = Math.max(1, Duration.between(first, end).toDays());
        long widthDays = Math.max(1, (days + MAX_BUCKETS - 1) / MAX_BUCKETS);
        ArrayList<EmailStatsModal.DistributionBucket> buckets = new ArrayList<>();
        Instant cursor = first;
        while (cursor.isBefore(end)) {
            Instant next = cursor.plus(widthDays, ChronoUnit.DAYS);
            if (next.isAfter(end)) next = end;
            Instant start = cursor;
            Instant finish = next;
            long count = received.stream()
                .filter(value -> !value.isBefore(start) && value.isBefore(finish))
                .count();
            buckets.add(new EmailStatsModal.DistributionBucket(
                start.atZone(ZoneOffset.UTC).toLocalDate() + " to "
                    + finish.minus(1, ChronoUnit.NANOS)
                        .atZone(ZoneOffset.UTC).toLocalDate(),
                start, finish, count));
            cursor = next;
        }
        return List.copyOf(buckets);
    }
}
