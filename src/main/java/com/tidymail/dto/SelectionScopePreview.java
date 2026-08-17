package com.tidymail.dto;

import java.util.List;

public record SelectionScopePreview(
    State state,
    List<SelectionScopeDigest> scopes,
    long targetCount,
    long messageCount,
    long protectedSenderCount,
    long protectedMessageCount,
    long highRiskCount,
    long excludedProtectedSenderCount,
    long excludedProtectedMessageCount,
    String capAccountLabel,
    long capMessageCount,
    String errorMessage,
    String confirmationIntent
) {
    public SelectionScopePreview {
        if (state == null) throw new IllegalArgumentException("Preview state is required");
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        if (targetCount < 0 || messageCount < 0 || protectedSenderCount < 0
                || protectedMessageCount < 0 || highRiskCount < 0
                || excludedProtectedSenderCount < 0 || excludedProtectedMessageCount < 0
                || capMessageCount < 0) {
            throw new IllegalArgumentException("Preview counts must be non-negative");
        }
        if (state == State.READY && (scopes.isEmpty() || messageCount == 0)) {
            throw new IllegalArgumentException("Ready previews require exact scopes");
        }
        if (state == State.READY
                && (confirmationIntent == null || confirmationIntent.isBlank())) {
            throw new IllegalArgumentException("Ready previews require confirmation");
        }
        if (state != State.READY && !scopes.isEmpty()) {
            throw new IllegalArgumentException("Non-ready previews cannot expose scopes");
        }
        if (state != State.READY && confirmationIntent != null) {
            throw new IllegalArgumentException(
                "Non-ready previews cannot expose confirmation");
        }
    }

    @Override
    public List<SelectionScopeDigest> scopes() {
        return List.copyOf(scopes);
    }

    public enum State {
        READY,
        EMPTY,
        PROTECTED_BLOCKED,
        CAP_EXCEEDED,
        INVALID
    }
}
