package com.tidymail.service;

import static com.tidymail.service.MessageRecommendation.*;
import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class PositiveRetentionTest {
    private final MessageRecommendation engine=new MessageRecommendation();
    private Input input(Mailbox mailbox,RetentionEvidence facts,Assessment assessment,ManualDecision correction) {
        return new Input(UUID.randomUUID(),"current-local-evidence",mailbox,6_000_000L,assessment,correction,
                ReadState.READ,null,Instant.parse("2026-11-03T12:00:00Z"),facts);
    }
    private Assessment unclear() {
        return new Assessment(Purpose.UNKNOWN,Importance.UNKNOWN,Set.of(),Signal.UNKNOWN,Signal.UNKNOWN,Qualification.UNRESOLVED,true);
    }
    @Test void oneObservedSentExchangeIsEnoughToPreserveAnUnclearForwardWithoutInventingPersonalOrImportance() {
        for(long count:new long[]{1,4,5,3000}) {
            var result=engine.evaluate(input(Mailbox.INBOX,new RetentionEvidence(count,false),unclear(),null),Policy.initial());
            assertThat(result.action()).isEqualTo(Action.KEEP_IN_INBOX);
            assertThat(result.reason()).isEqualTo(Reason.PRIOR_SENT_CORRESPONDENCE);
            assertThat(result.purpose()).isEqualTo(Purpose.UNKNOWN);
            assertThat(result.importance()).isEqualTo(Importance.UNKNOWN);
            assertThat(result.facets()).contains(Facet.CONTACT,Facet.LARGE_MESSAGE);
        }
        assertThat(engine.evaluate(input(Mailbox.INBOX,RetentionEvidence.none(),unclear(),null),Policy.initial()).action()).isEqualTo(Action.REVIEW);
    }
    @Test void observedCalendarPartPreservesTheMessageWithoutKnowingDatesRecurrenceOrSemanticContext() {
        var result=engine.evaluate(input(Mailbox.INBOX,new RetentionEvidence(0,true),unclear(),null),Policy.initial());
        assertThat(result.action()).isEqualTo(Action.KEEP_IN_INBOX);
        assertThat(result.reason()).isEqualTo(Reason.OBSERVED_CALENDAR);
        assertThat(result.facets()).contains(Facet.CALENDAR_INVITE);
        var combined=engine.evaluate(input(Mailbox.ARCHIVED,new RetentionEvidence(1,true),unclear(),null),Policy.initial());
        assertThat(combined.action()).isEqualTo(Action.KEEP_STORED);
        assertThat(combined.facets()).contains(Facet.CONTACT,Facet.CALENDAR_INVITE,Facet.LARGE_MESSAGE);
    }
    @Test void currentLocalRetentionEvidenceSurvivesStaleSemanticGuessesButRespectsManualDecisionsAndExclusions() {
        var stale=new Assessment(Purpose.UNKNOWN,Importance.UNKNOWN,Set.of(),Signal.UNKNOWN,Signal.UNKNOWN,Qualification.STALE,true);
        assertThat(engine.evaluate(input(Mailbox.INBOX,new RetentionEvidence(1,false),stale,null),Policy.initial()).action()).isEqualTo(Action.KEEP_IN_INBOX);
        assertThat(engine.evaluate(input(Mailbox.INBOX,new RetentionEvidence(1,true),stale,new ManualDecision(Action.SUGGEST_TRASH,Importance.ROUTINE)),Policy.initial()).action()).isEqualTo(Action.SUGGEST_TRASH);
        assertThat(engine.evaluate(input(Mailbox.EXCLUDED,new RetentionEvidence(1,true),stale,null),Policy.initial()).action()).isEqualTo(Action.NONE);
    }
    @Test void supportedReceiptAndSecurityPreservationDoNotNeedTheGenericContextAnswer() {
        for(var purpose:Set.of(Purpose.RECEIPT,Purpose.SECURITY,Purpose.PERSONAL,Purpose.CALENDAR_INVITE)) {
            var supported=new Assessment(purpose,Importance.UNKNOWN,Set.of(),Signal.UNKNOWN,Signal.UNKNOWN,Qualification.QUALIFIED,true);
            assertThat(engine.evaluate(input(Mailbox.INBOX,RetentionEvidence.none(),supported,null),Policy.initial()).action()).isEqualTo(Action.KEEP_IN_INBOX);
        }
        var disposable=new Assessment(Purpose.MARKETING,Importance.UNKNOWN,Set.of(),Signal.ABSENT,Signal.ABSENT,Qualification.QUALIFIED,true);
        assertThat(engine.evaluate(input(Mailbox.INBOX,RetentionEvidence.none(),disposable,null),Policy.initial()).action()).isEqualTo(Action.REVIEW);
    }
}
