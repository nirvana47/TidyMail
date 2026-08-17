package com.tidymail.unsubscribe;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.io.CloseMode;
import org.springframework.stereotype.Component;

/**
 * Executes validated unsubscribe requests over the HTTP layer, building GET or
 * POST calls, enforcing a total timeout, and cancelling on early abort.
 * <p>
 * Product functionality supported: automated unsubscribe (RFC 8058 POST and
 * legacy HTTPS GET) in TidyMail's cleanup flow.
 */
@Component
public final class ApacheUnsubscribeHttpTransport implements UnsubscribeHttpTransport {
    private static final String FORM_CONTENT_TYPE =
        "application/x-www-form-urlencoded";

    private final PinnedTransportFactory transportFactory;
    private final UnsubscribeTransportProperties properties;

    public ApacheUnsubscribeHttpTransport(
            PinnedTransportFactory transportFactory,
            UnsubscribeTransportProperties properties) {
        this.transportFactory = transportFactory;
        this.properties = properties;
    }

    /**
     * Executes the request against its pinned destination within the total
     * timeout, classifying redirects, oversized responses, and remote rejections.
     */
    @Override
    public TransportResult execute(ValidatedUnsubscribeRequest request) {
        HttpUriRequestBase outbound = buildRequest(request);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        PinnedTransportFactory.ClientHandle handle = null;
        try {
            handle = transportFactory.create(request.destination());
            CloseableHttpClient client = handle.client();
            Future<TransportResult> operation = executor.submit(
                () -> executeOnce(client, outbound));
            try {
                return operation.get(
                    properties.totalTimeout().toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException timeout) {
                outbound.cancel();
                operation.cancel(true);
                return TransportResult.failed(
                    null, FailureReason.TIMEOUT_UNKNOWN_REMOTE_RESULT);
            } catch (InterruptedException interrupted) {
                outbound.cancel();
                operation.cancel(true);
                Thread.currentThread().interrupt();
                return TransportResult.failed(
                    null, FailureReason.TIMEOUT_UNKNOWN_REMOTE_RESULT);
            } catch (ExecutionException failure) {
                return classifyFailure(failure.getCause());
            }
        } finally {
            if (handle != null) {
                try {
                    handle.close();
                } catch (IOException | RuntimeException ignored) {
                    // Cleanup cannot change a result already determined from the wire.
                }
            }
            executor.shutdownNow();
        }
    }

    @SuppressWarnings("deprecation")
    private TransportResult executeOnce(
            CloseableHttpClient client, HttpUriRequestBase request) throws IOException {
        CloseableHttpResponse response = client.execute(request);
        try {
            return classifyResponse(response);
        } finally {
            response.close(CloseMode.IMMEDIATE);
        }
    }

    private static HttpUriRequestBase buildRequest(ValidatedUnsubscribeRequest request) {
        if (request.method() == ValidatedUnsubscribeRequest.Method.LEGACY_HTTPS_GET) {
            return new HttpGet(request.uri());
        }
        HttpPost post = new HttpPost(request.uri());
        post.setEntity(new ByteArrayEntity(request.body(), null));
        post.setHeader("Content-Type", FORM_CONTENT_TYPE);
        return post;
    }

    private TransportResult classifyResponse(ClassicHttpResponse response) throws IOException {
        int status = response.getCode();
        int rawStatusClass = status / 100;
        Integer statusClass =
            rawStatusClass >= 2 && rawStatusClass <= 5 ? rawStatusClass : null;
        if (rawStatusClass == 3) {
            return TransportResult.failed(
                statusClass, FailureReason.REDIRECT_BLOCKED);
        }
        if (!discardWithinLimit(response.getEntity())) {
            return TransportResult.failed(
                statusClass, FailureReason.RESPONSE_TOO_LARGE);
        }
        if (rawStatusClass == 2) return TransportResult.accepted();
        return TransportResult.failed(statusClass, FailureReason.REMOTE_REJECTED);
    }

    private boolean discardWithinLimit(HttpEntity entity) throws IOException {
        if (entity == null) return true;
        int limit = properties.maxResponseBytes();
        long advertisedLength = entity.getContentLength();
        if (advertisedLength > limit) return false;
        int consumed = 0;
        byte[] buffer = new byte[Math.min(1024, limit + 1)];
        try (InputStream content = entity.getContent()) {
            int read;
            while ((read = content.read(
                    buffer, 0, Math.min(buffer.length, limit + 1 - consumed))) != -1) {
                consumed += read;
                if (consumed > limit) return false;
            }
        }
        return true;
    }

    private static TransportResult classifyFailure(Throwable failure) {
        if (hasCause(failure, SSLException.class)) {
            return TransportResult.failed(null, FailureReason.TLS_FAILURE);
        }
        if (hasCause(failure, SocketTimeoutException.class)
                || hasCause(failure, InterruptedIOException.class)
                || failure instanceof InterruptedException) {
            return TransportResult.failed(
                null, FailureReason.TIMEOUT_UNKNOWN_REMOTE_RESULT);
        }
        return TransportResult.failed(null, FailureReason.IO_FAILURE);
    }

    private static boolean hasCause(
            Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }
}
