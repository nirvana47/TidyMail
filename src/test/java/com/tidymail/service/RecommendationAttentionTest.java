package com.tidymail.service;

import static com.tidymail.service.MessageRecommendation.*;
import static org.assertj.core.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RecommendationAttentionTest {
    private final Instant now=Instant.parse("2026-11-02T12:00:00Z");
    private final MessageRecommendation engine=new MessageRecommendation();
    private Input input(Purpose purpose,ReadState read,Instant at,Set<Facet> facets) {
        return new Input(UUID.randomUUID(),"revision",Mailbox.INBOX,100L,
                new Assessment(purpose,Importance.UNKNOWN,facets,Signal.ABSENT,Signal.ABSENT,Qualification.QUALIFIED,false),
                null,read,at,now);
    }
    private Result evaluate(Purpose purpose,ReadState read,Instant at,Set<Facet> facets) {
        return engine.evaluate(input(purpose,read,at,facets),Policy.initial());
    }
    @Test void onlyRecentUnreadConsequentialMailIsHeldWhileReadOrOldBillsAreEligibleForTrash() {
        for(var purpose:Set.of(Purpose.BILL,Purpose.STATEMENT)) {
            assertThat(evaluate(purpose,ReadState.UNREAD,now.minus(Duration.ofDays(45)),Set.of()).action()).isEqualTo(Action.KEEP_IN_INBOX);
            assertThat(evaluate(purpose,ReadState.UNREAD,now.minus(Duration.ofDays(60)),Set.of()).action()).isEqualTo(Action.KEEP_IN_INBOX);
            assertThat(evaluate(purpose,ReadState.UNREAD,now.minus(Duration.ofDays(60)).minusSeconds(1),Set.of()).action()).isEqualTo(Action.SUGGEST_TRASH);
            assertThat(evaluate(purpose,ReadState.READ,now,Set.of()).action()).isEqualTo(Action.SUGGEST_TRASH);
        }
        assertThat(evaluate(Purpose.MARKETING,ReadState.UNREAD,now,Set.of()).action()).isEqualTo(Action.SUGGEST_TRASH);
        assertThat(evaluate(Purpose.SOCIAL,ReadState.UNREAD,now,Set.of()).action()).isEqualTo(Action.SUGGEST_TRASH);
    }
    @Test void olderOrReadDoesNotEraseIndependentRecordAndPersonalProtections() {
        for(var purpose:Set.of(Purpose.PERSONAL,Purpose.RECEIPT,Purpose.SECURITY))
            for(var read:Set.of(ReadState.READ,ReadState.UNREAD))
                assertThat(evaluate(purpose,read,now.minus(Duration.ofDays(365)),Set.of()).action()).isEqualTo(Action.KEEP_IN_INBOX);
        for(var facet:Set.of(Facet.HEALTHCARE,Facet.SCHOOL,Facet.ORDER,Facet.HUMAN_CORRESPONDENCE,Facet.SECURITY))
            assertThat(evaluate(Purpose.BILL,ReadState.READ,now.minus(Duration.ofDays(365)),Set.of(facet)).action()).isEqualTo(Action.KEEP_IN_INBOX);
    }
    @Test void missingFutureAndUnknownReadEvidenceCannotManufactureAnOldBill() {
        for(var read:ReadState.values()) {
            Action expected=read==ReadState.READ?Action.SUGGEST_TRASH:Action.REVIEW;
            assertThat(evaluate(Purpose.BILL,read,null,Set.of()).action()).isEqualTo(expected);
            assertThat(evaluate(Purpose.BILL,read,now.plusSeconds(1),Set.of()).action()).isEqualTo(expected);
        }
        assertThat(evaluate(Purpose.BILL,ReadState.UNKNOWN,now.minus(Duration.ofDays(1000)),Set.of()).reason()).isEqualTo(Reason.UNKNOWN_READ_OR_DATE);
    }
    @Test void nextEvaluationFollowsAnExactElapsedDayBoundaryWithoutChangingImportance() {
        Instant received=now.minus(Duration.ofDays(59));
        var result=evaluate(Purpose.BILL,ReadState.UNREAD,received,Set.of());
        assertThat(result.nextEvaluationAt()).isEqualTo(received.plus(Duration.ofDays(60)).plusNanos(1000));
        assertThat(result.attention()).isEqualTo(Attention.RECENT_UNREAD);
        assertThat(result.importance()).isEqualTo(Importance.UNKNOWN);
        assertThat(evaluate(Purpose.BILL,ReadState.UNREAD,now.minus(Duration.ofDays(61)),Set.of()).nextEvaluationAt()).isNull();
        assertThat(evaluate(Purpose.BILL,ReadState.READ,received,Set.of()).nextEvaluationAt()).isNull();
    }
    @Test void explicitRecordPolicyAndManualChoiceStillWinWithoutAutomaticallyArchivingAnything() {
        var input=input(Purpose.BILL,ReadState.READ,now,Set.of());
        var retain=new Policy("retain",true,Set.of(Purpose.MARKETING),60,false);
        assertThat(engine.evaluate(input,retain).action()).isEqualTo(Action.KEEP_IN_INBOX);
        var manual=new Input(input.message(),"revision",Mailbox.INBOX,100L,input.assessment(),new ManualDecision(Action.ARCHIVE,Importance.ROUTINE),
                ReadState.UNREAD,now,now);
        assertThat(engine.evaluate(manual,Policy.initial()).action()).isEqualTo(Action.ARCHIVE);
        var archived=new Input(input.message(),"revision",Mailbox.ARCHIVED,100L,input.assessment(),null,ReadState.UNREAD,now,now);
        assertThat(engine.evaluate(archived,Policy.initial()).action()).isEqualTo(Action.KEEP_STORED);
    }
}
