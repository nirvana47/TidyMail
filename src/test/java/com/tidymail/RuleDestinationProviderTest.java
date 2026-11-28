package com.tidymail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.google.api.client.testing.http.*;
import com.google.api.client.http.*;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.gmail.Gmail;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.gmail.*;
import com.tidymail.rules.*;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;

class RuleDestinationProviderTest {
    private final ObjectMapper json=new ObjectMapper();
    private final UserAccount account=new UserAccount();
    private RuleDefinition definition(boolean skip){return new RuleDefinition("Offers",List.of("offers@example.test"),"weekly offers\nmonthly sale","receipt\ninvoice","ORGANIZE","TidyMail - Offers",skip);}

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("emptyGmailInventories")
    void gmailWithAllFiltersDeletedCanListThenCreateAnActionableFilter(int statusCode,String emptyResponse) throws Exception {
        var bodies=new ArrayList<JsonNode>();
        var transport=new MockHttpTransport(){
            @Override public LowLevelHttpRequest buildRequest(String method,String url){
                return new MockLowLevelHttpRequest(url){
                    @Override public LowLevelHttpResponse execute() throws IOException {
                        assertThat(url).endsWith("/settings/filters");
                        String body=emptyResponse;
                        if(method.equals("POST")){
                            bodies.add(json.readTree(getContentAsString()));body="{\"id\":\"new-filter\"}";
                        }else assertThat(method).isEqualTo("GET");
                        return new MockLowLevelHttpResponse().setStatusCode(method.equals("POST")?200:statusCode)
                            .setContentType("application/json").setContent(body).setContentLength(body.length());
                    }
                };
            }
        };
        var clients=mock(GmailClientFactory.class);
        when(clients.buildFor(account)).thenReturn(new Gmail.Builder(transport,JacksonFactory.getDefaultInstance(),null).setApplicationName("Rule fixture").build());
        var provider=new GmailRuleProvider(clients,mock(GmailRateLimiter.class),json);
        assertThat(provider.list(account)).isEmpty();
        var d=new RuleDefinition("Archive offers",List.of("offers@example.test"),"","","READ_ARCHIVE");
        assertThat(provider.create(account,provider.compile(account,d,List.of()))).isEqualTo("new-filter");
        assertThat(bodies).hasSize(1);
        assertThat(bodies.getFirst().path("action").path("removeLabelIds")).isEqualTo(json.readTree("[\"INBOX\",\"UNREAD\"]"));
    }

    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> emptyGmailInventories(){
        return java.util.stream.Stream.of(
            org.junit.jupiter.params.provider.Arguments.of(204,""),
            org.junit.jupiter.params.provider.Arguments.of(200,"{}"),
            org.junit.jupiter.params.provider.Arguments.of(200,"{\"filter\":[]}"));
    }

    @Test void outlookSenderUpdateDoesNotMutateReadBackOrAddUnusedPredicates() throws Exception {
        var graph=mock(com.tidymail.mail.outlook.OutlookGraph.class);
        var provider=new OutlookRuleProvider(graph,json);
        var raw=json.readTree("{\"conditions\":{\"fromAddresses\":[{\"emailAddress\":{\"address\":\"offers@example.test\",\"name\":\"Offers\"}}],\"subjectContains\":[],\"hasAttachments\":false},\"actions\":{\"markAsRead\":true},\"sequence\":9}");
        var original=raw.deepCopy();
        provider.behavior(raw);
        assertThat(raw).isEqualTo(original);
        var payload=provider.withSenders(raw,List.of("offers@example.test","billing@example.test","updates@example.test"));
        assertThat(payload.path("conditions").has("senderContains")).isFalse();
        assertThat(payload.path("conditions").path("fromAddresses")).hasSize(3);
        assertThat(payload.path("actions")).isEqualTo(raw.path("actions"));
        assertThat(payload.path("sequence")).isEqualTo(raw.path("sequence"));
        provider.updateSenders(account,"native-rule",payload);
        verify(graph).request(eq(account),eq(org.springframework.http.HttpMethod.PATCH),anyString(),eq(Map.of("conditions",payload.path("conditions"))));
        var domain=provider.withSenders(raw,List.of("offers@example.test","@example.test"));
        // Switching matching modes must explicitly clear the formerly populated predicate.
        assertThat(domain.path("conditions").path("fromAddresses")).isEmpty();
        assertThat(domain.path("conditions").path("senderContains")).hasSize(2);
        assertThat(raw).isEqualTo(original);
    }

    @Test void inboxFilterCheckKeepsSenderScopeAndAllConditionsAndRejectsPartialTranslation() throws Exception {
        var raw=json.readTree("{\"criteria\":{\"from\":\"{offers@example.test billing@example.test}\",\"to\":\"owner@example.test\",\"subject\":\"Monthly invoice\",\"query\":\"(has:userlabels OR is:unread)\",\"negatedQuery\":\"subject:overdue\",\"hasAttachment\":true,\"excludeChats\":true,\"size\":1000,\"sizeComparison\":\"larger\"}}");
        String query=GmailRuleProvider.inboxFilterQuery(raw,List.of("offers@example.test","other@example.test"));
        assertThat(query).startsWith("in:inbox from:{offers@example.test other@example.test}")
            .contains("from:({offers@example.test billing@example.test})","subject:\"Monthly invoice\"","((has:userlabels OR is:unread))","-(subject:overdue)","to:(owner@example.test)","cc:(owner@example.test)","bcc:(owner@example.test)","has:attachment","-is:chat","larger:1000");
        ((com.fasterxml.jackson.databind.node.ObjectNode)raw.path("criteria")).put("unsupportedCondition",true);
        assertThatThrownBy(()->GmailRuleProvider.inboxFilterQuery(raw,List.of("offers@example.test"))).isInstanceOf(IllegalStateException.class);
        ((com.fasterxml.jackson.databind.node.ObjectNode)raw.path("criteria")).remove("unsupportedCondition");
        ((com.fasterxml.jackson.databind.node.ObjectNode)raw.path("criteria")).put("query","foo) OR in:anywhere (");
        assertThatThrownBy(()->GmailRuleProvider.inboxFilterQuery(raw,List.of("offers@example.test"))).isInstanceOf(IllegalStateException.class);
    }

    @Test void gmailCompilesWithoutMutationThenCreatesOrReusesLabelAndReadsMessageCount() throws Exception {
        var calls=new ArrayList<String>();var existing=new boolean[]{false};
        var transport=new MockHttpTransport(){
            @Override public LowLevelHttpRequest buildRequest(String method,String url){
                return new MockLowLevelHttpRequest(url){
                    @Override public LowLevelHttpResponse execute() throws IOException {
                        calls.add(method+" "+url);String body;
                        if(url.endsWith("/labels")&&method.equals("GET"))body=existing[0]?"{\"labels\":[{\"id\":\"Label_42\",\"name\":\"TidyMail - Offers\",\"type\":\"user\"}]}":"{\"labels\":[]}";
                        else if(url.endsWith("/settings/filters")&&method.equals("POST")) {
                            var submitted=json.readTree(getContentAsString());
                            assertThat(submitted.path("criteria").path("query").asText()).isEqualTo("{subject:\"weekly offers\" subject:\"monthly sale\"}");
                            assertThat(submitted.path("criteria").path("negatedQuery").asText()).isEqualTo("{subject:\"receipt\" subject:\"invoice\"}");
                            assertThat(submitted.path("action").path("addLabelIds").get(0).asText()).isEqualTo("Label_42");
                            body="{\"id\":\"filter-42\"}";
                        }
                        else if(url.endsWith("/settings/filters"))body="{\"filter\":[{\"id\":\"existing-trash\",\"criteria\":{\"from\":\"offers@example.test\"},\"action\":{\"addLabelIds\":[\"TRASH\"]}},{\"id\":\"domain-or\",\"criteria\":{\"from\":\"{@relay.example.test alerts@fixed.example.test}\"},\"action\":{\"addLabelIds\":[\"TRASH\"]}},{\"id\":\"unsafe-import\",\"criteria\":{\"from\":\"one@example.test two@example.test\"},\"action\":{\"addLabelIds\":[\"TRASH\"]}}]}";
                        else if(url.endsWith("/labels")&&method.equals("POST")){
                            assertThat(json.readTree(getContentAsString()).path("name").asText()).isEqualTo("TidyMail - Offers");
                            existing[0]=true;body="{\"id\":\"Label_42\",\"name\":\"TidyMail - Offers\"}";
                        } else if(url.endsWith("/labels/Label_42"))body="{\"id\":\"Label_42\",\"messagesTotal\":17,\"threadsTotal\":9}";
                        else throw new AssertionError(method+" "+url);
                        return new MockLowLevelHttpResponse().setStatusCode(200).setContentType("application/json").setContent(body);
                    }
                };
            }
        };
        var clients=mock(GmailClientFactory.class);
        when(clients.buildFor(account)).thenReturn(new Gmail.Builder(transport,JacksonFactory.getDefaultInstance(),null).setApplicationName("Rule fixture").build());
        var provider=new GmailRuleProvider(clients,mock(GmailRateLimiter.class),json);
        var draft=provider.compile(account,definition(false),List.of());assertThat(calls).isEmpty();
        var ready=provider.prepare(account,draft);
        assertThat(ready.has("_destinationName")).isFalse();
        assertThat(ready.path("action").path("addLabelIds").get(0).asText()).isEqualTo("Label_42");
        assertThat(ready.path("action").has("removeLabelIds")).isFalse();
        var skip=provider.prepare(account,provider.compile(account,definition(true),List.of()));
        assertThat(skip.path("action").path("removeLabelIds").get(0).asText()).isEqualTo("INBOX");
        assertThat(calls.stream().filter(c->c.startsWith("POST")).count()).isEqualTo(1);
        assertThat(provider.create(account,ready)).isEqualTo("filter-42");
        assertThat(provider.destinationCount(account,"Label_42")).isEqualTo(17);
        var inventory=provider.list(account);
        assertThat(inventory.getFirst().compatible().action()).isEqualTo("TRASH");
        assertThat(inventory.get(1).compatible().senders()).containsExactly("@relay.example.test","alerts@fixed.example.test");
        assertThat(inventory.get(2).compatible()).isNull();
    }

    @Test void existingRuleDefinitionsStillLoadWithoutDestinationFields() throws Exception {
        var old=json.readValue("{\"name\":\"Offers\",\"senders\":[\"offers@example.test\"],\"subject\":\"\",\"exceptSubject\":\"\",\"action\":\"TRASH\"}",RuleDefinition.class);
        assertThat(old.trash()).isTrue();assertThat(old.destination()).isEmpty();
    }
}
