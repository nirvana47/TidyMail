package com.tidymail.dto;

import java.util.List;

/**
 * Groups review-queue actions by their {@link Kind} (archive, trash, mark-read,
 * unsubscribe, keep, protect, or needs-decision) for display in the Review page.
 *
 * <p>Product functionality supported: the <em>Review</em> flow, which presents staged
 * exact cleanup scopes grouped by action type so the user can approve them in bulk.
 */
public record ReviewActionGroup(
    Kind kind,
    String label,
    long actionCount,
    long accountCount,
    long targetCount,
    long conflictCount,
    long eligibleCount,
    List<AccountGroup> accounts
) {
    public ReviewActionGroup {
        accounts = List.copyOf(accounts);
    }

    /** The kind of cleanup action a group represents. */
    public enum Kind {
        ARCHIVE("Archive"),
        TRASH("Trash"),
        MARK_READ("Mark read"),
        UNSUBSCRIBE("Unsubscribe"),
        KEEP("Keep"),
        PROTECT("Protect"),
        NEEDS_DECISION("Needs decision");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** A labeled subset of rows within a group, scoped to a single account. */
    public record AccountGroup(String label, List<ReviewActionRow> rows) {
        public AccountGroup {
            rows = List.copyOf(rows);
        }
    }
}
