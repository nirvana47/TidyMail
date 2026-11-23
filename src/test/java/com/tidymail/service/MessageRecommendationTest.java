package com.tidymail.service;

import static com.tidymail.service.MessageRecommendation.*;
import static org.assertj.core.api.Assertions.*;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageRecommendationTest {
    private final MessageRecommendation engine = new MessageRecommendation();

    private Input message(Purpose purpose, Set<Facet> facets, Signal security, Signal human,
                          Qualification qualification, boolean contextRequired, Mailbox mailbox,
                          Long bytes, ManualDecision manual) {
        return new Input(UUID.randomUUID(), "message-and-evidence-v1", mailbox, bytes,
                new Assessment(purpose, Importance.UNKNOWN, facets, security, human, qualification, contextRequired), manual,
                ReadState.READ,null,java.time.Instant.parse("2026-11-02T12:00:00Z"));
    }

    private Input message(Purpose purpose, Mailbox mailbox, Long bytes) {
        return message(purpose, Set.of(), Signal.ABSENT, Signal.ABSENT,
                Qualification.QUALIFIED, false, mailbox, bytes, null);
    }

    private Result evaluate(Input input) { return engine.evaluate(input, Policy.initial()); }

    @Test void contextStalenessAndConflictsCannotBecomeResolvedDisposal() {
        for (var state : Qualification.values()) {
            var result = evaluate(message(Purpose.MARKETING, Set.of(), Signal.ABSENT, Signal.ABSENT,
                    state, false, Mailbox.INBOX, 1000L, null));
            assertThat(result.action()).isEqualTo(state == Qualification.QUALIFIED ? Action.SUGGEST_TRASH : Action.REVIEW);
        }
        var forwardedShipment = evaluate(message(Purpose.NOTIFICATION, Set.of(Facet.SHIPPING), Signal.ABSENT,
                Signal.UNKNOWN, Qualification.QUALIFIED, true, Mailbox.INBOX, 1000L, null));
        assertThat(forwardedShipment.action()).isEqualTo(Action.REVIEW);
        assertThat(forwardedShipment.reason()).isEqualTo(Reason.CONTEXT_REQUIRED);
    }

    @Test void largeSizeAddsAFacetWithoutChangingTheRetentionDecision() {
        // Size is added before decision branching: exercise disposal, protection and stored Sent outcomes.
        for (var sample : java.util.List.of(
                java.util.Map.entry(Purpose.MARKETING, Mailbox.INBOX),
                java.util.Map.entry(Purpose.SECURITY, Mailbox.INBOX),
                java.util.Map.entry(Purpose.MARKETING, Mailbox.SENT_ONLY))) {
            var purpose = sample.getKey();
            var mailbox = sample.getValue();
            var small = evaluate(message(purpose, mailbox, 500L));
            var large = evaluate(message(purpose, mailbox, 1_000_001L));
            assertThat(large.action()).isEqualTo(small.action());
            assertThat(large.facets()).contains(Facet.LARGE_MESSAGE);
            assertThat(large.importance()).isEqualTo(Importance.UNKNOWN);
        }
        assertThat(evaluate(message(Purpose.OTHER, Mailbox.INBOX, null)).sizeEstimate()).isNull();
        assertThat(evaluate(message(Purpose.OTHER, Mailbox.INBOX, null)).facets()).doesNotContain(Facet.LARGE_MESSAGE);
        assertThat(evaluate(message(Purpose.OTHER, Mailbox.INBOX, 1_000_000L)).facets()).doesNotContain(Facet.LARGE_MESSAGE);
    }

    @Test void sentOnlyMailIsPreservedButMixedSentInboxMailStillNeedsJudgment() {
        var sent = evaluate(message(Purpose.MARKETING, Mailbox.SENT_ONLY, 9_000_000L));
        assertThat(sent.action()).isEqualTo(Action.KEEP_STORED);
        assertThat(sent.facets()).contains(Facet.SENT, Facet.LARGE_MESSAGE);
        assertThat(evaluate(message(Purpose.PERSONAL, Mailbox.SENT_AND_INBOX, 1000L)).action()).isEqualTo(Action.KEEP_IN_INBOX);
        assertThat(evaluate(message(Purpose.MARKETING, Mailbox.UNKNOWN, 1000L)).action()).isEqualTo(Action.REVIEW);
        assertThat(evaluate(message(Purpose.MARKETING, Mailbox.EXCLUDED, 1000L)).action()).isEqualTo(Action.NONE);
    }

    @Test void inputCannotForgeStorageFactsAndPolicyCannotDisposeOfProtectedPurposes() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Policy("bad", true, Set.of(Purpose.SECURITY)));
        assertThatIllegalArgumentException().isThrownBy(() -> new Assessment(Purpose.OTHER, Importance.UNKNOWN,
                Set.of(Facet.LARGE_MESSAGE), Signal.UNKNOWN, Signal.UNKNOWN, Qualification.UNRESOLVED, false));
        assertThatIllegalArgumentException().isThrownBy(() -> message(Purpose.OTHER, Mailbox.INBOX, -1L));
    }
}
