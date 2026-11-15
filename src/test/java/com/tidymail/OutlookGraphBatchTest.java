package com.tidymail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.tidymail.entity.UserAccount;
import com.tidymail.mail.CleanupProviderException;
import com.tidymail.mail.outlook.OutlookGraph;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OutlookGraphBatchTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    @org.junit.jupiter.api.Timeout(35)
    void stalledTokenEndpointTimesOutBeforeMutationAndAllowsRecovery(boolean refresh) throws Exception {
        var endpoint=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var release=new java.util.concurrent.CountDownLatch(1);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        endpoint.createContext("/token",exchange -> {
            try {
                if(calls.incrementAndGet()==1)release.await(30,java.util.concurrent.TimeUnit.SECONDS);
                byte[] body="{\"access_token\":\"recovered\",\"token_type\":\"Bearer\",\"expires_in\":3600}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,body.length);
                exchange.getResponseBody().write(body);
            } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
            finally {exchange.close();}
        });
        endpoint.start();
        try {
            var registration=ClientRegistration.withRegistrationId("microsoft").clientId("fixture")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/callback").authorizationUri("https://login.example/authorize")
                .tokenUri("http://127.0.0.1:"+endpoint.getAddress().getPort()+"/token").build();
            var registrations=new org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository(registration);
            var clients=new InMemoryOAuth2AuthorizedClientService(registrations);
            var principal=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("fixture","unused");
            clients.saveAuthorizedClient(new OAuth2AuthorizedClient(registration,"fixture",
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,"expired",Instant.now().minusSeconds(7200),Instant.now().minusSeconds(3600)),
                new OAuth2RefreshToken("refresh",Instant.now().minusSeconds(7200))),principal);
            var manager=new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations,clients);
            manager.setAuthorizedClientProvider(new com.tidymail.security.SecurityConfig().authorizedClientProvider(60));
            var graph=new OutlookGraph(manager);
            var builder=RestClient.builder();
            var server=MockRestServiceServer.bindTo(builder).build();
            ReflectionTestUtils.setField(graph,"rest",builder.build());
            var account=new UserAccount();account.setId(7L);account.setProvider("microsoft");
            account.setProviderAccountId("fixture");account.setOauthStatus(UserAccount.OAuthStatus.ACTIVE);
            var codeClient=new com.tidymail.security.SecurityConfig().authorizationCodeTokenResponseClient();
            var authorizationRequest=org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://login.example/authorize").clientId("fixture")
                .redirectUri("http://localhost/callback").state("fixture-state").build();
            var authorizationResponse=org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse.success("fixture-code")
                .redirectUri("http://localhost/callback").state("fixture-state").build();
            var grant=new org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest(registration,
                new org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange(authorizationRequest,authorizationResponse));
            if(refresh) {
                assertThatThrownBy(()->graph.request(account,HttpMethod.POST,"/v1.0/$batch",Map.of()))
                    .isInstanceOfSatisfying(CleanupProviderException.class,error->{
                        assertThat(error.status()).isEqualTo(503);
                        assertThat(error.retryableRejection()).isTrue();
                        assertThat(error).hasRootCauseInstanceOf(java.net.SocketTimeoutException.class);
                    });
            } else {
                assertThatThrownBy(()->codeClient.getTokenResponse(grant))
                    .isInstanceOf(OAuth2AuthorizationException.class)
                    .hasRootCauseInstanceOf(java.net.SocketTimeoutException.class);
            }
            server.verify(); // The stalled authorization sent no mutation.
            release.countDown();
            if(refresh) {
                server.expect(requestTo("https://graph.microsoft.com/v1.0/$batch"))
                    .andExpect(header("Authorization","Bearer recovered"))
                    .andRespond(withSuccess("{\"responses\":[]}",MediaType.APPLICATION_JSON));
                assertThat(graph.request(account,HttpMethod.POST,"/v1.0/$batch",Map.of()).path("responses").isArray()).isTrue();
            } else {
                // A fresh authorization attempt can use the endpoint after it recovers.
                assertThat(codeClient.getTokenResponse(grant).getAccessToken().getTokenValue()).isEqualTo("recovered");
            }
            assertThat(calls.get()).isEqualTo(2);
            server.verify();
        } finally {release.countDown();endpoint.stop(0);}
    }

    @Test void transientAuthorizationFailureCanRetryBecauseNoGraphRequestWasSent() {
        var manager=mock(OAuth2AuthorizedClientManager.class);
        when(manager.authorize(any(OAuth2AuthorizeRequest.class))).thenThrow(
            new OAuth2AuthorizationException(new OAuth2Error("invalid_token_response")));
        var graph=new OutlookGraph(manager);
        var builder=RestClient.builder();
        var server=MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(graph,"rest",builder.build());
        var account=new UserAccount();
        account.setId(7L);account.setProvider("microsoft");account.setProviderAccountId("fixture");
        account.setOauthStatus(UserAccount.OAuthStatus.ACTIVE);
        assertThatThrownBy(()->graph.request(account,HttpMethod.POST,"/v1.0/$batch",Map.of()))
            .isInstanceOfSatisfying(CleanupProviderException.class,error->{
                assertThat(error.retryableRejection()).isTrue();
                assertThat(error.status()).isEqualTo(503);
            });
        server.verify();
    }

    @Test void batchTransportReadsIndividualStatusesAndSharesRetryAfterWithSync() {
        var manager=mock(OAuth2AuthorizedClientManager.class);
        var registration=ClientRegistration.withRegistrationId("microsoft").clientId("fixture")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost/callback").authorizationUri("https://login.example/authorize")
            .tokenUri("https://login.example/token").build();
        var token=new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,"fixture-token",Instant.now(),Instant.now().plusSeconds(3600));
        when(manager.authorize(any(OAuth2AuthorizeRequest.class))).thenReturn(new OAuth2AuthorizedClient(registration,"fixture",token));
        var graph=new OutlookGraph(manager);
        var builder=RestClient.builder();
        var server=MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(graph,"rest",builder.build());
        var account=new UserAccount();
        account.setId(7L);
        account.setProvider("microsoft");
        account.setProviderAccountId("fixture");
        account.setOauthStatus(UserAccount.OAuthStatus.ACTIVE);
        server.expect(requestTo("https://graph.microsoft.com/v1.0/$batch"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization","Bearer fixture-token"))
            .andRespond(withSuccess("""
                {"responses":[{"id":"0","status":201,"body":{"id":"a"}},
                  {"id":"1","status":429,"headers":{"retry-after":"60"}}]}
                """,MediaType.APPLICATION_JSON));
        var result=graph.request(account,HttpMethod.POST,"/v1.0/$batch",Map.of("requests",java.util.List.of()));
        assertThat(result.path("responses").size()).isEqualTo(2);
        assertThatThrownBy(()->graph.request(account,HttpMethod.GET,"/v1.0/me/messages/a",null))
            .isInstanceOfSatisfying(CleanupProviderException.class,error->{
                assertThat(error.status()).isEqualTo(429);
                assertThat(error.retryAt()).isAfter(Instant.now().plusSeconds(50));
            });
        server.verify();
    }
}
