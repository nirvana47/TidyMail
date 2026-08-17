package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tidymail.action.ActionStatus;
import com.tidymail.action.ActionType;
import com.tidymail.action.ExecutionStatus;
import com.tidymail.action.ExecutionTargetStatus;
import com.tidymail.mail.BulkActionResult;
import com.tidymail.service.ActionCompatibility;
import com.tidymail.service.ActionFingerprints;
import com.tidymail.service.ActionStateRules;
import com.tidymail.service.ExecutionFacts;
import com.tidymail.service.ProviderResultClassifier;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Phase12ActionStateMachineTest {
    @Test
    void ACTN_01_compatibilityMatrixIsCompleteAndSymmetric() {
        for (ActionType left : ActionType.values()) {
            for (ActionType right : ActionType.values()) {
                assertThat(ActionCompatibility.compatible(left, right))
                    .isEqualTo(ActionCompatibility.compatible(right, left));
            }
        }
        assertThat(ActionCompatibility.compatible(
            ActionType.ARCHIVE, ActionType.MARK_READ)).isTrue();
        assertThat(ActionCompatibility.compatible(
            ActionType.TRASH, ActionType.ARCHIVE)).isFalse();
        assertThat(ActionCompatibility.compatible(
            ActionType.KEEP, ActionType.UNSUBSCRIBE)).isFalse();
        assertThat(ActionCompatibility.compatible(
            ActionType.PROTECT, ActionType.UNSUBSCRIBE)).isFalse();
        for (ActionType type : ActionType.values()) {
            assertThat(ActionCompatibility.compatible(ActionType.REVIEW, type)).isFalse();
        }
    }

    @Test
    void ACTN_07_stateTransitionsAndCountPartitionsFailClosed() {
        assertThat(ActionStateRules.canTransition(
            ActionStatus.QUEUED, ActionStatus.EXECUTING)).isTrue();
        assertThat(ActionStateRules.canTransition(
            ActionStatus.APPLIED, ActionStatus.EXECUTING)).isFalse();
        assertThat(ActionStateRules.canTransition(
            ExecutionStatus.RESERVED, ExecutionStatus.RUNNING)).isTrue();
        assertThat(ActionStateRules.canTransition(
            ExecutionStatus.APPLIED, ExecutionStatus.RUNNING)).isFalse();
        assertThat(ActionStateRules.canTransition(
            ExecutionTargetStatus.PENDING,
            ExecutionTargetStatus.VERIFICATION_PENDING)).isTrue();
        assertThat(ActionStateRules.canTransition(
            ExecutionTargetStatus.FAILED,
            ExecutionTargetStatus.SUCCEEDED)).isFalse();

        assertThat(new ExecutionFacts(4, 2, 2, 1, 1, 0, null).status())
            .isEqualTo(ExecutionStatus.PARTIAL);
        assertThatThrownBy(() -> new ExecutionFacts(4, 0, 1, 1, 1, 0, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionFacts(1, 2, 1, 0, 0, 0, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ACTN_07_providerResultsPreserveKnownFactsAndNeverBroadenSuccess() {
        Map<String, Integer> requested = ordered("a", "b", "c", "d");
        BulkActionResult partial = new BulkActionResult(
            4, 1, 1, List.of("a"), Map.of("b", "provider detail"),
            Instant.parse("2026-08-09T12:00:00Z"));
        var classified = ProviderResultClassifier.classify(requested, partial);
        assertThat(classified.facts().succeeded()).isEqualTo(1);
        assertThat(classified.facts().failed()).isEqualTo(1);
        assertThat(classified.facts().verificationPending()).isEqualTo(2);
        assertThat(classified.facts().reason().name())
            .doesNotContain("provider detail");

        BulkActionResult malformed = new BulkActionResult(
            99, 3, 2, List.of("a", "a", "foreign"),
            Map.of("a", "contradiction", "b", "raw"), Instant.now());
        var conservative = ProviderResultClassifier.classify(requested, malformed);
        assertThat(conservative.contractInvalid()).isTrue();
        assertThat(conservative.facts().succeeded()).isZero();
        assertThat(conservative.facts().failed()).isEqualTo(1);
        assertThat(conservative.facts().verificationPending()).isEqualTo(3);

        var absent = ProviderResultClassifier.classify(requested, null);
        assertThat(absent.contractInvalid()).isTrue();
        assertThat(absent.facts().succeeded()).isZero();
        assertThat(absent.facts().verificationPending()).isEqualTo(4);
        assertThat(absent.facts().reason().name())
            .isEqualTo("PROVIDER_RESULT_INVALID");
    }

    @Test
    void ACTN_01_fingerprintBindingIsDomainSeparatedAndStable() {
        String first = ActionFingerprints.bind("execute-v1", 1L, "action", "token");
        assertThat(first).matches("[0-9a-f]{64}");
        assertThat(ActionFingerprints.bind("execute-v1", 1L, "action", "token"))
            .isEqualTo(first);
        assertThat(ActionFingerprints.bind("command-v1", 1L, "action", "token"))
            .isNotEqualTo(first);
        assertThat(ActionFingerprints.sha256("provider-id"))
            .matches("[0-9a-f]{64}");
    }

    private static Map<String, Integer> ordered(String... ids) {
        LinkedHashMap<String, Integer> values = new LinkedHashMap<>();
        for (int index = 0; index < ids.length; index++) {
            values.put(ids[index], index);
        }
        return values;
    }
}
