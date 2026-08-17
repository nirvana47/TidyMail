package com.tidymail.dto;

import java.util.Arrays;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public enum WorkspaceRoute {
    SENDERS(
        "senders", "/senders", "Bulk Archive/Delete", "Sender cleanup",
        "Review indexed sender metadata and prepare exact cleanup decisions.",
        "Bulk Archive/Delete", 10, "Sender decisions",
        "Filter indexed sender patterns and preview exact cleanup impact. No mailbox changes are applied here.",
        "mailbox execution controls"),
    UNREAD(
        "unread", "/unread", "Unread Only", "Unread cleanup",
        "Prepare indexed unread-message decisions without rescanning the mailbox.",
        "Unread Only", 10, "Unread emails",
        "Review indexed unread metadata, understand each recommendation, and preview exact impact.",
        "mailbox execution controls"),
    UNSUBSCRIBE(
        "unsubscribe", "/unsubscribe", "Bulk Unsubscribe", "Unsubscribe safety",
        "Inspect detected unsubscribe methods and destinations before choosing one.",
        "Bulk Unsubscribe", 11, "Unsubscribe candidates",
        "Browse metadata-derived candidates and inspect redacted destination facts. No request is sent from this page.",
        "later method confirmation is implemented"),
    REVIEW(
        "review", "/review", "Review", "Approval and action history",
        "Approve staged exact scopes, resolve conflicts, and verify what happened.",
        "Review", 12, "Review actions",
        "Approve selected or all eligible exact scopes through one outcome ledger.",
        "history and undo controls"),
    ACTIVITY(
        "activity", "/activity", "Sync / Activity", "Mailbox status",
        "Inspect factual mailbox indexing and provider freshness activity.",
        "Sync / Activity", 19, "Mailbox sync activity",
        "Inspect indexing readiness, freshness, and bounded recent sync activity.",
        "provider-backed mailbox status"),
    ANALYTICS(
        "analytics", "/analytics", "Analytics", "Mailbox cleanup",
        "Inspect received evidence, UTC trends, comparisons, and source rows.",
        "Analytics", 13, "Cleanup analytics",
        "Inspect owned received facts with explicit historical availability.",
        "date controls, comparisons, charts, and drilldowns");

    private final String value;
    private final String path;
    private final String title;
    private final String eyebrow;
    private final String purpose;
    private final String railTitle;
    private final int capabilityPhase;
    private final String capabilityHeading;
    private final String capabilityCopy;
    private final String unavailableCapability;

    WorkspaceRoute(
            String value,
            String path,
            String title,
            String eyebrow,
            String purpose,
            String railTitle,
            int capabilityPhase,
            String capabilityHeading,
            String capabilityCopy,
            String unavailableCapability) {
        this.value = value;
        this.path = path;
        this.title = title;
        this.eyebrow = eyebrow;
        this.purpose = purpose;
        this.railTitle = railTitle;
        this.capabilityPhase = capabilityPhase;
        this.capabilityHeading = capabilityHeading;
        this.capabilityCopy = capabilityCopy;
        this.unavailableCapability = unavailableCapability;
    }

    public String value() { return value; }
    public String path() { return path; }
    public String title() { return title; }
    public String eyebrow() { return eyebrow; }
    public String purpose() { return purpose; }
    public String railTitle() { return railTitle; }
    public int capabilityPhase() { return capabilityPhase; }
    public String capabilityHeading() { return capabilityHeading; }
    public String capabilityCopy() { return capabilityCopy; }
    public String unavailableCapability() { return unavailableCapability; }
    public WorkspaceRoute nextRoute() {
        return switch (this) {
            case SENDERS -> UNREAD;
            case UNREAD -> UNSUBSCRIBE;
            case UNSUBSCRIBE -> REVIEW;
            case REVIEW -> ACTIVITY;
            case ACTIVITY -> ANALYTICS;
            case ANALYTICS -> SENDERS;
        };
    }

    public static WorkspaceRoute parse(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Workspace route is required");
        }
        return Arrays.stream(values())
            .filter(route -> route.value.equalsIgnoreCase(value.trim()))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Unknown workspace route"));
    }
}
