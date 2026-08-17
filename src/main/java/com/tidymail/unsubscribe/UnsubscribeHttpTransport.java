package com.tidymail.unsubscribe;

public interface UnsubscribeHttpTransport {
    TransportResult execute(ValidatedUnsubscribeRequest request);

    record TransportResult(Outcome outcome, Integer httpStatusClass, FailureReason reason) {
        public TransportResult {
            if (outcome == null || reason == null
                    || httpStatusClass != null
                        && (httpStatusClass < 2 || httpStatusClass > 5)
                    || outcome == Outcome.ACCEPTED
                        && (httpStatusClass == null || httpStatusClass != 2
                            || reason != FailureReason.NONE)
                    || outcome == Outcome.FAILED && reason == FailureReason.NONE) {
                throw new IllegalArgumentException("Invalid transport result");
            }
        }

        public static TransportResult accepted() {
            return new TransportResult(Outcome.ACCEPTED, 2, FailureReason.NONE);
        }

        public static TransportResult failed(
                Integer statusClass, FailureReason reason) {
            return new TransportResult(Outcome.FAILED, statusClass, reason);
        }
    }

    enum Outcome {
        ACCEPTED,
        FAILED
    }

    enum FailureReason {
        NONE,
        REDIRECT_BLOCKED,
        REMOTE_REJECTED,
        RESPONSE_TOO_LARGE,
        TIMEOUT_UNKNOWN_REMOTE_RESULT,
        TLS_FAILURE,
        IO_FAILURE
    }
}
