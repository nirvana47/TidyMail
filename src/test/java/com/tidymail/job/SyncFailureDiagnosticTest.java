package com.tidymail.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import org.junit.jupiter.api.Test;

class SyncFailureDiagnosticTest {
    @Test
    void reportsStatusAndLocationsWithoutProviderPayloads() {
        var provider = new HttpResponseException.Builder(403, "private subject", new HttpHeaders())
            .setContent("secret-token private@example.com").build();
        var failure = new IllegalStateException("private@example.com", provider);
        String diagnostic = IncrementalSyncJob.failureDiagnostic(failure);
        assertThat(diagnostic).contains("IllegalStateException", "HttpResponseException", "status=403")
            .doesNotContain("private", "secret-token");
    }
}
