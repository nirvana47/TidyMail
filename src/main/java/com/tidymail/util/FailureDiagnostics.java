package com.tidymail.util;

public final class FailureDiagnostics {
    private FailureDiagnostics() {
    }

    public static String gmailReason(com.google.api.client.googleapis.json.GoogleJsonError error) {
        if (error == null || error.getErrors() == null) return "unspecified";
        var allowed = java.util.Set.of("rateLimitExceeded", "userRateLimitExceeded", "dailyLimitExceeded",
            "quotaExceeded", "accessNotConfigured", "forbidden", "insufficientPermissions", "authError",
            "backendError", "internalError", "notFound", "invalidArgument");
        return error.getErrors().stream().filter(java.util.Objects::nonNull)
            .map(com.google.api.client.googleapis.json.GoogleJsonError.ErrorInfo::getReason)
            .filter(java.util.Objects::nonNull).filter(allowed::contains).distinct().sorted().collect(java.util.stream.Collectors.collectingAndThen(
                java.util.stream.Collectors.joining(","), value -> value.isEmpty() ? "unspecified" : value));
    }

    /**
     * Logs locations and status codes, never provider payloads or exception text.
     */
    public static String failureDiagnostic(Throwable failure) {
        StringBuilder diagnostic = new StringBuilder();
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            if (depth > 0)
                diagnostic.append(" causedBy=");
            diagnostic.append(failure.getClass().getSimpleName());
            if (failure instanceof com.google.api.client.http.HttpResponseException response) {
                diagnostic.append(" status=").append(response.getStatusCode());
            }
            if (failure instanceof com.google.api.client.googleapis.json.GoogleJsonResponseException google) {
                diagnostic.append(" reason=").append(gmailReason(google.getDetails()));
            }
            if (failure instanceof com.tidymail.mail.gmail.GmailSyncReadException metadata) {
                diagnostic.append(" status=").append(metadata.status()).append(" reason=").append(metadata.reason());
            }
            if (failure instanceof java.sql.SQLException sql) {
                diagnostic.append(" sqlState=").append(sql.getSQLState());
            }
            if (failure instanceof com.tidymail.mail.CleanupProviderException provider) {
                diagnostic.append(" status=").append(provider.status())
                        .append(" retryableRejection=").append(provider.retryableRejection());
            }
            if (failure instanceof org.springframework.web.client.RestClientResponseException response) {
                diagnostic.append(" status=").append(response.getStatusCode().value());
            }
            if (failure instanceof org.springframework.security.oauth2.core.OAuth2AuthorizationException oauth) {
                String code = oauth.getError().getErrorCode();
                if (java.util.Set.of("invalid_grant", "invalid_client", "unauthorized_client", "invalid_token",
                        "invalid_token_response", "temporarily_unavailable", "server_error").contains(code))
                    diagnostic.append(" oauthCode=").append(code);
            }
            java.util.Arrays.stream(failure.getStackTrace())
                    .filter(frame -> frame.getClassName().startsWith("com.tidymail."))
                    .limit(4).forEach(frame -> diagnostic.append(" at ").append(frame));
        }
        return diagnostic.toString();
    }

}
