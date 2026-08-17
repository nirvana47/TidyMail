package com.tidymail.service;

import com.tidymail.service.UnsubscribeCandidateService.ExecutionCandidate;
import com.tidymail.service.UnsubscribeExecutionPreparationService.PreparedExecution;
import com.tidymail.unsubscribe.DestinationResolver;
import com.tidymail.unsubscribe.UnsubscribeHttpTransport;
import com.tidymail.unsubscribe.UnsubscribeHttpTransport.FailureReason;
import com.tidymail.unsubscribe.UnsubscribeMethod;
import com.tidymail.unsubscribe.UnsubscribeReasonCode;
import com.tidymail.unsubscribe.ValidatedUnsubscribeRequest;
import java.util.Collection;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Executes automated unsubscribe requests (RFC 8058 POST or legacy HTTPS GET):
 * prepares the attempt, resolves and validates the destination, dispatches via
 * the transport, and records the outcome.
 *
 * <p>Product functionality supported: the <em>Unsubscribe</em> flow's automated
 * execution and manual completion.
 */
@Service
public class UnsubscribeExecutionService {
    private final UnsubscribeExecutionPreparationService preparation;
    private final UnsubscribeOutcomeService outcomes;
    private final DestinationResolver resolver;
    private final UnsubscribeHttpTransport transport;

    public UnsubscribeExecutionService(
            UnsubscribeExecutionPreparationService preparation,
            UnsubscribeOutcomeService outcomes,
            DestinationResolver resolver,
            UnsubscribeHttpTransport transport) {
        this.preparation = preparation;
        this.outcomes = outcomes;
        this.resolver = resolver;
        this.transport = transport;
    }

    /** Executes an automated unsubscribe request and records its outcome. */
    public ExecutionResult execute(
            Collection<Long> authorizedAccountIds,
            String candidateKey,
            UnsubscribeMethod method,
            String intent) {
        if (method == null || !method.automated()) {
            throw new ExecutionException(
                HttpStatus.UNPROCESSABLE_ENTITY, UnsubscribeReasonCode.NO_SAFE_METHOD);
        }
        PreparedExecution prepared =
            preparation.prepare(authorizedAccountIds, candidateKey, method, intent);
        ExecutionCandidate candidate = prepared.candidate();
        long attemptId = prepared.attemptId();
        ValidatedUnsubscribeRequest request;
        try {
            var destination = resolver.resolveAndValidate(candidate.destinationHost());
            request = method == UnsubscribeMethod.RFC8058_POST
                ? ValidatedUnsubscribeRequest.oneClickPost(
                    candidate.destination(), destination)
                : ValidatedUnsubscribeRequest.legacyGet(
                    candidate.destination(), destination);
        } catch (RuntimeException blocked) {
            return new ExecutionResult(
                outcomes.failed(candidate.accountId(), attemptId,
                    UnsubscribeReasonCode.DESTINATION_BLOCKED, null),
                HttpStatus.UNPROCESSABLE_ENTITY);
        }

        final UnsubscribeHttpTransport.TransportResult result;
        try {
            result = transport.execute(request);
        } catch (RuntimeException transportFailure) {
            return new ExecutionResult(
                outcomes.failed(candidate.accountId(), attemptId,
                    UnsubscribeReasonCode.TRANSPORT_ERROR, null),
                HttpStatus.BAD_GATEWAY);
        }
        if (result.outcome() == UnsubscribeHttpTransport.Outcome.ACCEPTED) {
            return new ExecutionResult(
                outcomes.accepted(candidate.accountId(), attemptId), HttpStatus.OK);
        }
        UnsubscribeReasonCode reason = map(result.reason());
        HttpStatus responseStatus = reason == UnsubscribeReasonCode.REDIRECT_BLOCKED
            || reason == UnsubscribeReasonCode.DESTINATION_BLOCKED
                ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_GATEWAY;
        return new ExecutionResult(
            outcomes.failed(
                candidate.accountId(), attemptId, reason, result.httpStatusClass()),
            responseStatus);
    }

    /** Records a manual completion for a manual unsubscribe method. */
    public ExecutionResult completeManual(
            Collection<Long> authorizedAccountIds,
            String candidateKey,
            UnsubscribeMethod method,
            String intent,
            boolean userConfirmed) {
        if (method == null || !method.manual() || method == UnsubscribeMethod.MANUAL) {
            throw new ExecutionException(
                HttpStatus.UNPROCESSABLE_ENTITY, UnsubscribeReasonCode.NO_SAFE_METHOD);
        }
        return new ExecutionResult(
            preparation.completeManual(
                authorizedAccountIds, candidateKey, method, intent, userConfirmed),
            HttpStatus.OK);
    }

    /** Maps a transport failure reason to an unsubscribe reason code. */
    private static UnsubscribeReasonCode map(FailureReason reason) {
        return switch (reason) {
            case REDIRECT_BLOCKED -> UnsubscribeReasonCode.REDIRECT_BLOCKED;
            case TIMEOUT_UNKNOWN_REMOTE_RESULT ->
                UnsubscribeReasonCode.TIMEOUT_UNKNOWN_REMOTE_RESULT;
            case REMOTE_REJECTED, RESPONSE_TOO_LARGE ->
                UnsubscribeReasonCode.NON_SUCCESS_RESPONSE;
            case TLS_FAILURE, IO_FAILURE -> UnsubscribeReasonCode.TRANSPORT_ERROR;
            case NONE -> UnsubscribeReasonCode.TRANSPORT_ERROR;
        };
    }

    /** The outcome and HTTP status of an unsubscribe execution. */
    public record ExecutionResult(
        UnsubscribeOutcomeService.OutcomeView outcome,
        HttpStatus status
    ) {
    }

    /** Thrown when an unsubscribe request is blocked. */
    public static final class ExecutionException extends RuntimeException {
        private final HttpStatus status;
        private final UnsubscribeReasonCode reason;

        public ExecutionException(HttpStatus status, UnsubscribeReasonCode reason) {
            super("Unsubscribe request was blocked");
            this.status = status;
            this.reason = reason;
        }

        public HttpStatus status() { return status; }
        public UnsubscribeReasonCode reason() { return reason; }
    }
}
