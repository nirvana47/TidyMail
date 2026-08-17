package com.tidymail.dto;

import java.util.List;

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

    public record AccountGroup(String label, List<ReviewActionRow> rows) {
        public AccountGroup {
            rows = List.copyOf(rows);
        }
    }
}
