package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tidymail.dto.AnalyticsSenderScope;
import com.tidymail.service.AnalyticsDrilldownKeyService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.web.server.ResponseStatusException;

class AnalyticsDrilldownKeyServiceTest {
    @Test
    void tokenIsOpaqueTamperProofOwnerBoundAndExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-10T00:00:00Z"));
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 7);
        AnalyticsDrilldownKeyService service =
            new AnalyticsDrilldownKeyService(key, clock, new java.security.SecureRandom());
        AnalyticsSenderScope scope = new AnalyticsSenderScope(
            41L, "sender@example.com",
            Instant.parse("2026-08-01T00:00:00Z"),
            Instant.parse("2026-08-04T00:00:00Z"));

        String token = service.issue(auth("owner@example.com"), scope);
        assertThat(token).startsWith("v1.")
            .doesNotContain("sender", "example.com", "2026-08", "41");
        assertThat(service.resolve(auth("owner@example.com"), token)).isEqualTo(scope);

        String tampered = token.substring(0, token.length() - 1)
            + (token.endsWith("A") ? "B" : "A");
        assertInvalid(() -> service.resolve(auth("owner@example.com"), tampered));
        assertInvalid(() -> service.resolve(auth("other@example.com"), token));

        clock.set(Instant.parse("2026-08-10T00:16:00Z"));
        assertInvalid(() -> service.resolve(auth("owner@example.com"), token));
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(
                ((ResponseStatusException) error).getStatusCode().value()).isEqualTo(404))
            .hasMessageNotContaining("sender@example.com");
    }

    private static OAuth2AuthenticationToken auth(String email) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("email", email);
        attributes.put("name", "Owner");
        attributes.put("sub", "subject-" + email);
        List<GrantedAuthority> authorities =
            List.of(new SimpleGrantedAuthority("ROLE_USER"));
        DefaultOAuth2User user = new DefaultOAuth2User(authorities, attributes, "sub");
        return new OAuth2AuthenticationToken(user, authorities, "google");
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant value) {
            instant = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException();
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
