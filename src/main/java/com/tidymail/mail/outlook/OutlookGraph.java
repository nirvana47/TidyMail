package com.tidymail.mail.outlook;

import com.fasterxml.jackson.databind.JsonNode;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.CleanupProviderException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class OutlookGraph {
    private static final String ORIGIN = "https://graph.microsoft.com";
    private final OAuth2AuthorizedClientManager clients;
    private final RestClient rest;
    private final ConcurrentHashMap<Long,Instant> throttled = new ConcurrentHashMap<>();

    public OutlookGraph(OAuth2AuthorizedClientManager clients) {
        this.clients = clients;
        var requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build());
        requests.setReadTimeout(Duration.ofSeconds(20));
        rest = RestClient.builder().requestFactory(requests).build();
    }

    private synchronized String accessToken(UserAccount account) {
        if (!"microsoft".equals(account.getProvider()) || account.getOauthStatus() != UserAccount.OAuthStatus.ACTIVE)
            throw new IllegalStateException("OUTLOOK_RECONNECT_REQUIRED");
        var client = clients.authorize(OAuth2AuthorizeRequest.withClientRegistrationId("microsoft")
            .principal(account.getProviderAccountId()).build());
        if (client == null || !client.getAccessToken().getExpiresAt().isAfter(java.time.Instant.now()))
            throw new IllegalStateException("OUTLOOK_RECONNECT_REQUIRED");
        return client.getAccessToken().getTokenValue();
    }

    public JsonNode request(UserAccount account, HttpMethod method, String link, Object payload) {
        Instant retryAt = throttled.get(account.getId());
        if (retryAt != null) {
            if (retryAt.isAfter(Instant.now())) throw new CleanupProviderException(429, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .format(retryAt.atZone(java.time.ZoneOffset.UTC)));
            throttled.remove(account.getId(), retryAt);
        }
        URI uri = URI.create(link.startsWith("/") ? ORIGIN + link : link);
        if (!"https".equals(uri.getScheme()) || !"graph.microsoft.com".equals(uri.getHost())
                || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getRawFragment() != null
                || !uri.getPath().startsWith("/v1.0/me/"))
            throw new IllegalArgumentException("Invalid Graph continuation URL");
        try {
            var request = rest.method(method).uri(uri)
                .headers(headers -> headers.setBearerAuth(accessToken(account)))
                .header("Prefer", "IdType=\"ImmutableId\", odata.maxpagesize=100");
            if (payload != null) request.contentType(MediaType.APPLICATION_JSON).body(payload);
            if (method.equals(HttpMethod.GET)) return request.retrieve().body(JsonNode.class);
            request.retrieve().toBodilessEntity();
            return null;
        } catch (RestClientResponseException error) {
            var headers = error.getResponseHeaders();
            var failure = new CleanupProviderException(error.getStatusCode().value(),
                headers == null ? null : headers.getFirst("Retry-After"));
            if (failure.status() == 429 || failure.status() == 503 && failure.retryAt() != null)
                throttled.merge(account.getId(), failure.retryAt() == null ? Instant.now().plusSeconds(30) : failure.retryAt(),
                    (existing, requested) -> existing.isAfter(requested) ? existing : requested);
            throw failure;
        } catch (RestClientException error) {
            // Response loss is ambiguous for a mutation. The cleanup worker reads it back.
            throw new CleanupProviderException(0, null);
        }
    }
}
