package com.tidymail.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import org.junit.jupiter.api.Test;

class SyncFailureDiagnosticTest {
    @Test
    void reportsAllowlistedGoogleReasonWithoutLeakingProviderText() {
        var details = new com.google.api.client.googleapis.json.GoogleJsonError();
        details.setCode(403);
        details.setMessage("synthetic private message");
        var known = new com.google.api.client.googleapis.json.GoogleJsonError.ErrorInfo();
        known.setReason("userRateLimitExceeded");
        var unknown = new com.google.api.client.googleapis.json.GoogleJsonError.ErrorInfo();
        unknown.setReason("synthetic-private-token");
        details.setErrors(java.util.List.of(known, unknown));
        var failure = new com.google.api.client.googleapis.json.GoogleJsonResponseException(
            new HttpResponseException.Builder(403, "synthetic private message", new HttpHeaders()), details);
        assertThat(com.tidymail.util.FailureDiagnostics.failureDiagnostic(failure))
            .contains("status=403", "reason=userRateLimitExceeded").doesNotContain("private", "token");
    }
    @Test
    void reportsStatusAndLocationsWithoutProviderPayloads() {
        var provider = new HttpResponseException.Builder(403, "private subject", new HttpHeaders())
            .setContent("secret-token private@example.com").build();
        var failure = new IllegalStateException("SECRET_TOKEN", provider);
        String diagnostic = com.tidymail.util.FailureDiagnostics.failureDiagnostic(failure);
        assertThat(diagnostic).contains("IllegalStateException", "HttpResponseException", "status=403")
            .doesNotContain("private", "secret-token", "SECRET_TOKEN");
    }
}
