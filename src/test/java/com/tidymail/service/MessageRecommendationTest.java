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

    @Test void readFinancialMailIsDisposableWhileReceiptsAndOtherRecordsAreProtected() {
        assertThat(evaluate(message(Purpose.STATEMENT, Mailbox.INBOX, 1000L)).action()).isEqualTo(Action.SUGGEST_TRASH);
        assertThat(evaluate(message(Purpose.BILL, Mailbox.INBOX, 1000L)).action()).isEqualTo(Action.SUGGEST_TRASH);
        for (var purpose : Set.of(Purpose.RECEIPT, Purpose.PERSONAL, Purpose.SECURITY))
            assertThat(evaluate(message(purpose, Mailbox.INBOX, 1000L)).action()).isEqualTo(Action.KEEP_IN_INBOX);
        assertThat(evaluate(message(Purpose.RECEIPT, Mailbox.ARCHIVED, 1000L)).action()).isEqualTo(Action.KEEP_STORED);
    }

    @Test void protectedContentWinsOverBulkAndStatementPolicies() {
        for (var purpose : Set.of(Purpose.MARKETING, Purpose.NEWSLETTER, Purpose.SOCIAL, Purpose.STATEMENT)) {
            for (var facet : Set.of(Facet.HEALTHCARE, Facet.SCHOOL, Facet.ORDER))
                assertThat(evaluate(message(purpose, Set.of(facet), Signal.ABSENT, Signal.ABSENT,
                        Qualification.QUALIFIED, false, Mailbox.INBOX, 10_000L, null)).action()).isEqualTo(Action.KEEP_IN_INBOX);
            for (var security : Signal.values()) for (var human : Signal.values()) {
                var result = evaluate(message(purpose, Set.of(), security, human,
                        Qualification.QUALIFIED, false, Mailbox.INBOX, 10_000L, null));
                assertThat(result.action() == Action.SUGGEST_TRASH)
                        .as("Trash requires resolved negative critical signals: %s %s %s", purpose, security, human)
                        .isEqualTo(security == Signal.ABSENT && human == Signal.ABSENT);
            }
        }
    }

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
        for (var purpose : Purpose.values()) for (var mailbox : Set.of(Mailbox.INBOX, Mailbox.ARCHIVED, Mailbox.SENT_ONLY)) {
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

    @Test void explicitManualDecisionsWinWithoutMakingTheModelMoreCertain() {
        var result = evaluate(message(Purpose.SECURITY, Set.of(), Signal.PRESENT, Signal.UNKNOWN,
                Qualification.STALE, true, Mailbox.INBOX, 9_000_000L,
                new ManualDecision(Action.SUGGEST_TRASH, Importance.ROUTINE)));
        assertThat(result.action()).isEqualTo(Action.SUGGEST_TRASH);
        assertThat(result.reason()).isEqualTo(Reason.MANUAL_DECISION);
        assertThat(result.importance()).isEqualTo(Importance.ROUTINE);
        assertThat(result.evidenceRevision()).isEqualTo("message-and-evidence-v1");
        assertThat(result.policyRevision()).isEqualTo(Policy.initial().revision());
    }

    @Test void calendarAndGeneralUpdatesNeedASeparatePolicyAndEvidence() {
        for (var purpose : Set.of(Purpose.NOTIFICATION, Purpose.OTHER, Purpose.UNKNOWN))
            assertThat(evaluate(message(purpose, Mailbox.INBOX, 1000L)).action()).isEqualTo(Action.REVIEW);
        assertThat(evaluate(message(Purpose.MARKETING, Set.of(Facet.CALENDAR_INVITE), Signal.ABSENT, Signal.ABSENT,
                Qualification.QUALIFIED, false, Mailbox.INBOX, 1000L, null)).action()).isEqualTo(Action.KEEP_IN_INBOX);
        var important = new Input(UUID.randomUUID(), "revision", Mailbox.INBOX, 100L,
                new Assessment(Purpose.MARKETING, Importance.IMPORTANT, Set.of(), Signal.ABSENT,
                        Signal.ABSENT, Qualification.QUALIFIED, false), null);
        assertThat(evaluate(important).action()).isEqualTo(Action.KEEP_IN_INBOX);
    }

    @Test void inputCannotForgeStorageFactsAndPolicyCannotDisposeOfProtectedPurposes() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Policy("bad", true, Set.of(Purpose.SECURITY)));
        assertThatIllegalArgumentException().isThrownBy(() -> new Assessment(Purpose.OTHER, Importance.UNKNOWN,
                Set.of(Facet.LARGE_MESSAGE), Signal.UNKNOWN, Signal.UNKNOWN, Qualification.UNRESOLVED, false));
        assertThatIllegalArgumentException().isThrownBy(() -> message(Purpose.OTHER, Mailbox.INBOX, -1L));
    }
}
