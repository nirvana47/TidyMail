package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tidymail.dto.ActionScopeRiskSummary;
import com.tidymail.dto.ActionScopeTargetSummary;
import com.tidymail.service.ActionScopeHasher;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class Phase9ActionScopeTest {

    private static final ActionScopeTargetSummary TARGET =
        new ActionScopeTargetSummary("news@example.com", "news@example.com", "example.com", 1);
    private static final ActionScopeRiskSummary RISK =
        new ActionScopeRiskSummary(1, 0, List.of("PROTECTED_MESSAGES"));

    @Nested
    @DisplayName("Canonical hash and typed summary contracts")
    class PureContracts {

        private final ActionScopeHasher hasher = new ActionScopeHasher();

        @Test
        void knownHashIsVersionedDeterministicAndDeduplicated() {
            ActionScopeHasher.CanonicalScope canonical = hasher.canonicalize(
                7L, "archive", "sender_address", List.of("m-2", "m-1", "m-2"));

            assertThat(canonical.messageIds()).containsExactly("m-1", "m-2");
            assertThat(canonical.scopeHash())
                .isEqualTo("555241e523db980dd4a305a18cee10494f54e7735770e4ab3d3eeac1ca47ab45")
                .matches("[0-9a-f]{64}");
            assertThat(hasher.canonicalize(
                7L, "ARCHIVE", "SENDER_ADDRESS", List.of("m-1", "m-2")).scopeHash())
                .isEqualTo(canonical.scopeHash());
        }

        @Test
        void everyIdentityFieldChangesHashAndLengthPrefixesPreventAmbiguity() {
            String baseline = hash(7L, "ARCHIVE", "SENDER_ADDRESS", List.of("a", "bc"));
            assertThat(hash(8L, "ARCHIVE", "SENDER_ADDRESS", List.of("a", "bc")))
                .isNotEqualTo(baseline);
            assertThat(hash(7L, "TRASH", "SENDER_ADDRESS", List.of("a", "bc")))
                .isNotEqualTo(baseline);
            assertThat(hash(7L, "ARCHIVE", "DOMAIN", List.of("a", "bc")))
                .isNotEqualTo(baseline);
            assertThat(hash(7L, "ARCHIVE", "SENDER_ADDRESS", List.of("ab", "c")))
                .isNotEqualTo(baseline);
            assertThat(hash(7L, "ARCHIVE", "SENDER_ADDRESS", List.of("a|b", "雪")))
                .isNotEqualTo(hash(7L, "ARCHIVE", "SENDER_ADDRESS", List.of("a", "b|雪")));
            assertThatThrownBy(() -> hash(
                7L, "ARCHIVE", "SENDER_ADDRESS", List.of("\uD800")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid Unicode");
        }

        @Test
        void rejectsInvalidIdentityAndBoundsTypedSummaries() {
            assertThatThrownBy(() -> hasher.canonicalize(0L, "ARCHIVE", "SENDER_ADDRESS", List.of("m")))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> hasher.canonicalize(1L, "ARCHIVE", "SENDER_ADDRESS", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> hasher.canonicalize(1L, "ARCHIVE", "SENDER_ADDRESS", List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ActionScopeTargetSummary(
                "x".repeat(513), null, null, 1))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ActionScopeRiskSummary(-1, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ActionScopeRiskSummary(0, 0, List.of("nested.value")))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void summaryJsonRejectsUnknownAndNestedCallerValues() {
            ObjectMapper strict = new ObjectMapper();
            assertThatThrownBy(() -> strict.readValue(
                """
                {"displayLabel":"ok","senderAddress":"a@b.com","senderDomain":"b.com",
                 "selectedTargetCount":1,"subject":"forbidden"}
                """, ActionScopeTargetSummary.class))
                .isInstanceOf(Exception.class);
            assertThatThrownBy(() -> strict.readValue(
                """
                {"protectedCount":0,"highRiskCount":0,"warningCodes":[{"code":"NESTED"}]}
                """, ActionScopeRiskSummary.class))
                .isInstanceOf(Exception.class);
            ArrayList<String> source = new ArrayList<>(List.of("WARNING"));
            ActionScopeRiskSummary summary = new ActionScopeRiskSummary(0, 0, source);
            source.add("CHANGED");
            assertThat(summary.warningCodes()).containsExactly("WARNING");
            assertThatThrownBy(() -> summary.warningCodes().add("CHANGED"))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        private String hash(Long account, String action, String target, List<String> ids) {
            return hasher.canonicalize(account, action, target, ids).scopeHash();
        }
    }
}
