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

    private final java.util.concurrent.locks.ReentrantLock authorization=new java.util.concurrent.locks.ReentrantLock();

    private String accessToken(UserAccount account) {
        if (!"microsoft".equals(account.getProvider()) || account.getOauthStatus() != UserAccount.OAuthStatus.ACTIVE)
            throw new CleanupProviderException(401,null);
        try {
            if(!authorization.tryLock(10,java.util.concurrent.TimeUnit.SECONDS))
                throw new CleanupProviderException(503,"2",true);
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CleanupProviderException(503,"2",true,interrupted);
        }
        try {
            var client = clients.authorize(OAuth2AuthorizeRequest.withClientRegistrationId("microsoft")
                .principal(account.getProviderAccountId()).build());
            if (client == null || client.getAccessToken().getExpiresAt() == null || !client.getAccessToken().getExpiresAt().isAfter(Instant.now()))
                throw new CleanupProviderException(401,null);
            return client.getAccessToken().getTokenValue();
        } catch (org.springframework.security.oauth2.core.OAuth2AuthorizationException error) {
            boolean reconnect=java.util.Set.of("invalid_grant","invalid_client","unauthorized_client","invalid_token")
                .contains(error.getError().getErrorCode());
            // Authorization failed before any Graph mutation was sent; transient failures can be retried.
            throw new CleanupProviderException(reconnect?401:503,reconnect?null:"30",!reconnect,error);
        } finally {authorization.unlock();}
    }

    private final ConcurrentHashMap<Long,java.util.concurrent.Semaphore> capacity = new ConcurrentHashMap<>();

    public JsonNode request(UserAccount account, HttpMethod method, String link, Object payload) {
        var permits = capacity.computeIfAbsent(account.getId(), ignored -> new java.util.concurrent.Semaphore(4, true));
        int count = link.equals("/v1.0/$batch") ? 4 : 1;
        try {
            if(!permits.tryAcquire(count,10,java.util.concurrent.TimeUnit.SECONDS))
                throw new CleanupProviderException(503,"2",true);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CleanupProviderException(503,"2",true,interrupted);
        }
        try { return execute(account, method, link, payload); }
        finally { permits.release(count); }
    }

    private JsonNode execute(UserAccount account, HttpMethod method, String link, Object payload) {
        Instant retryAt = throttled.get(account.getId());
        if (retryAt != null) {
            if (retryAt.isAfter(Instant.now())) throw new CleanupProviderException(429, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .format(retryAt.atZone(java.time.ZoneOffset.UTC)));
            throttled.remove(account.getId(), retryAt);
        }
        URI uri = URI.create(link.startsWith("/") ? ORIGIN + link : link);
        if (!"https".equals(uri.getScheme()) || !"graph.microsoft.com".equals(uri.getHost())
                || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getRawFragment() != null
                || !(uri.getPath().startsWith("/v1.0/me/")
                    || method == HttpMethod.POST && uri.getPath().equals("/v1.0/$batch")))
            throw new IllegalArgumentException("Invalid Graph continuation URL");
        String token=accessToken(account); // Authorization and persistence occur before Graph transport.
        try {
            var request = rest.method(method).uri(uri)
                .headers(headers -> headers.setBearerAuth(token))
                .header("Prefer", "IdType=\"ImmutableId\", odata.maxpagesize=100");
            if (payload != null) request.contentType(MediaType.APPLICATION_JSON).body(payload);
            if (method.equals(HttpMethod.GET) || method.equals(HttpMethod.POST)) {
                var body = request.retrieve().body(JsonNode.class);
                if (uri.getPath().equals("/v1.0/$batch") && body != null) for (var response : body.path("responses")) {
                    var failure=failure(response);
                    if(failure!=null)recordThrottle(account.getId(),failure);
                }
                if(body==null || !body.isObject())throw new CleanupProviderException(0,null,false,
                    new org.springframework.http.converter.HttpMessageConversionException("Invalid Graph response"));
                return body;
            }
            request.retrieve().toBodilessEntity();
            return null;
        } catch (RestClientResponseException error) {
            var headers = error.getResponseHeaders();
            var failure = new CleanupProviderException(error.getStatusCode().value(),
                headers == null ? null : headers.getFirst("Retry-After"),error.getStatusCode().value()==429,error);
            recordThrottle(account.getId(), failure);
            throw failure;
        } catch (RestClientException error) {
            // Response loss is ambiguous for a mutation. The cleanup worker reads it back.
            throw new CleanupProviderException(0, null,false,error);
        }
    }

    static CleanupProviderException failure(JsonNode response) {
        int status=response.path("status").asInt();
        if(status>=200 && status<300)return null;
        String retry=null;
        var headers=response.path("headers").fields();
        while(headers.hasNext()) {
            var header=headers.next();
            if(header.getKey().equalsIgnoreCase("Retry-After"))retry=header.getValue().asText();
        }
        return new CleanupProviderException(status,retry);
    }

    private void recordThrottle(long account, CleanupProviderException failure) {
        if (failure.status() == 429 || failure.status() == 503 && failure.retryAt() != null)
            throttled.merge(account, failure.retryAt() == null ? Instant.now().plusSeconds(30) : failure.retryAt(),
                (existing, requested) -> existing.isAfter(requested) ? existing : requested);
    }
}
