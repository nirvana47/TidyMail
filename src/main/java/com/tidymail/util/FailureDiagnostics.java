package com.tidymail.util;

public final class FailureDiagnostics {
    private FailureDiagnostics() {
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
