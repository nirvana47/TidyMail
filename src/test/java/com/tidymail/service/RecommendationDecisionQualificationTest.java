package com.tidymail.service;

import static com.tidymail.service.MessageRecommendation.*;
import static com.tidymail.service.RecommendationSemanticContract.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class RecommendationDecisionQualificationTest {
    private final ObjectMapper json=new ObjectMapper();
    private final RecommendationSemanticContract contract=new RecommendationSemanticContract(json);
    private final RecommendationDecisionQualification qualifier=new RecommendationDecisionQualification(json);
    private Diagnostic replay(Purpose purpose,ReadState read,boolean forward,Map<Property,Double> overrides) {
        var e=new Evidence(UUID.randomUUID(),"source","sender@example.test","Sender",forward?"Fwd: Shipping":"A direct message",forward,false,
                List.of(),true,new SemanticQualificationContract.Text("Bounded actual content",SemanticQualificationContract.State.AVAILABLE),CorrespondentHistory.Context.unknown());
        var probabilities=new EnumMap<Purpose,Double>(Purpose.class);
        for(var p:Purpose.values())probabilities.put(p,p==purpose?.95:.05/(Purpose.values().length-1));
        var properties=new EnumMap<Property,Double>(Property.class);
        for(var p:Property.values())properties.put(p,p==Property.CONTEXT_SUFFICIENT?.3:.01);
        properties.putAll(overrides);
        var judgment=new Judgment(e.message(),contract.evidenceHash(e,Variant.SNIPPET),new Choice(purpose,.95,probabilities),properties);
        var now=Instant.parse("2026-11-03T12:00:00Z");
        var local=new Input(e.message(),e.sourceRevision(),Mailbox.INBOX,100L,new Assessment(Purpose.UNKNOWN,Importance.UNKNOWN,Set.of(),
                Signal.UNKNOWN,Signal.UNKNOWN,Qualification.UNRESOLVED,false),null,read,now,now);
        return qualifier.diagnose(e,Variant.SNIPPET,judgment,local,Policy.initial(),Thresholds.diagnostic());
    }
    @Test void directReceiptCanBeKeptWithoutGlobalContextCertaintyOrResolvedUnrelatedQuestions() {
        var result=replay(Purpose.RECEIPT,ReadState.UNREAD,false,Map.of(Property.HUMAN_CORRESPONDENCE,.5,Property.HEALTHCARE,.5,Property.ORDER,.99));
        assertThat(result.recommendation().action()).isEqualTo(Action.KEEP_IN_INBOX);
        assertThat(result.properties().get(Property.CONTEXT_SUFFICIENT)).isEqualTo(Signal.UNKNOWN);
    }
    @Test void genericContextUncertaintyDoesNotBlockDirectBulkButPermanentProtectionUncertaintyDoes() {
        assertThat(replay(Purpose.MARKETING,ReadState.READ,false,Map.of(Property.BILL,.5)).recommendation().action()).isEqualTo(Action.SUGGEST_TRASH);
        for(var p:Set.of(Property.SECURITY,Property.HUMAN_CORRESPONDENCE,Property.HEALTHCARE,Property.SCHOOL,Property.ORDER))
            assertThat(replay(Purpose.MARKETING,ReadState.READ,false,Map.of(p,.5)).recommendation().action()).isEqualTo(Action.REVIEW);
    }
    @Test void recentUnreadConsequentialUncertaintyIsStillHeldAndPositiveFinancialEvidenceKeeps() {
        assertThat(replay(Purpose.MARKETING,ReadState.UNREAD,false,Map.of(Property.BILL,.5)).recommendation().action()).isEqualTo(Action.REVIEW);
        assertThat(replay(Purpose.BILL,ReadState.UNREAD,false,Map.of(Property.BILL,.99)).recommendation().action()).isEqualTo(Action.KEEP_IN_INBOX);
    }
    @Test void ambiguousForwardCannotEarnTrashButSupportedHumanCorrespondenceCanKeepIt() {
        assertThat(replay(Purpose.MARKETING,ReadState.READ,true,Map.of()).recommendation().action()).isEqualTo(Action.REVIEW);
        assertThat(replay(Purpose.NOTIFICATION,ReadState.READ,true,Map.of(Property.HUMAN_CORRESPONDENCE,.99)).recommendation().action()).isEqualTo(Action.KEEP_IN_INBOX);
    }
}
