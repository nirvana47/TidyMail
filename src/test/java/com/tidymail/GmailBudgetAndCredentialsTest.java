package com.tidymail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpHeaders;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.CleanupProviderException;
import com.tidymail.mail.gmail.*;
import com.tidymail.security.oauth2.ManagedGoogleCredentialService;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.*;

class GmailBudgetAndCredentialsTest {
    @Test void freshNegativeMonotonicClockDoesNotInventACooldownOrPacingDelay() {
        var clock = new java.util.concurrent.atomic.AtomicLong(-60_000_000_000L);
        var config = new GmailSyncConfigProperties();
        org.springframework.test.util.ReflectionTestUtils.setField(config, "metadataBatchDelayMs", 1000);
        var limiter = new GmailRateLimiter(config, clock::get, millis -> clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis)));
        limiter.chargeForSync(1L, 20, true);
        limiter.paceMetadataBatch();
        assertThat(clock.get()).isEqualTo(-60_000_000_000L);
        limiter.paceMetadataBatch();
        assertThat(clock.get()).isBetween(-59_000_000_000L, -58_900_000_000L);
    }
    @Test void syncWaitsOutShortCooldownButCleanupStillReturnsItsRejection() {
        var clock = new java.util.concurrent.atomic.AtomicLong(-10_000_000_000L);
        var config = new GmailSyncConfigProperties();
        var limiter = new GmailRateLimiter(config, clock::get, millis -> clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis)));
        limiter.recordThrottle(1L, null);
        assertThatThrownBy(() -> limiter.charge(1L, 20, false)).isInstanceOf(CleanupProviderException.class);
        limiter.chargeForSync(1L, 20, true);
        assertThat(clock.get()).isBetween(20_000_000_000L, 20_100_000_000L);
        limiter.recordThrottle(1L, new HttpHeaders().setRetryAfter("120"));
        long before = clock.get();
        assertThatThrownBy(() -> limiter.chargeForSync(1L, 20, true)).isInstanceOf(CleanupProviderException.class);
        assertThat(clock.get()).isEqualTo(before);
    }
    @Test void sharedCooldownHonorsRetryAfterAndDoesNotBlockAnotherAccount() {
        var config=new GmailSyncConfigProperties();var limiter=new GmailRateLimiter(config);
        limiter.recordThrottle(1L,new HttpHeaders().setRetryAfter("120"));
        assertThatThrownBy(()->limiter.charge(1L,50,false)).isInstanceOfSatisfying(CleanupProviderException.class,error->{
            assertThat(error.retryableRejection()).isTrue();assertThat(error.retryAt()).isAfter(Instant.now().plusSeconds(110));
        });
        assertThatCode(()->limiter.charge(2L,50,false)).doesNotThrowAnyException();
    }
    @Test void backgroundBudgetLeavesForegroundCapacityAndWaitingNeverHoldsTheMonitor() throws Exception {
        var config=new GmailSyncConfigProperties();config.setBackfillQuotaUnitsPerMinute(60);
        var limiter=new GmailRateLimiter(config);limiter.charge(1L,500,true);
        var started=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        try {
            var background=pool.submit(()->{started.countDown();limiter.charge(1L,20,true);});
            assertThat(started.await(1,TimeUnit.SECONDS)).isTrue();limiter.charge(2L,50,false);
            var foreground=CompletableFuture.runAsync(()->limiter.charge(1L,50,false));
            foreground.get(2,TimeUnit.SECONDS);assertThat(background.isDone()).isFalse();background.cancel(true);
        } finally {pool.shutdownNow();}
    }
    @Test void managedTokenNearExpiryDoesNotTriggerAnUnsupportedSecondRefresh() throws Exception {
        var managed=mock(ManagedGoogleCredentialService.class);var account=new UserAccount();account.setProvider("google");
        var registration=ClientRegistration.withRegistrationId("google").clientId("fixture")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri("https://example.test/callback")
            .authorizationUri("https://example.test/auth").tokenUri("https://example.test/token").build();
        var token=new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,"synthetic-managed-token",
            Instant.now().minusSeconds(3500),Instant.now().plusSeconds(100),Set.of("gmail.modify"));
        when(managed.authorize(account)).thenReturn(new OAuth2AuthorizedClient(registration,"fixture",token));
        var factory=new GmailClientFactory(managed);var gmail=factory.buildFor(account);
        var request=gmail.getRequestFactory().buildGetRequest(new GenericUrl("https://gmail.googleapis.com/gmail/v1/users/me/profile"));
        assertThat(request.getHeaders().getAuthorization()).isEqualTo("Bearer synthetic-managed-token");
        assertThat(request.getNumberOfRetries()).isZero();
        assertThat(gmail.getRequestFactory().getTransport()).isSameAs(factory.buildFor(account).getRequestFactory().getTransport());
        verify(managed,times(2)).authorize(account);
        var otherProvider=new UserAccount();otherProvider.setProvider("microsoft");
        assertThatThrownBy(()->factory.buildFor(otherProvider)).isInstanceOf(GmailClientInitException.class);

    }
}
