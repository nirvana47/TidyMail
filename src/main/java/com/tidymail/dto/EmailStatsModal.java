package com.tidymail.dto;

import com.tidymail.mail.AttachmentState;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Shared, provider-free model for Review and Unsubscribe email statistics modals.
 * The reusable Thymeleaf contract is
 * {@code fragments/email-stats-modal-frag :: modal}.
 */
public record EmailStatsModal(
    UUID actionPublicId,
    String title,
    String senderLabel,
    String sourceLabel,
    String scopeLabel,
    long emailCount,
    String accountQuery,
    String closeUrl,
    List<TopAction> topActions,
    List<DistributionBucket> distribution,
    List<EmailRow> emails
) {
    private static final DateTimeFormatter RANGE_DATE =
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);

    public EmailStatsModal {
        if (actionPublicId == null || title == null || senderLabel == null
                || sourceLabel == null || scopeLabel == null || emailCount < 0
                || accountQuery == null || closeUrl == null || closeUrl.isBlank()) {
            throw new IllegalArgumentException("Invalid email stats modal");
        }
        topActions = List.copyOf(topActions);
        distribution = List.copyOf(distribution);
        emails = List.copyOf(emails);
    }

    public String rangeLabel() {
        if (distribution.isEmpty()) return "Selected range unavailable";
        return RANGE_DATE.format(distribution.getFirst().startInclusive())
            + " to "
            + RANGE_DATE.format(distribution.getLast().endExclusive().minusMillis(1));
    }

    public long maxDistributionCount() {
        return distribution.stream()
            .mapToLong(DistributionBucket::count)
            .max()
            .orElse(0);
    }

    public long distributionAxisMax() {
        long max = maxDistributionCount();
        if (max <= 5) return 5;
        double magnitude = Math.pow(10, Math.floor(Math.log10(max)));
        double normalized = max / magnitude;
        double nice = normalized <= 1 ? 1
            : normalized <= 2 ? 2
            : normalized <= 2.5 ? 2.5
            : normalized <= 5 ? 5 : 10;
        return Math.max(1, Math.round(nice * magnitude));
    }

    public List<Long> distributionAxisTicks() {
        long max = distributionAxisMax();
        long step = Math.max(1, max / 5);
        return List.of(max, max - step, max - (2 * step),
            max - (3 * step), max - (4 * step), 0L);
    }

    public boolean showDistributionLabel(int index) {
        if (distribution.isEmpty()) return false;
        return index == 0
            || index == distribution.size() / 2
            || index == distribution.size() - 1;
    }

    public boolean hasUnknownAttachmentState() {
        return emails.stream()
            .anyMatch(email -> email.attachmentState() == AttachmentState.UNKNOWN);
    }

    public record TopAction(String label, String url, String tone, String method) {
        public TopAction {
            if (label == null || label.isBlank() || url == null || url.isBlank()
                    || !List.of("primary", "warning", "neutral").contains(tone)
                    || !List.of("get", "post").contains(method)) {
                throw new IllegalArgumentException("Invalid modal top action");
            }
        }
    }

    public record DistributionBucket(
        String label,
        Instant startInclusive,
        Instant endExclusive,
        long count
    ) {
        private static final DateTimeFormatter CHART_DATE =
            DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
                .withZone(ZoneOffset.UTC);

        public DistributionBucket {
            if (label == null || label.isBlank() || count < 0) {
                throw new IllegalArgumentException("Invalid distribution bucket");
            }
        }

        public int heightPercent(long maxCount) {
            if (count == 0 || maxCount <= 0) return 0;
            return Math.max(8, (int) Math.round(count * 100.0 / maxCount));
        }

        public String chartLabel() {
            return CHART_DATE.format(startInclusive);
        }
    }

    public record EmailRow(
        String sender,
        String subject,
        Instant receivedAt,
        boolean unread,
        AttachmentState attachmentState
    ) {
        public EmailRow {
            sender = sender == null || sender.isBlank() ? "Unknown sender" : sender;
            subject = subject == null || subject.isBlank() ? "(No subject)" : subject;
            if (attachmentState == null) {
                throw new IllegalArgumentException("Attachment state is required");
            }
        }

        public String attachmentLabel() {
            return switch (attachmentState) {
                case PRESENT -> "Present";
                case ABSENT -> "None";
                case UNKNOWN -> "Checking";
            };
        }
    }
}
