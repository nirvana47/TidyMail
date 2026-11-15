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
