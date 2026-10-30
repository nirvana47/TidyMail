package com.tidymail.service;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class JevClassifierTest {
    ObjectMapper json=new ObjectMapper();
    JevClassifier.Evidence evidence(){return new JevClassifier.Evidence(UUID.randomUUID(),"local-hash","shivani.tewari@gene.com","Shivani Tewari","Dinner tonight?","ADDRESS",List.of("CATEGORY_PERSONAL"),true,false);}
    ObjectNode response() {
        var root=json.createObjectNode();root.put("model",JevClassifier.MODEL+"-20261014");root.put("provider","TypeSafe");root.putObject("usage").put("cost",.0001);
        var answers=root.putObject("answers");answer(answers,"email_0_category",JevClassifier.CATEGORIES,"PERSONAL",.98);answer(answers,"email_0_kind",JevClassifier.KINDS,"PERSON",.98);answer(answers,"email_0_stream",JevClassifier.STREAMS,"GENERAL",.98);return root;
    }
    void answer(ObjectNode answers,String id,List<String> options,String choice,double p){var node=answers.putObject(id);node.put("type","choice").put("choice",choice).put("confidence",p);var probs=node.putObject("probabilities");for(String option:options)probs.put(option,option.equals(choice)?p:(1-p)/(options.size()-1));}
    @Test void requestPinsPrivacyRouteAndSendsOnlyBoundedMetadata() throws Exception {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();var classifier=new JevClassifier("test-secret",json,builder);
        server.expect(requestTo("https://openrouter.ai/api/alpha/decisions")).andExpect(header("Authorization","Bearer test-secret"))
            .andExpect(header("X-OpenRouter-Cache","false")).andExpect(request->{
                var payload=json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest)request).getBodyAsString());
                assertThat(payload.path("provider").path("zdr").asBoolean()).isTrue();assertThat(payload.path("provider").path("allow_fallbacks").asBoolean()).isFalse();
                assertThat(payload.path("provider").path("data_collection").asText()).isEqualTo("deny");
                assertThat(payload.path("provider").path("only").get(0).asText()).isEqualTo("typesafe");
                String body=payload.toString();assertThat(body).doesNotContain("test-secret","local-hash",evidence().id().toString(),"body_html","access_token");
                assertThat(payload.path("state").path("email_0").path("sender_name").asText()).isEqualTo("Shivani Tewari");
                assertThat(payload.path("questions").size()).isEqualTo(3);
                assertThat(payload.path("questions").path("email_0_kind").path("instructions").asText()).contains("untrusted","Never infer two addresses");
            }).andRespond(withSuccess(response().toString(),MediaType.APPLICATION_JSON));
        var result=classifier.classify(List.of(evidence()));assertThat(result.decisions().getFirst().category()).isEqualTo("PERSONAL");server.verify();
    }
    @Test void uncertaintyIsExplicitAndCategoriesAreNotInvented() {
        var root=response();answer((ObjectNode)root.path("answers"),"email_0_category",JevClassifier.CATEGORIES,"PERSONAL",.6);
        assertThat(JevClassifier.parse(root,1).decisions().getFirst().category()).isEqualTo("UNCERTAIN");
        ((ObjectNode)root.path("answers").path("email_0_kind")).put("choice","Shivani Tewari");
        assertThatThrownBy(()->JevClassifier.parse(root,1)).isInstanceOf(JevClassifier.Unavailable.class);
    }
    @Test void highReportedConfidenceCannotOverrideWeakProbability() {
        var root=response();answer((ObjectNode)root.path("answers"),"email_0_kind",JevClassifier.KINDS,"PERSON",.6);
        ((ObjectNode)root.path("answers").path("email_0_kind")).put("confidence",1);
        assertThat(JevClassifier.parse(root,1).decisions().getFirst().kind()).isEqualTo("UNKNOWN");
    }
    @Test void malformedMissingAndWrongProviderAnswersFailClosed() {
        var wrong=response();wrong.put("provider","Other");assertThatThrownBy(()->JevClassifier.parse(wrong,1)).isInstanceOf(JevClassifier.Unavailable.class);
        var missing=response();((ObjectNode)missing.path("answers")).remove("email_0_kind");assertThatThrownBy(()->JevClassifier.parse(missing,1)).isInstanceOf(JevClassifier.Unavailable.class);
        var invalid=response();((ObjectNode)invalid.path("answers").path("email_0_category").path("probabilities")).put("PERSONAL",2);assertThatThrownBy(()->JevClassifier.parse(invalid,1)).isInstanceOf(JevClassifier.Unavailable.class);
    }
    @Test void providerErrorsDoNotExposeRemoteContentOrKeysAndDoNotFallback() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();var classifier=new JevClassifier("secret-key",json,builder);
        server.expect(requestTo("https://openrouter.ai/api/alpha/decisions")).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("secret-key private email content"));
        assertThatThrownBy(()->classifier.classify(List.of(evidence()))).isInstanceOf(JevClassifier.Unavailable.class).hasMessageNotContaining("secret-key").hasMessageNotContaining("private email");server.verify();
    }
    @Test void noKeyDoesNotMakeAnyRequest() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();var classifier=new JevClassifier("",json,builder);
        assertThat(classifier.configured()).isFalse();assertThatThrownBy(()->classifier.classify(List.of(evidence()))).hasMessageContaining("OPENROUTER_API_KEY");server.verify();
    }

    @Test void rejectedDecisionsRetainExactReportedCostAndMissingBillingNeverBecomesZero() {
        var invalid=response();invalid.put("provider","Other");invalid.withObject("usage").put("cost",new java.math.BigDecimal("0.000019992"));
        try {JevClassifier.parse(invalid,1);fail("Expected invalid provider");}catch(JevClassifier.Unavailable error){assertThat(error.reportedCost()).isEqualByComparingTo("0.000019992");}
        var missing=response();missing.remove("usage");
        try {JevClassifier.parse(missing,1);fail("Expected missing billing");}catch(JevClassifier.Unavailable error){assertThat(error.reportedCost()).isNull();}
        var zero=response();zero.withObject("usage").put("cost",0);
        assertThat(JevClassifier.parse(zero,1).cost()).isEqualByComparingTo("0");
    }
    @Test void largerTransportBatchKeepsAllThreeDimensionsAndPrivacyRoute() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();var classifier=new JevClassifier("test-secret",json,builder);
        var root=response();var answers=(ObjectNode)root.path("answers");
        for(int i=1;i<10;i++){answer(answers,"email_"+i+"_category",JevClassifier.CATEGORIES,"PERSONAL",.98);answer(answers,"email_"+i+"_kind",JevClassifier.KINDS,"PERSON",.98);answer(answers,"email_"+i+"_stream",JevClassifier.STREAMS,"GENERAL",.98);}
        server.expect(requestTo("https://openrouter.ai/api/alpha/decisions")).andExpect(request->{
            var payload=json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest)request).getBodyAsString());
            assertThat(payload.path("questions").size()).isEqualTo(30);assertThat(payload.path("state").size()).isEqualTo(10);
        }).andRespond(withSuccess(root.toString(),MediaType.APPLICATION_JSON));
        assertThat(classifier.classify(java.util.Collections.nCopies(10,evidence())).decisions()).hasSize(10);server.verify();
    }
}
