package com.tidymail.service;

import com.tidymail.entity.UnsubscribeAttempt;
import com.tidymail.entity.UnsubscribeOutcomeEvent;
import com.tidymail.repository.UnsubscribeAttemptRepository;
import com.tidymail.repository.UnsubscribeOutcomeEventRepository;
import com.tidymail.unsubscribe.UnsubscribeMethod;
import com.tidymail.unsubscribe.UnsubscribeOutcomeStatus;
import com.tidymail.unsubscribe.UnsubscribeReasonCode;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Reads retained unsubscribe outcomes and their event timelines without dispatch or mutation. */
@Service
public class UnsubscribeOutcomeService {
    private final UnsubscribeAttemptRepository attempts;
    private final UnsubscribeOutcomeEventRepository events;
    public UnsubscribeOutcomeService(UnsubscribeAttemptRepository attempts, UnsubscribeOutcomeEventRepository events) {
        this.attempts = attempts;
        this.events = events;
    }

    /** Returns the outcome view for an attempt. */
    @Transactional(readOnly = true)
    public OutcomeView view(long accountId, long attemptId) {
        UnsubscribeAttempt attempt = attempts.findByIdAndAccountId(attemptId, accountId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Outcome not found"));
        List<OutcomeEventView> timeline = events
            .findByAttemptIdAndAccountIdOrderByOccurredAtAscIdAsc(attemptId, accountId)
            .stream().map(UnsubscribeOutcomeService::eventView).toList();
        return new OutcomeView(
            attempt.getId(), attempt.getMethod(), attempt.getDestinationHost(),
            attempt.getLatestStatus(), timeline);
    }

    /** Returns the outcome view for an attempt owned by the user. */
    @Transactional(readOnly = true)
    public OutcomeView viewOwned(Collection<Long> authorizedAccountIds, long attemptId) {
        if (authorizedAccountIds == null || authorizedAccountIds.isEmpty() || attemptId <= 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Outcome not found");
        }
        return authorizedAccountIds.stream()
            .map(accountId -> attempts.findByIdAndAccountId(attemptId, accountId))
            .flatMap(java.util.Optional::stream)
            .findFirst()
            .map(attempt -> view(attempt.getAccountId(), attemptId))
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Outcome not found"));
    }

    /** Maps an outcome event to its display view. */
    private static OutcomeEventView eventView(UnsubscribeOutcomeEvent event) {
        String label = switch (event.getStatus()) {
            case PENDING -> "Pending";
            case ACCEPTED -> "Accepted";
            case CONFIRMED -> "Confirmed";
            case FAILED -> "Failed";
            case MANUAL_REQUIRED -> "Manual required";
            case VERIFICATION_PENDING -> "Verification pending";
        };
        String copy = switch (event.getStatus()) {
            case PENDING -> "The request was recorded before any destination contact.";
            case ACCEPTED ->
                "The endpoint accepted the request. This does not prove future mail has stopped.";
            case CONFIRMED -> "Confirmed by you.";
            case FAILED -> failureCopy(event.getReasonCode());
            case MANUAL_REQUIRED ->
                "This method required your explicit action in another app or page.";
            case VERIFICATION_PENDING ->
                "Completion has not been independently verified.";
        };
        String source = event.getConfirmationSource() == null ? null
            : switch (event.getConfirmationSource()) {
                case ENDPOINT_ACCEPTED -> "Endpoint accepted";
                case USER_CONFIRMED -> "Confirmed by you";
                case PROVIDER_CONFIRMED -> "Provider confirmed";
            };
        return new OutcomeEventView(
            event.getStatus(), label, copy, event.getOccurredAt(),
            event.getReasonCode(), event.getHttpStatusClass(), source);
    }

    /** Returns the human-readable copy for a failure reason. */
    private static String failureCopy(UnsubscribeReasonCode reason) {
        return switch (reason) {
            case REDIRECT_BLOCKED ->
                "The destination returned a redirect, so no follow-up request was made.";
            case TIMEOUT_UNKNOWN_REMOTE_RESULT ->
                "The request timed out and may have reached the destination. It was not retried.";
            case DESTINATION_BLOCKED ->
                "The destination did not pass the current network safety check.";
            case NON_SUCCESS_RESPONSE ->
                "The destination did not accept the request.";
            default ->
                "The request could not be completed.";
        };
    }

    /** The outcome view for an unsubscribe attempt. */
    public record OutcomeView(
        long attemptId,
        UnsubscribeMethod method,
        String destinationHost,
        UnsubscribeOutcomeStatus latestStatus,
        List<OutcomeEventView> events
    ) {
        public OutcomeView {
            events = List.copyOf(events);
        }
    }

    /** A single outcome event in the timeline. */
    public record OutcomeEventView(
        UnsubscribeOutcomeStatus status,
        String label,
        String copy,
        Instant occurredAt,
        UnsubscribeReasonCode reason,
        Short httpStatusClass,
        String confirmationSource
    ) {
    }
}
