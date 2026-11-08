package com.tidymail.dto;

import java.util.Arrays;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The top-level workspace routes (Senders, Unread, Action history, Activity),
 * each carrying its display metadata, purpose copy, and capability
 * phase used to render the navigation rail and page headers.
 *
 * <p>Product functionality supported: the workspace navigation and the
 * capability-gated presentation of each cleanup surface.
 */
public enum WorkspaceRoute {
    SENDERS(
        "senders", "/senders", "Senders", "Sender categories",
        "Browse indexed senders and organize their categories.",
        "Senders", 10, "Sender decisions",
        "Browse indexed senders by category.",
        "mailbox execution controls"),
    UNREAD(
        "unread", "/unread", "Unread Only", "Unread cleanup",
        "Browse indexed unread mail without rescanning the mailbox.",
        "Unread Only", 10, "Unread emails",
        "Browse categories of indexed Inbox-unread mail.",
        "mailbox execution controls"),
    REVIEW(
        "review", "/review", "Action history", "Action history",
        "Inspect retained action outcomes.",
        "Action history", 12, "Action history",
        "Inspect recorded outcomes and their timeline.",
        "retained history"),
    ACTIVITY(
        "activity", "/activity", "Sync / Activity", "Mailbox status",
        "Inspect factual mailbox indexing and provider freshness activity.",
        "Sync / Activity", 19, "Mailbox sync activity",
        "Inspect indexing readiness, freshness, and bounded recent sync activity.",
        "provider-backed mailbox status");

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
    /** Returns the next route in the workspace navigation order. */
    public WorkspaceRoute nextRoute() {
        return switch (this) {
            case SENDERS -> UNREAD;
            case UNREAD -> REVIEW;
            case REVIEW -> ACTIVITY;
            case ACTIVITY -> SENDERS;
        };
    }

    /** Parses a route value, rejecting unknown or blank routes. */
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
