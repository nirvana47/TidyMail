package com.tidymail.service;

import static com.tidymail.service.MessageRecommendation.*;
import static com.tidymail.service.RecommendationSemanticContract.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;

class RecommendationSemanticContractTest {
    private final ObjectMapper json=new ObjectMapper();
    private final RecommendationSemanticContract contract=new RecommendationSemanticContract(json);

    private Evidence evidence(String subject,String snippet,boolean reply) {
        return new Evidence(UUID.randomUUID(),"source-1","sender@example.test","Sender",subject,reply,true,
                List.of("CATEGORY_UPDATES"),true,new SemanticQualificationContract.Text(snippet,
                    snippet.isEmpty()?SemanticQualificationContract.State.UNKNOWN:SemanticQualificationContract.State.AVAILABLE),
                CorrespondentHistory.Context.unknown());
    }
    private ObjectNode response(List<Evidence> batch,Purpose selected,Map<Property,Double> overrides) {
        var root=json.createObjectNode();root.put("model",JevClassifier.MODEL+"-20261014").put("provider","TypeSafe");
        root.putObject("usage").put("cost",new java.math.BigDecimal("0.0001"));
        var answers=root.putObject("answers");
        for(int i=0;i<batch.size();i++) {
            var choice=answers.putObject("email_"+i+"_purpose");
            choice.put("type","choice").put("choice",selected.name()).put("confidence",.99);
            var probabilities=choice.putObject("probabilities");
            for(var purpose:Purpose.values()) probabilities.put(purpose.name(),purpose==selected?.95:.05/(Purpose.values().length-1));
            for(var property:Property.values()) answers.putObject("email_"+i+"_"+property.name().toLowerCase(Locale.ROOT))
                .put("type","noul").put("noul",overrides.getOrDefault(property,property==Property.CONTEXT_SUFFICIENT?.99:.01));
        }
        return root;
    }
    private Diagnostic diagnostic(Evidence e,Variant variant,Purpose purpose,Map<Property,Double> values) {
        var parsed=contract.parse(response(List.of(e),purpose,values),List.of(e),variant);
        var local=new Input(e.message(),e.sourceRevision(),Mailbox.INBOX,1000L,
                new Assessment(Purpose.UNKNOWN,Importance.UNKNOWN,Set.of(),Signal.UNKNOWN,Signal.UNKNOWN,Qualification.UNRESOLVED,false),null,
                ReadState.READ,null,java.time.Instant.parse("2026-11-02T12:00:00Z"));
        return contract.diagnose(e,variant,parsed.judgments().getFirst(),local,Policy.initial(),Thresholds.diagnostic());
    }

    @Test void matchedArmsDifferOnlyBySnippetAndDoNotDiscloseLocalProvenanceOrCleanupPreferences() {
        var e=evidence("Statement available","A monthly summary of your account",false);
        var headers=contract.request(List.of(e),Variant.HEADERS);
        var snippets=contract.request(List.of(e),Variant.SNIPPET);
        ((ObjectNode)snippets.path("state").path("email_0")).remove("snippet");
        assertThat(snippets).isEqualTo(headers);
        assertThat(headers.path("questions").size()).isEqualTo(12);
        assertThat(headers.path("questions").path("email_0_purpose").path("criteria").has("STATEMENT")).isTrue();
        assertThat(headers.path("questions").path("email_0_purpose").path("criteria").has("BILL")).isTrue();
        assertThat(headers.toString()).doesNotContain(e.message().toString(),e.sourceRevision(),"normalized_body","policy_revision","TRASH");
        assertThat(headers.path("provider").path("allow_fallbacks").booleanValue()).isFalse();
        assertThat(headers.path("questions").has("email_0_attention_required")).isFalse();
        assertThat(headers.path("questions").has("email_0_promotion")).isFalse();
        assertThat(headers.path("questions").has("email_0_social_platform")).isFalse();
        assertThat(headers.path("questions").has("email_0_marketing")).isTrue();
        assertThat(headers.path("questions").has("email_0_social")).isTrue();
        assertThat(headers.path("questions").path("email_0_purpose").path("criteria").path("PERSONAL").asText()).contains("1:1", "recruiter", "human support");
    }

    @Test void utf8LimitsAndExplicitSnippetStatesBoundEveryInputWithoutFullBodyFallback() {
        var e=evidence("😀".repeat(500),"😀".repeat(500),false);
        var state=contract.request(List.of(e),Variant.SNIPPET).path("state").path("email_0");
        assertThat(state.path("subject").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(1200);
        assertThat(state.path("snippet").path("text").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(1024);
        assertThat(state.path("snippet").path("state").asText()).isEqualTo("TRUNCATED");
        assertThat(contract.request(List.of(e,evidence("B","",false),evidence("C","",false)),Variant.SNIPPET).toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(MAX_REQUEST_BYTES);
        assertThatIllegalArgumentException().isThrownBy(()->contract.request(List.of(e,e),Variant.HEADERS));
        assertThatIllegalArgumentException().isThrownBy(()->new Evidence(e.message(),"source",e.address(),e.name(),e.subject(),false,false,
                List.of("PRIVATE_LABEL"),true,e.snippet(),e.relationship()));
    }

    @Test void responsePreservesAllDistributionsCostsAndMessageAssociationsWithoutAdoptingAnything() {
        var batch=List.of(evidence("One","Text",false),evidence("Two","More text",false));
        var parsed=contract.parse(response(batch,Purpose.STATEMENT,Map.of(Property.STATEMENT,.99)),batch,Variant.SNIPPET);
        assertThat(parsed.reportedCost()).isEqualByComparingTo("0.0001");
        assertThat(parsed.judgments().get(1).message()).isEqualTo(batch.get(1).message());
        assertThat(parsed.judgments().getFirst().purpose().probabilities()).hasSize(Purpose.values().length);
        assertThat(parsed.judgments().getFirst().properties()).hasSize(Property.values().length);
        assertThat(parsed.judgments().getFirst().properties()).containsEntry(Property.HEALTHCARE,.01);
    }

    @Test void malformedForeignMissingAndOverconfidentPrimitiveShapesFailClosed() {
        var e=evidence("Test","Text",false);var batch=List.of(e);
        for(var mutation:List.of("missing","extra","nan","confidence","provider","cost","choice","wrong-type","string")) {
            var root=response(batch,Purpose.MARKETING,Map.of());var answers=(ObjectNode)root.path("answers");
            var security=(ObjectNode)answers.path("email_0_security");
            switch(mutation) {
                case "missing"->answers.remove("email_0_bill");
                case "extra"->answers.putObject("email_1_security");
                case "nan"->security.put("noul",Double.NaN);
                case "confidence"->security.put("confidence",.99);
                case "provider"->root.put("provider","Other");
                case "cost"->((ObjectNode)root.path("usage")).put("cost",-.01);
                case "choice"->((ObjectNode)answers.path("email_0_purpose")).put("choice","BILL");
                case "wrong-type"->security.put("type","choice");
                case "string"->security.put("noul","0.99");
            }
            assertThatIllegalArgumentException().as(mutation).isThrownBy(()->contract.parse(root,batch,Variant.SNIPPET));
        }
    }

    @Test void readStatementsAndBillsAreDisposableWhileReceiptsRemainProtected() {
        var e=evidence("Monthly financial mail","Statement or invoice detail",false);
        assertThat(diagnostic(e,Variant.SNIPPET,Purpose.STATEMENT,Map.of(Property.STATEMENT,.99)).recommendation().action()).isEqualTo(Action.SUGGEST_TRASH);
        assertThat(diagnostic(e,Variant.SNIPPET,Purpose.BILL,Map.of(Property.BILL,.99)).recommendation().action()).isEqualTo(Action.SUGGEST_TRASH);
        assertThat(diagnostic(e,Variant.SNIPPET,Purpose.RECEIPT,Map.of(Property.ORDER,.99)).recommendation().action()).isEqualTo(Action.KEEP_IN_INBOX);
        assertThat(diagnostic(e,Variant.SNIPPET,Purpose.STATEMENT,Map.of(Property.STATEMENT,.99,Property.BILL,.99))
                .recommendation().facets()).contains(Facet.STATEMENT,Facet.BILL);
        assertThat(diagnostic(e,Variant.SNIPPET,Purpose.STATEMENT,Map.of(Property.STATEMENT,.99,Property.BILL,.99))
                .recommendation().action()).isEqualTo(Action.SUGGEST_TRASH);
    }

    @Test void everyUnresolvedProtectedCheckBlocksBulkTrashWhilePositiveChecksProtectMixedContent() {
        var e=evidence("Offer","A routine promotion",false);
        for(var property:Set.of(Property.SECURITY,Property.HUMAN_CORRESPONDENCE,Property.HEALTHCARE,Property.SCHOOL,
                Property.ORDER,Property.BILL)) {
            assertThat(diagnostic(e,Variant.SNIPPET,Purpose.MARKETING,Map.of(property,.5)).recommendation().action()).isEqualTo(Action.REVIEW);
            assertThat(diagnostic(e,Variant.SNIPPET,Purpose.MARKETING,Map.of(property,.99)).recommendation().action())
                    .isEqualTo(Set.of(Property.BILL).contains(property)?Action.SUGGEST_TRASH:Action.KEEP_IN_INBOX);
        }
        assertThat(diagnostic(e,Variant.SNIPPET,Purpose.NEWSLETTER,Map.of(Property.SCHOOL,.99)).recommendation().facets()).contains(Facet.SCHOOL);
        assertThat(diagnostic(e,Variant.SNIPPET,Purpose.RECEIPT,Map.of(Property.ORDER,.99,Property.SOCIAL,.99))
                .recommendation().facets()).contains(Facet.ORDER,Facet.SOCIAL);
        assertThat(diagnostic(e,Variant.SNIPPET,Purpose.RECEIPT,Map.of(Property.ORDER,.99,Property.MARKETING,.99))
                .recommendation().facets()).contains(Facet.ORDER,Facet.MARKETING);
    }

    @Test void forwardedHeaderContentCannotSelfDeclareEnoughContextAndContradictoryAnswersAreDeferred() {
        var e=evidence("Fwd: Password or shipment","A personal forwarding wrapper",true);
        assertThat(diagnostic(e,Variant.HEADERS,Purpose.NOTIFICATION,Map.of()).recommendation().reason()).isEqualTo(Reason.CONTEXT_REQUIRED);
        assertThat(diagnostic(e,Variant.SNIPPET,Purpose.PERSONAL,Map.of(Property.HUMAN_CORRESPONDENCE,.99)).recommendation().action()).isEqualTo(Action.KEEP_IN_INBOX);
        var missing=evidence("Fwd: Shipment","",false);
        assertThat(diagnostic(missing,Variant.SNIPPET,Purpose.MARKETING,Map.of()).recommendation().reason()).isEqualTo(Reason.CONTEXT_REQUIRED);
        assertThat(diagnostic(e,Variant.SNIPPET,Purpose.SECURITY,Map.of(Property.SECURITY,.01)).recommendation().reason()).isEqualTo(Reason.EVIDENCE_CONFLICT);
        assertThat(diagnostic(e,Variant.SNIPPET,Purpose.PERSONAL,Map.of(Property.HUMAN_CORRESPONDENCE,.99)).shadowOnly()).isTrue();
    }

    @Test void revisionChangesInvalidateProvenanceWithoutInventingNewPaidEvidence() {
        var e=evidence("Subject","x".repeat(1024)+"first",false);
        var revised=new Evidence(e.message(),"source-2",e.address(),e.name(),e.subject(),e.reply(),e.unsubscribe(),e.gmailCategories(),e.labelsKnown(),
                new SemanticQualificationContract.Text("x".repeat(1024)+"second",SemanticQualificationContract.State.AVAILABLE),e.relationship());
        assertThat(contract.hasNewEvidence(e,revised,Variant.SNIPPET)).isFalse();
        assertThat(contract.evidenceHash(e,Variant.SNIPPET)).isNotEqualTo(contract.evidenceHash(revised,Variant.SNIPPET));
        var raw=contract.parse(response(List.of(e),Purpose.MARKETING,Map.of()),List.of(e),Variant.SNIPPET).judgments().getFirst();
        var local=new Input(e.message(),"different-source",Mailbox.INBOX,100L,new Assessment(Purpose.UNKNOWN,Importance.UNKNOWN,
                Set.of(),Signal.UNKNOWN,Signal.UNKNOWN,Qualification.UNRESOLVED,false),null);
        assertThatIllegalArgumentException().isThrownBy(()->contract.diagnose(e,Variant.SNIPPET,raw,local,Policy.initial(),Thresholds.diagnostic()));
    }
}
