package com.tidymail.unsubscribe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tidymail.repository.UnsubscribeIntentNonceRepository;
import com.tidymail.service.UnsubscribeCandidateService;
import com.tidymail.service.UnsubscribeCandidateService.ExecutionCandidate;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class Phase11UnsubscribeIntentTest {
    @Test
    void expiredAndTamperedIntentsFailBeforeFreshLookupOrNonceConsumption() {
        UnsubscribeCandidateService candidates = mock(UnsubscribeCandidateService.class);
        UnsubscribeIntentNonceRepository nonces =
            mock(UnsubscribeIntentNonceRepository.class);
        UnsubscribeIntentNonceService nonceConsumption =
            mock(UnsubscribeIntentNonceService.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-08-09T12:00:00Z"));
        UnsubscribeIntentService service = new UnsubscribeIntentService(
            candidates, nonces, nonceConsumption, new byte[32], clock,
            new java.security.SecureRandom());
        String candidateKey = UnsubscribeCandidateKey.encode(
            7, 11, "sender@example.com", "list.example");
        ExecutionCandidate candidate = new ExecutionCandidate(
            candidateKey,
            7,
            "a".repeat(64),
            "b".repeat(64),
            "sender@example.com",
            UnsubscribeMethod.RFC8058_POST,
            URI.create("https://unsubscribe.example/private?token=hidden"),
            "unsubscribe.example",
            "c".repeat(64));
        when(candidates.executionCandidate(
            List.of(7L), candidateKey, UnsubscribeMethod.RFC8058_POST))
            .thenReturn(candidate);
        String token = service.issue(
            List.of(7L), candidateKey, UnsubscribeMethod.RFC8058_POST);
        clearInvocations(candidates, nonces, nonceConsumption);

        clock.advanceSeconds(301);
        assertThatThrownBy(() -> service.consume(
            List.of(7L), candidateKey, UnsubscribeMethod.RFC8058_POST, token))
            .isInstanceOfSatisfying(
                UnsubscribeIntentService.IntentException.class,
                failure -> assertThat(failure.reason())
                    .isEqualTo(UnsubscribeReasonCode.EXPIRED_INTENT));
        verifyNoInteractions(candidates, nonces, nonceConsumption);

        String tampered = token.substring(0, token.length() - 1)
            + (token.endsWith("A") ? "B" : "A");
        assertThatThrownBy(() -> service.consume(
            List.of(7L), candidateKey, UnsubscribeMethod.RFC8058_POST, tampered))
            .isInstanceOfSatisfying(
                UnsubscribeIntentService.IntentException.class,
                failure -> assertThat(failure.reason())
                    .isEqualTo(UnsubscribeReasonCode.STALE_INTENT));
    }

    @Test
    void signedIntentBindsTheExactCanonicalSourceCandidateKey() {
        UnsubscribeCandidateService candidates = mock(UnsubscribeCandidateService.class);
        UnsubscribeIntentNonceRepository nonces =
            mock(UnsubscribeIntentNonceRepository.class);
        UnsubscribeIntentNonceService nonceConsumption =
            mock(UnsubscribeIntentNonceService.class);
        UnsubscribeIntentService service = new UnsubscribeIntentService(
            candidates, nonces, nonceConsumption, new byte[32],
            Clock.fixed(Instant.parse("2026-08-09T12:00:00Z"), ZoneOffset.UTC),
            new java.security.SecureRandom());
        String inspectedKey = UnsubscribeCandidateKey.encode(
            7, 11, "sender@example.com", "list.example");
        String replacementSourceKey = UnsubscribeCandidateKey.encode(
            7, 12, "sender@example.com", "list.example");
        ExecutionCandidate candidate = new ExecutionCandidate(
            inspectedKey, 7, "a".repeat(64), "b".repeat(64),
            "sender@example.com", UnsubscribeMethod.RFC8058_POST,
            URI.create("https://unsubscribe.example/private?token=hidden"),
            "unsubscribe.example", "c".repeat(64));
        when(candidates.executionCandidate(
            List.of(7L), inspectedKey, UnsubscribeMethod.RFC8058_POST))
            .thenReturn(candidate);
        String token = service.issue(
            List.of(7L), inspectedKey, UnsubscribeMethod.RFC8058_POST);
        clearInvocations(candidates, nonces, nonceConsumption);

        assertThatThrownBy(() -> service.consume(
            List.of(7L), replacementSourceKey,
            UnsubscribeMethod.RFC8058_POST, token))
            .isInstanceOfSatisfying(
                UnsubscribeIntentService.IntentException.class,
                failure -> assertThat(failure.reason())
                    .isEqualTo(UnsubscribeReasonCode.STALE_INTENT));
        verifyNoInteractions(candidates, nonces, nonceConsumption);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Only UTC is supported");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
