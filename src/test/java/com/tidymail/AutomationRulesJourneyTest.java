package com.tidymail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tidymail.rules.*;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.mail.outlook.OutlookGraph;
import com.tidymail.support.PostgresJourneyTest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

/** Real HTTP/SQL journeys; providers are simulated, so no live mail or native rules are changed. */
@org.springframework.test.context.TestPropertySource(properties={"app.mvp.enabled=true","MICROSOFT_CLIENT_ID=fixture-client","MICROSOFT_CLIENT_SECRET=fixture-secret"})
class AutomationRulesJourneyTest extends PostgresJourneyTest {
    @Autowired AutomationRuleService rules;
    @Autowired UserAccountRepository accounts;
    @Autowired ObjectMapper json;
    @SpyBean GmailRuleProvider gmailRules;
    @MockBean OutlookGraph graph;
    private final List<RuleProvider.NativeRule> nativeGmail=new ArrayList<>();
    private long account;
    private int creates;
    private boolean uncertain;
    private static final String EMAIL="rules@example.test";
    private static final String SENDER="offers@example.test";

    @BeforeEach void prepare(){
        nativeGmail.clear();creates=0;uncertain=false;
        doReturn(List.of()).when(gmailRules).destinations(any());
        account=userAccounts.createAccount(EMAIL);
        var a=accounts.findById(account).orElseThrow();a.setScopes(List.of("https://www.googleapis.com/auth/gmail.settings.basic"));accounts.save(a);
        doAnswer(i->List.copyOf(nativeGmail)).when(gmailRules).list(any());
        doAnswer(i->{creates++;JsonNode raw=((JsonNode)i.getArgument(1)).deepCopy();((ObjectNode)raw).put("id","native-"+creates);
            nativeGmail.add(new RuleProvider.NativeRule("native-"+creates,"Gmail filter",true,false,raw,null,""));
            if(uncertain)throw new IllegalStateException("Simulated response lost after provider applied creation");return "native-"+creates;
        }).when(gmailRules).create(any(),any());
        doAnswer(i->{nativeGmail.removeIf(n->n.id().equals(i.getArgument(1)));return null;}).when(gmailRules).pause(any(),anyString());
        jdbc.update("INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,subject_header,labels,received_at) VALUES(?,'google','mail-1',?,'Weekly offers',ARRAY['INBOX'],now())",account,SENDER);
    }
    private RuleDefinition definition(){return new RuleDefinition("Read and archive offers",List.of(SENDER),"","","READ_ARCHIVE");}
    private UUID draft(long a){return rules.save(a,null,0,definition());}
    private UUID preview(long a,UUID id){return (UUID)rules.preview(a,id).get("id");}

    @Test void browserDraftPreviewEnablePauseResumeAndRemoveHaveRealPersistence() throws Exception {
        var session=new MockHttpSession();var auth=authenticatedAs(EMAIL);
        mockMvc.perform(get("/rules").session(session).with(auth)).andExpect(status().isOk());
        mockMvc.perform(get("/rules/new?inbox=true&action=READ_ARCHIVE").session(session).with(auth))
            .andExpect(status().isOk()).andExpect(model().attribute("inbox",true));
        mockMvc.perform(post("/rules/save").session(session).with(auth).with(csrfToken())
            .param("account",""+account).param("inbox","true").param("name","Invalid rule").param("senders","not an address").param("action","READ_ARCHIVE"))
            .andExpect(status().isBadRequest()).andExpect(view().name("rule-inline-error"));
        mockMvc.perform(post("/rules/save").session(session).with(auth).param("account",""+account)).andExpect(status().isForbidden());
        var save=mockMvc.perform(post("/rules/save").session(session).with(auth).with(csrfToken())
            .param("account",""+account).param("inbox","true").param("name","Read and archive offers").param("senders",SENDER).param("action","READ_ARCHIVE"))
            .andExpect(status().is3xxRedirection()).andReturn();
        String location=save.getResponse().getRedirectedUrl();
        mockMvc.perform(get(location).session(session).with(auth)).andExpect(status().isOk());
        UUID id=UUID.fromString(location.split("/")[2].split("\\?")[0]);
        assertThat(creates).isZero();
        var enabled=mockMvc.perform(post("/rules/"+id+"/enable").session(session).with(auth).with(csrfToken())
            .param("account",""+account).param("preview",preview(account,id).toString()).param("inbox","true"))
            .andExpect(status().is3xxRedirection()).andReturn();
        mockMvc.perform(get(enabled.getResponse().getRedirectedUrl()).session(session).with(auth))
            .andExpect(status().isOk()).andExpect(model().attribute("inbox",true)).andExpect(model().attribute("result",true));
        assertThat(rules.get(account,id).state()).isEqualTo("ACTIVE");
        assertThat(nativeGmail.getFirst().raw().path("action").path("removeLabelIds")).isEqualTo(json.readTree("[\"INBOX\",\"UNREAD\"]"));
        mockMvc.perform(get("/rules").session(session).with(auth)).andExpect(status().isOk());
        rules.manage(account,id,"pause");assertThat(nativeGmail).isEmpty();assertThat(rules.get(account,id).state()).isEqualTo("PAUSED");
        rules.enable(account,id,preview(account,id),true);assertThat(creates).isEqualTo(2);
        rules.manage(account,id,"remove");assertThat(nativeGmail).isEmpty();assertThat(rules.get(account,id).state()).isEqualTo("REMOVED");
    }

    @Autowired RuleExistingMailService existingMail;
    @Autowired com.tidymail.service.DashboardJobService filingJobs;
    @Autowired com.tidymail.job.DashboardCleanupWorker filingWorker;
    @Test void reviewReplacesEditorAndSuccessfulRuleOffersExactlyScopedOptionalFiling() throws Exception {
        jdbc.update("DELETE FROM message_metadata WHERE account_id=?",account);
        var matches=new HashSet<String>();
        for(int i=0;i<42;i++) {
            String id="scope-"+i;matches.add(id);
            String label=i<28?"INBOX":i<38?"":i==38?"SPAM":i==39?"TRASH":i==40?"DRAFT":"SENT";
            jdbc.update("""
                INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,dashboard_from_address,
                  dashboard_unit_kind,dashboard_unit_key,subject_header,labels,label_evidence_known,unread,received_at)
                VALUES(?,'google',?, ?,?,'ADDRESS',?,'Weekly offers',?::text[],true,?,now()-(?*interval '1 hour'))
                """,account,id,SENDER,SENDER,SENDER,label.isEmpty()?"{}":"{"+label+"}",i<22,i);
        }
        doReturn(new GmailRuleProvider.Matches(matches,matches)).when(gmailRules).matching(any(),any());
        doReturn(List.of(new GmailRuleProvider.Destination("Label_scope","Offers"))).when(gmailRules).destinations(any());
        doAnswer(i->gmailRules.useDestination(i.getArgument(1),"Label_scope")).when(gmailRules).prepare(any(),any());
        doReturn(0L).when(gmailRules).destinationCount(any(),anyString());
        var unrelated=json.readTree("{\"criteria\":{\"from\":\"unrelated.example\"},\"action\":{\"addLabelIds\":[\"TRASH\"]}}");
        nativeGmail.add(new RuleProvider.NativeRule("unrelated","Unrelated filter",true,false,unrelated,null,""));
        var d=new RuleDefinition("Offers",List.of(SENDER),"Weekly offers","","ORGANIZE","Offers",true,"Label_scope","EXISTING");
        UUID id=rules.save(account,null,0,d);
        var review=mockMvc.perform(get("/rules/"+id+"?preview=true&inbox=true&mailScope=inbox").with(authenticatedAs(EMAIL)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(review).contains("Back to edit rule","Create rule","Recent matching emails","Show more").doesNotContain("name=\"senders\""," matching</strong>","toPrettyString","Unrelated filter","Refresh preview");
        assertThat(creates).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM cleanup_jobs",Integer.class)).isZero();
        rules.enable(account,id,preview(account,id),false); // unrelated filters require no acknowledgement
        assertThat(existingMail.matches(account,id,"inbox").total()).isEqualTo(28);
        assertThat(existingMail.matches(account,id,"inbox").unread()).isEqualTo(22);
        assertThat(existingMail.matches(account,id,"all").total()).isEqualTo(38);
        assertThat(existingMail.matches(account,id,"all").inbox()).isEqualTo(28);
        mockMvc.perform(get("/rules/"+id+"?result=true&inbox=true&mailScope=inbox").with(authenticatedAs(EMAIL)))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("28 matching emails in Inbox")));
        var response=mockMvc.perform(post("/rules/"+id+"/existing-preview").with(authenticatedAs(EMAIL)).with(csrfToken())
            .param("account",""+account).param("mailScope","inbox")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID exact=UUID.fromString(json.readTree(response).path("id").asText());
        assertThat(json.readTree(response).path("selected").asInt()).isEqualTo(28);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cleanup_jobs",Integer.class)).isZero();
        var job=filingJobs.confirm(account,exact);filingWorker.runOnce();
        assertThat(filingJobs.get(account,job.id()).changed()).isEqualTo(28);
        verify(gmailProvider).fileOnce(any(),argThat(ids->ids.size()==28&&!ids.contains("scope-28")),eq("Label_scope"),eq(true));
        long foreign=userAccounts.createAccount("foreign-rule@example.test");
        assertThatThrownBy(()->existingMail.preview(foreign,id,"inbox")).isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(404));
    }

    @Test void inboxSenderAdditionPreservesSettingsAndRecoversLostGmailReplacementReceipt() throws Exception {
        doReturn(new GmailRuleProvider.Matches(Set.of("mail-1","added-1"),Set.of("mail-1","added-1"))).when(gmailRules).matching(any(),any());
        doAnswer(i->gmailRules.useDestination(i.getArgument(1),"Label_add")).when(gmailRules).prepare(any(),any());
        doReturn(0L).when(gmailRules).destinationCount(any(),anyString());
        var d=new RuleDefinition("Receipts",List.of(SENDER),"receipt","overdue","ORGANIZE","Receipts",true,"Label_add","EXISTING");
        UUID id=rules.save(account,null,0,d);rules.enable(account,id,preview(account,id),true);
        var original=nativeGmail.getFirst().raw().deepCopy();
        mockMvc.perform(get("/rules/add-senders").param("account",""+account).param("senders","billing@example.test").param("inbox","true").with(authenticatedAs(EMAIL)))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Review addition")));
        var response=mockMvc.perform(post("/rules/"+id+"/senders/review").param("account",""+account).param("senders","billing@example.test").param("inbox","true").with(authenticatedAs(EMAIL)).with(csrfToken()))
            .andExpect(status().isOk()).andExpect(model().attributeExists("addition")).andReturn();
        var addition=(Map<?,?>)response.getModelAndView().getModel().get("addition");
        assertThat(creates).isEqualTo(1);assertThat(rules.get(account,id).definition()).isEqualTo(d);
        assertThat(rules.get(account,id).state()).isEqualTo("ACTIVE");
        uncertain=true; // Provider creates the replacement, but its response is lost.
        rules.addSenders(account,id,(UUID)addition.get("id"),true);
        assertThat(rules.get(account,id).pending()).isEqualTo("UPDATE_SENDERS");assertThat(nativeGmail).hasSize(2);
        rules.refresh(account);
        var updated=rules.get(account,id);
        assertThat(updated.pending()).isNull();assertThat(updated.state()).isEqualTo("ACTIVE");assertThat(creates).isEqualTo(2);assertThat(nativeGmail).hasSize(1);
        assertThat(updated.definition().senders()).containsExactly("billing@example.test",SENDER);
        var expected=original.deepCopy();((ObjectNode)expected.path("criteria")).put("from","{billing@example.test "+SENDER+"}");
        assertThat(gmailRules.behavior(nativeGmail.getFirst().raw())).isEqualTo(gmailRules.behavior(expected));
        assertThatThrownBy(()->rules.addSenders(account,id,(UUID)addition.get("id"),true)).hasMessageContaining("changed");
        assertThatThrownBy(()->rules.reviewSenders(account,id,List.of("billing@example.test"))).hasMessageContaining("already included");
        jdbc.update("INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,subject_header,labels,received_at) VALUES(?,'google','added-1','billing@example.test','receipt',ARRAY['INBOX'],now())",account);
        assertThat(existingMail.matches(account,id,"inbox",List.of("billing@example.test")).total()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cleanup_jobs",Integer.class)).isZero();
        mockMvc.perform(post("/rules/"+id+"/senders/review").param("account",""+account).param("senders","another@example.test").with(authenticatedAs(EMAIL)))
            .andExpect(status().isForbidden());
        long foreign=userAccounts.createAccount("sender-add-foreign@example.test");
        assertThatThrownBy(()->rules.reviewSenders(foreign,id,List.of("another@example.test"))).hasMessageContaining("not found");
        rules.manage(account,id,"pause");
        var paused=rules.reviewSenders(account,id,List.of("another@example.test"));
        rules.addSenders(account,id,(UUID)paused.get("id"),true);
        assertThat(rules.get(account,id).state()).isEqualTo("PAUSED");assertThat(nativeGmail).isEmpty();assertThat(creates).isEqualTo(2);
    }

    @Test void deleteOnlyConfirmedEligibleDraftsInOwnedSelectedMailbox() throws Exception {
        long other=userAccounts.createAccount("other-drafts@example.test");
        UUID eligible=draft(account),changed=draft(account),edited=draft(account),pending=draft(account),attached=draft(account),active=draft(account),paused=draft(account),foreign=draft(other);
        jdbc.update("UPDATE automation_rules SET pending_operation='CREATE' WHERE id=?",pending);
        jdbc.update("UPDATE automation_rules SET remote_id='attached' WHERE id=?",attached);
        jdbc.update("UPDATE automation_rules SET state='ACTIVE',remote_id='active' WHERE id=?",active);
        jdbc.update("UPDATE automation_rules SET state='PAUSED' WHERE id=?",paused);
        var session=new MockHttpSession();var auth=authenticatedAs(EMAIL);
        String html=mockMvc.perform(get("/rules").session(session).with(auth)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Delete all drafts","Delete draft");
        var response=mockMvc.perform(get("/rules/drafts/deletion-preview").session(session).with(auth).param("account",""+account))
            .andExpect(status().isOk()).andReturn();
        var snapshot=json.readTree(response.getResponse().getContentAsString());assertThat(snapshot.path("count").asInt()).isEqualTo(3);
        // Opening/cancelling confirmation has no effect. A new opening replaces the snapshot.
        assertThat(rules.get(account,eligible).state()).isEqualTo("DRAFT");
        response=mockMvc.perform(get("/rules/drafts/deletion-preview").session(session).with(auth).param("account",""+account)).andReturn();
        String token=json.readTree(response.getResponse().getContentAsString()).path("token").asText();
        UUID later=draft(account);
        jdbc.update("UPDATE automation_rules SET state='PAUSED' WHERE id=?",changed);
        rules.save(account,edited,1,new RuleDefinition("Edited draft",List.of(SENDER),"","","READ"));
        clearInvocations(gmailRules,graph);
        mockMvc.perform(post("/rules/drafts/delete").session(session).with(auth).param("account",""+account).param("confirmation",token)).andExpect(status().isForbidden());
        mockMvc.perform(post("/rules/drafts/delete").session(session).with(auth).with(csrfToken()).param("account",""+other).param("confirmation",token))
            .andExpect(flash().attributeExists("error"));
        mockMvc.perform(post("/rules/drafts/delete").session(session).with(auth).with(csrfToken()).param("account",""+account).param("confirmation",token))
            .andExpect(redirectedUrl("/rules")).andExpect(flash().attribute("notice","Deleted 1 draft. Skipped 2 changed or already removed."));
        assertThat(rules.get(account,eligible).state()).isEqualTo("REMOVED");
        for(UUID id:List.of(edited,later,pending,attached))assertThat(rules.get(account,id).state()).isEqualTo("DRAFT");
        assertThat(rules.get(account,changed).state()).isEqualTo("PAUSED");
        assertThat(rules.get(account,active).state()).isEqualTo("ACTIVE");assertThat(rules.get(account,paused).state()).isEqualTo("PAUSED");
        assertThat(rules.get(other,foreign).state()).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM automation_rule_events WHERE rule_id=?",Integer.class,eligible)).isEqualTo(2);
        verifyNoInteractions(gmailRules,graph);
        // Single deletion uses the same fixed confirmation and local removal path.
        response=mockMvc.perform(get("/rules/drafts/deletion-preview").session(session).with(auth).param("account",""+account).param("id",later.toString())).andReturn();
        token=json.readTree(response.getResponse().getContentAsString()).path("token").asText();
        mockMvc.perform(post("/rules/drafts/delete").session(session).with(auth).with(csrfToken()).param("account",""+account).param("confirmation",token)).andExpect(redirectedUrl("/rules"));
        assertThat(rules.get(account,later).state()).isEqualTo("REMOVED");
        verifyNoInteractions(gmailRules,graph);
    }

    @Test void gmailDomainRuleRoundTripsAndPreviewsChangingAddressesWithExceptionsAndProtections() throws Exception {
        String[][] messages={{"domain-1","prefix-one@relay.example.test","Weekly update"},{"domain-2","prefix-two@relay.example.test","Weekly invoice"},{"domain-3","alerts@fixed.example.test","Weekly update"},{"domain-other","outside@other.example.test","Weekly update"}};
        for(var m:messages)jdbc.update("INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,dashboard_from_address,dashboard_unit_kind,dashboard_unit_key,subject_header,labels,label_evidence_known,received_at) VALUES(?,'google',?,?,?,'ADDRESS',?,?,ARRAY['INBOX'],true,now())",account,m[0],m[1],m[1],m[1],m[2]);
        doReturn(new GmailRuleProvider.Matches(Set.of("domain-1","domain-3"),Set.of("domain-1","domain-2","domain-3"))).when(gmailRules).matching(any(),any());
        doReturn(List.of(new GmailRuleProvider.Destination("Label_domain","Domain mail"))).when(gmailRules).destinations(any());
        doAnswer(i->gmailRules.useDestination(i.getArgument(1),"Label_domain")).when(gmailRules).prepare(any(),any());
        doReturn(0L).when(gmailRules).destinationCount(any(),anyString());
        var response=mockMvc.perform(post("/rules/save").with(authenticatedAs(EMAIL)).with(csrfToken())
            .param("account",""+account).param("name","Domain mail").param("senders","@relay.example.test, alerts@fixed.example.test")
            .param("subject","Weekly").param("exceptSubject","invoice").param("action","ORGANIZE").param("destination","Domain mail").param("destinationId","Label_domain").param("destinationMode","EXISTING"))
            .andExpect(status().is3xxRedirection()).andReturn().getResponse();
        UUID id=UUID.fromString(response.getRedirectedUrl().split("/rules/")[1].split("\\?")[0]);
        assertThat(rules.get(account,id).definition().hasDomains()).isTrue();
        var review=rules.preview(account,id);assertThat(((Map<?,?>)review.get("counts")).get("matched")).isEqualTo(2L);assertThat(((Map<?,?>)review.get("counts")).get("excluded")).isEqualTo(1L);
        jdbc.update("INSERT INTO protected_senders(account_id,sender_address,source) VALUES(?,?,'USER')",account,"important@relay.example.test");
        assertThat((List<String>)rules.preview(account,id).get("protected")).contains("important@relay.example.test");
        assertThatThrownBy(()->rules.enable(account,id,(UUID)review.get("id"),true)).hasMessageContaining("protected sender");assertThat(creates).isZero();
        jdbc.update("DELETE FROM protected_senders WHERE account_id=?",account);
        rules.enable(account,id,preview(account,id),true);
        var nativeRule=nativeGmail.getFirst().raw();assertThat(nativeRule.path("criteria").path("from").asText()).isEqualTo("{@relay.example.test alerts@fixed.example.test}");
        assertThat(nativeRule.path("criteria").path("negatedQuery").asText()).contains("invoice");
        assertThat(existingMail.matches(account,id,"inbox").total()).isEqualTo(2);
        assertThat(existingMail.matches(account,id,"inbox",List.of("alerts@fixed.example.test")).total()).isEqualTo(1);
        jdbc.update("INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,dashboard_from_address,dashboard_unit_kind,dashboard_unit_key,subject_header,labels,label_evidence_known,received_at) VALUES(?,'google','domain-new','prefix-new@relay.example.test','prefix-new@relay.example.test','ADDRESS','prefix-new@relay.example.test','Weekly update',ARRAY['INBOX'],true,now())",account);
        doReturn(new GmailRuleProvider.Matches(Set.of("domain-1","domain-3","domain-new"),Set.of("domain-1","domain-2","domain-3","domain-new"))).when(gmailRules).matching(any(),any());
        assertThat(existingMail.matches(account,id,"inbox").total()).isEqualTo(3);assertThat(creates).isEqualTo(1);
        rules.manage(account,id,"pause");
        mockMvc.perform(get("/rules/"+id).with(authenticatedAs(EMAIL))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("@relay.example.test")));
        var add=rules.reviewSenders(account,id,List.of("added@other.example.test"));rules.addSenders(account,id,(UUID)add.get("id"),true);
        assertThat(rules.get(account,id).definition().senders()).contains("@relay.example.test");assertThat(rules.get(account,id).state()).isEqualTo("PAUSED");
        var domainOnly=new RuleDefinition("Domain only",List.of("relay.example.test"),"","","READ");
        assertThat(domainOnly.senders()).containsExactly("@relay.example.test");assertThat(gmailRules.compile(accounts.findById(account).orElseThrow(),domainOnly,List.of()).path("criteria").path("from").asText()).isEqualTo("@relay.example.test");
        assertThatThrownBy(()->new RuleDefinition("Unsafe",List.of("@relay.example.test OR from:anywhere"),"","","READ")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void outlookDomainAndAddressUseOneOrListAndPreserveDomainsThroughPauseAddAndResume() throws Exception {
        long outlook=userAccounts.createAccount("domain-outlook@example.test","microsoft");
        var a=accounts.findById(outlook).orElseThrow();a.setScopes(List.of("MailboxSettings.ReadWrite"));accounts.save(a);
        String[][] messages={{"d-1","changed-prefix@relay.example.test","Weekly update"},{"d-2","alerts@fixed.example.test","Weekly update"},{"d-3","other@relay.example.test","Weekly invoice"},{"d-4","outside@elsewhere.example.test","Weekly update"}};
        for(var m:messages)jdbc.update("INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,subject_header,received_at) VALUES(?,'microsoft',?,?,?,now())",outlook,m[0],m[1],m[2]);
        var inventory=new LinkedHashMap<String,JsonNode>();
        when(graph.request(any(),any(),anyString(),nullable(Object.class))).thenAnswer(i->{
            HttpMethod method=i.getArgument(1);Object payload=i.getArgument(3);
            if(method==HttpMethod.GET){var page=json.createObjectNode();var values=page.putArray("value");inventory.values().forEach(values::add);return page;}
            if(method==HttpMethod.PATCH){var changes=(Map<?,?>)payload;var nativeRule=(ObjectNode)inventory.get("domain-rule");
                if(changes.containsKey("isEnabled"))nativeRule.put("isEnabled",(Boolean)changes.get("isEnabled"));
                if(changes.containsKey("conditions"))nativeRule.set("conditions",((JsonNode)changes.get("conditions")).deepCopy());return null;}
            var created=((JsonNode)payload).deepCopy();((ObjectNode)created).put("id","domain-rule");inventory.put("domain-rule",created);return created;
        });
        var d=new RuleDefinition("Domain OR fixed address",List.of("@relay.example.test","alerts@fixed.example.test"),"Weekly","invoice","TRASH");UUID id=rules.save(outlook,null,0,d);
        var review=rules.preview(outlook,id);assertThat(((Map<?,?>)review.get("counts")).get("matched")).isEqualTo(2L);assertThat(((Map<?,?>)review.get("counts")).get("excluded")).isEqualTo(1L);
        assertThat(review.get("matchingNote").toString()).contains("sender address includes","alternatives");
        rules.enable(outlook,id,(UUID)review.get("id"),true);
        var nativeRule=inventory.get("domain-rule");assertThat(nativeRule.path("conditions").path("senderContains")).isEqualTo(json.readTree("[\"@relay.example.test\",\"alerts@fixed.example.test\"]"));assertThat(nativeRule.path("conditions").path("fromAddresses").isEmpty()).isTrue();
        assertThat(new OutlookRuleProvider(graph,json).list(a).getFirst().compatible().senders()).containsExactlyElementsOf(d.senders());
        var originalActions=nativeRule.path("actions").deepCopy();int sequence=nativeRule.path("sequence").asInt();
        rules.manage(outlook,id,"pause");var add=rules.reviewSenders(outlook,id,List.of("added@other.example.test"));rules.addSenders(outlook,id,(UUID)add.get("id"),true);
        assertThat(rules.get(outlook,id).state()).isEqualTo("PAUSED");assertThat(rules.get(outlook,id).definition().senders()).contains("@relay.example.test");
        assertThat(inventory.get("domain-rule").path("actions")).isEqualTo(originalActions);assertThat(inventory.get("domain-rule").path("sequence").asInt()).isEqualTo(sequence);
        rules.enable(outlook,id,preview(outlook,id),true);assertThat(rules.get(outlook,id).state()).isEqualTo("ACTIVE");assertThat(inventory).hasSize(1);
        // Native AND conditions must never be adopted as the editor's OR list.
        ((ObjectNode)inventory.get("domain-rule").path("conditions")).putArray("fromAddresses").addObject().putObject("emailAddress").put("address","someone@relay.example.test");
        assertThat(new OutlookRuleProvider(graph,json).list(a).getFirst().compatible()).isNull();
    }

    @Test void overlapIdentifiesOnlySharedAddressesAndBatchChecksFullFilterWithoutMovingMail() throws Exception {
        var senders=new ArrayList<String>();senders.add(SENDER);senders.add("not-"+SENDER);
        java.util.stream.IntStream.range(0,40).mapToObj(i->"selected"+i+"@example.test").forEach(senders::add);
        var raw=json.readTree("{\"criteria\":{\"from\":\"offers@example.test\",\"subject\":\"invoice\",\"negatedQuery\":\"subject:overdue\"},\"action\":{\"addLabelIds\":[\"TRASH\"]}}");
        nativeGmail.add(new RuleProvider.NativeRule("existing-trash","Gmail filter",true,false,raw,null,""));
        var d=new RuleDefinition("Forty-two selected senders",senders,"","","READ");UUID id=rules.save(account,null,0,d);
        var response=mockMvc.perform(get("/rules/"+id+"?preview=true&inbox=true").with(authenticatedAs(EMAIL)))
            .andExpect(status().isOk()).andReturn();
        assertThat(response.getResponse().getContentAsString()).contains("1 of 42 selected addresses",SENDER,"Subject phrase","invoice","Must not match this search","subject:overdue","not a full-rule match");
        var review=(Map<?,?>)response.getModelAndView().getModel().get("preview");
        var overlaps=(List<AutomationRuleService.Interaction>)review.get("interactions");
        assertThat(overlaps).hasSize(1);assertThat(overlaps.getFirst().senders()).extracting(RuleOverlap.Sender::address).containsExactly(SENDER);
        doReturn(Set.of("mail-1")).when(gmailRules).matchingInbox(any(),any(),anyList());
        jdbc.update("UPDATE message_metadata SET subject_header='invoice' WHERE account_id=? AND provider_message_id='mail-1'",account);
        var check=mockMvc.perform(post("/rules/"+id+"/interaction-check").with(authenticatedAs(EMAIL)).with(csrfToken())
            .param("account",""+account).param("preview",review.get("id").toString()).param("remote","existing-trash").param("fingerprint",rules.fingerprint(raw)).param("inbox","true"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var result=json.readTree(check);assertThat(result.path("selectedAddresses").asInt()).isEqualTo(42);
        assertThat(result.path("currentInboxMatches").asInt()).isEqualTo(1);assertThat(result.path("senders")).hasSize(42);
        assertThat(result.path("senders").get(0).path("address").asText()).isEqualTo(SENDER);
        assertThat(result.path("explanation").asText()).contains("not that this filter ran","cause is not established");
        assertThat(creates).isZero();assertThat(nativeGmail).hasSize(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM cleanup_jobs",Integer.class)).isZero();
        verify(gmailRules).matchingInbox(any(),eq(raw),argThat(v->v.size()==42));
        mockMvc.perform(post("/rules/"+id+"/interaction-check").with(authenticatedAs(EMAIL)).with(csrfToken())
            .param("account",""+account).param("preview",review.get("id").toString()).param("remote","existing-trash").param("fingerprint","stale").param("inbox","true"))
            .andExpect(status().isConflict());
        doThrow(new IllegalStateException("Simulated search failure")).when(gmailRules).matchingInbox(any(),any(),anyList());
        mockMvc.perform(post("/rules/"+id+"/interaction-check").with(authenticatedAs(EMAIL)).with(csrfToken())
            .param("account",""+account).param("preview",review.get("id").toString()).param("remote","existing-trash").param("fingerprint",rules.fingerprint(raw)).param("inbox","true"))
            .andExpect(status().isConflict()).andExpect(content().string(org.hamcrest.Matchers.containsString("No partial result is treated as verified")));
        long foreign=userAccounts.createAccount("overlap-foreign@example.test");
        assertThatThrownBy(()->rules.checkInteraction(foreign,id,(UUID)review.get("id"),"existing-trash",rules.fingerprint(raw))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var complex=raw.deepCopy();((ObjectNode)complex.path("criteria")).put("from","-offers@example.test");
        var assessment=RuleOverlap.assess(d,new RuleProvider.NativeRule("complex","Gmail filter",true,false,complex,null,""));
        assertThat(assessment.uncertain()).isTrue();assertThat(assessment.senders()).isEmpty();
    }

    @Test void newRuleFromFortyTwoSendersRetainsEveryAddressThroughHttpPreviewAndProviderPayloads() throws Exception {
        var senders=java.util.stream.IntStream.range(0,42).mapToObj(i->"selected"+i+"@example.test").sorted().toList();
        var response=mockMvc.perform(post("/rules/save").with(authenticatedAs(EMAIL)).with(csrfToken())
            .param("account",""+account).param("inbox","true").param("name","Selected senders").param("senders",String.join(", ",senders)).param("action","READ"))
            .andExpect(status().is3xxRedirection()).andReturn().getResponse();
        UUID id=UUID.fromString(response.getRedirectedUrl().split("/rules/")[1].split("\\?")[0]);
        assertThat(rules.get(account,id).definition().senders()).containsExactlyElementsOf(senders);
        var review=mockMvc.perform(get(response.getRedirectedUrl()).with(authenticatedAs(EMAIL))).andExpect(status().isOk()).andReturn();
        var preview=(Map<?,?>)review.getModelAndView().getModel().get("preview");
        assertThat(creates).isZero();rules.enable(account,id,(UUID)preview.get("id"),true);
        assertThat(rules.get(account,id).state()).isEqualTo("ACTIVE");
        assertThat(nativeGmail.getFirst().raw().path("criteria").path("from").asText()).isEqualTo("{"+String.join(" ",senders)+"}");
        var outlook=new OutlookRuleProvider(graph,json).compile(accounts.findById(account).orElseThrow(),rules.get(account,id).definition(),List.of());
        assertThat(outlook.path("conditions").path("fromAddresses")).hasSize(42);
    }

    @Test void multipleSubjectPhrasesKeepSenderScopeAndAnyExceptionWinsAcrossPreviewAndNativePayloads() throws Exception {
        long outlook=userAccounts.createAccount("phrases@example.test","microsoft");
        var a=accounts.findById(outlook).orElseThrow();a.setScopes(List.of("MailboxSettings.ReadWrite"));accounts.save(a);
        when(graph.request(eq(a),eq(HttpMethod.GET),anyString(),isNull())).thenReturn(json.readTree("{\"value\":[]}"));
        // Repository may return a separate account entity, so match the account ID.
        when(graph.request(argThat(v->v.getId().equals(outlook)),eq(HttpMethod.GET),anyString(),isNull())).thenReturn(json.readTree("{\"value\":[]}"));
        String[] subjects={"Weekly offers","Monthly sale","Weekly offers receipt","Monthly sale invoice","Unrelated"};
        for(int i=0;i<subjects.length;i++)jdbc.update("INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,subject_header,received_at) VALUES(?,'microsoft',?,?,?,now())",outlook,"phrase-"+i,SENDER,subjects[i]);
        jdbc.update("INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,subject_header,received_at) VALUES(?,'microsoft','other-sender','different@example.test','Weekly offers',now())",outlook);
        var d=new RuleDefinition("Offers and sales",List.of(SENDER),"Weekly offers\nMonthly sale","receipt\ninvoice","READ");
        UUID id=rules.save(outlook,null,0,d);var preview=rules.preview(outlook,id);
        assertThat(((Map<?,?>)preview.get("counts")).get("matched")).isEqualTo(2L);
        assertThat(((Map<?,?>)preview.get("counts")).get("excluded")).isEqualTo(2L);
        assertThat(((Map<?,?>)preview.get("counts")).get("other")).isEqualTo(1L);
        var nativeRule=new OutlookRuleProvider(graph,json).compile(a,d,List.of());
        assertThat(nativeRule.path("conditions").path("subjectContains")).isEqualTo(json.readTree("[\"Weekly offers\",\"Monthly sale\"]"));
        assertThat(nativeRule.path("exceptions").path("subjectContains")).hasSize(2);
        var gmail=gmailRules.compile(accounts.findById(account).orElseThrow(),d,List.of());
        assertThat(gmail.path("criteria").path("query").asText()).isEqualTo("{subject:\"Weekly offers\" subject:\"Monthly sale\"}");
        assertThat(gmail.path("criteria").path("negatedQuery").asText()).isEqualTo("{subject:\"receipt\" subject:\"invoice\"}");
        assertThat(rules.get(outlook,id).definition().subjects()).containsExactly("Weekly offers","Monthly sale");
        verify(gmailRules,never()).create(any(),any());
    }

    @Test void existingGmailDeletionRuleCanBeConvertedThroughEditorAndRetainsResultsAfterMailMoves() throws Exception {
        mockMvc.perform(get("/rules/new?action=TRASH").with(authenticatedAs(EMAIL)))
            .andExpect(status().isOk()).andExpect(model().attribute("initialAction","ORGANIZE"));
        doAnswer(i->gmailRules.useDestination(i.getArgument(1),"Label_results")).when(gmailRules).prepare(any(),any());
        doReturn(12L).when(gmailRules).destinationCount(any(),eq("Label_results"));
        var original=json.readTree("{\"id\":\"existing-trash\",\"criteria\":{\"from\":\"offers@example.test\"},\"action\":{\"addLabelIds\":[\"TRASH\"]}}");
        nativeGmail.add(new RuleProvider.NativeRule("existing-trash","Gmail filter",true,false,original,
            new RuleDefinition("Imported deletion rule",List.of(SENDER),"","","TRASH"),""));
        UUID adopted=rules.adopt(account,"existing-trash",rules.fingerprint(original));
        mockMvc.perform(get("/rules/"+adopted).with(authenticatedAs(EMAIL))).andExpect(status().isOk());
        rules.manage(account,adopted,"pause");assertThat(nativeGmail).isEmpty();
        var saved=mockMvc.perform(post("/rules/save").with(authenticatedAs(EMAIL)).with(csrfToken())
            .param("account",""+account).param("id",adopted.toString()).param("version",""+rules.get(account,adopted).version())
            .param("name","Organize offers").param("senders",SENDER)
            .param("action","ORGANIZE").param("destination","TidyMail - Offers"))
            .andExpect(status().is3xxRedirection()).andReturn();
        UUID id=UUID.fromString(saved.getResponse().getRedirectedUrl().split("/")[2].split("\\?")[0]);
        UUID preview=preview(account,id);
        assertThat(creates).isZero();verify(gmailRules,never()).prepare(any(),any());
        rules.enable(account,id,preview,true);
        assertThat(rules.get(account,id).state()).isEqualTo("ACTIVE");
        assertThat(nativeGmail.getFirst().raw().path("action").path("addLabelIds").get(0).asText()).isEqualTo("Label_results");
        assertThat(nativeGmail.getFirst().raw().path("action").has("removeLabelIds")).isFalse();
        // One new message, one historical message manually labeled: only new mail is observed.
        jdbc.update("INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,labels,received_at) VALUES(?,'google','new-1',?,ARRAY['Label_results'],now()+interval '1 second')",account,SENDER);
        jdbc.update("UPDATE message_metadata SET labels=ARRAY['Label_results'],received_at=now()-interval '1 day' WHERE account_id=? AND provider_message_id='mail-1'",account);
        assertThat(rules.results(account,id).get("observed")).isEqualTo(1L);
        assertThat(rules.results(account,id).get("week_count")).isEqualTo(1L);
        assertThat(rules.results(account,id).get("baseline_count")).isEqualTo(12L);
        jdbc.update("UPDATE message_metadata SET labels=ARRAY['INBOX'] WHERE account_id=?",account);
        assertThat(rules.results(account,id).get("observed")).isEqualTo(1L);
        mockMvc.perform(get("/rules/"+id).with(authenticatedAs(EMAIL)))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Gmail label results")));
        doThrow(new IllegalStateException("Provider unavailable")).when(gmailRules).destinationCount(any(),anyString());
        rules.refresh(account);
        assertThat(rules.get(account,id).state()).isEqualTo("ACTIVE");
        assertThat(rules.results(account,id).get("count_error")).isEqualTo(true);
        assertThat(rules.results(account,id).get("current_count")).isEqualTo(12L);
        doReturn(13L).when(gmailRules).destinationCount(any(),anyString());
        mockMvc.perform(post("/rules/"+id+"/results/refresh").with(authenticatedAs(EMAIL)).with(csrfToken())
            .param("account",""+account).param("inbox","true")).andExpect(redirectedUrl("/rules/"+id+"?inbox=true&result=true"));
        assertThat(rules.results(account,id).get("current_count")).isEqualTo(13L);
        rules.manage(account,id,"pause");
        rules.enable(account,id,preview(account,id),true);
        assertThat(rules.results(account,id).get("baseline_count")).isEqualTo(12L);
        assertThat(rules.results(account,id).get("observed")).isEqualTo(1L);
        assertThat(nativeGmail.getFirst().raw().path("action").path("addLabelIds").get(0).asText()).isEqualTo("Label_results");
        long foreign=userAccounts.createAccount("foreign-results@example.test");
        assertThatThrownBy(()->rules.results(foreign,id)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void pausedNewLabelRuleReopensWithResolvedDestinationAndCanSaveSkipInbox() throws Exception {
        var definition = new RuleDefinition("Synthetic social rule",List.of(SENDER),"","","ORGANIZE","Offers",false,"","NEW");
        UUID id=rules.save(account,null,0,definition);
        doAnswer(i->gmailRules.useDestination(i.getArgument(1),"Label_edit")).when(gmailRules).prepare(any(),any());
        doReturn(0L).when(gmailRules).destinationCount(any(),anyString());
        rules.enable(account,id,preview(account,id),true);
        rules.manage(account,id,"pause");
        doReturn(List.of(new GmailRuleProvider.Destination("Label_edit","Offers"))).when(gmailRules).destinations(any());
        String html=mockMvc.perform(get("/rules/"+id).with(authenticatedAs(EMAIL))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("data-initial-mode=\"EXISTING\"","data-initial-id=\"Label_edit\"");
        mockMvc.perform(post("/rules/save").with(authenticatedAs(EMAIL)).with(csrfToken())
            .header("X-Requested-With","XMLHttpRequest").param("account",""+account).param("id",id.toString()).param("version","1")
            .param("name",definition.name()).param("senders",SENDER).param("action","ORGANIZE")
            .param("destinationMode","EXISTING").param("destinationId","Label_edit").param("destination","Offers").param("skipInbox","true"))
            .andExpect(redirectedUrl("/rules/"+id+"?preview=true&mailScope=all"));
        var saved=rules.get(account,id);
        assertThat(saved.version()).isEqualTo(2);
        assertThat(saved.definition().skipInbox()).isTrue();
        assertThat(saved.definition().destinationMode()).isEqualTo("EXISTING");
        assertThat(saved.definition().destinationId()).isEqualTo("Label_edit");
        assertThat(saved.state()).isEqualTo("DRAFT");
        assertThat(creates).isEqualTo(1);
        rules.enable(account,id,preview(account,id),true);
        assertThat(rules.get(account,id).state()).isEqualTo("ACTIVE");
        assertThat(nativeGmail).hasSize(1);
        assertThat(nativeGmail.getFirst().raw().path("action").path("removeLabelIds").toString()).contains("INBOX");
    }

    @Test void failedDestinationPreparationNeverDispatchesRuleAndLostNativeResponseStillReconciles(){
        var d=new RuleDefinition("Organize offers",List.of(SENDER),"","","ORGANIZE","TidyMail - Offers",true);
        UUID id=rules.save(account,null,0,d);
        doThrow(new IllegalStateException("Destination response lost")).when(gmailRules).prepare(any(),any());
        rules.enable(account,id,preview(account,id),true);
        assertThat(creates).isZero();assertThat(rules.get(account,id).pending()).isEqualTo("CREATE");
        rules.refresh(account);assertThat(creates).isZero();
        UUID second=rules.save(account,null,0,d);
        doAnswer(i->gmailRules.useDestination(i.getArgument(1),"Label_results")).when(gmailRules).prepare(any(),any());
        doReturn(0L).when(gmailRules).destinationCount(any(),anyString());uncertain=true;
        rules.enable(account,second,preview(account,second),true);
        assertThat(creates).isEqualTo(1);
        assertThat(rules.get(account,second).pending()).isEqualTo("CREATE");
        rules.refresh(account);assertThat(rules.get(account,second).state()).isEqualTo("ACTIVE");
        assertThat(creates).isEqualTo(1);
        assertThat(nativeGmail.getFirst().raw().path("action").path("removeLabelIds").get(0).asText()).isEqualTo("INBOX");
    }

    @Test void lostCreationResponseRecoversWithoutDuplicatesAndExternalChangesAreNotOverwritten(){
        UUID id=draft(account);uncertain=true;
        rules.enable(account,id,preview(account,id),true);
        assertThat(rules.get(account,id).pending()).isEqualTo("CREATE");assertThat(creates).isEqualTo(1);
        rules.refresh(account);assertThat(rules.get(account,id).state()).isEqualTo("ACTIVE");assertThat(creates).isEqualTo(1);
        ((ObjectNode)nativeGmail.getFirst().raw().path("criteria")).put("from","someone-else@example.test");
        rules.refresh(account);assertThat(rules.get(account,id).state()).isEqualTo("ATTENTION");assertThat(creates).isEqualTo(1);
        long foreign=userAccounts.createAccount("foreign@example.test");
        assertThatThrownBy(()->rules.get(foreign,id)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void previewUsesAvailableEvidenceAndChangedProtectionsBlockActivation() throws Exception {
        jdbc.update("INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,subject_header,labels,received_at) VALUES(?,'google','mail-2',?,'Weekly receipt',ARRAY['INBOX'],now())",account,SENDER);
        jdbc.update("INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,subject_header,labels,received_at) VALUES(?,'google','mail-3',?,'Other mail',ARRAY['INBOX'],now())",account,SENDER);
        doReturn(new GmailRuleProvider.Matches(Set.of("mail-1"),Set.of("mail-1","mail-2"))).when(gmailRules).matching(any(),any());
        var d=new RuleDefinition("Weekly offers",List.of(SENDER),"Weekly","receipt","READ_ARCHIVE");
        UUID id=rules.save(account,null,0,d);var p=rules.preview(account,id);
        var counts=(Map<?,?>)p.get("counts");assertThat(((Number)counts.get("matched")).longValue()).isEqualTo(1);
        assertThat(((Number)counts.get("excluded")).longValue()).isEqualTo(1);assertThat(((Number)counts.get("other")).longValue()).isEqualTo(1);
        jdbc.update("INSERT INTO protected_senders(account_id,sender_address,source) VALUES(?,?,'USER')",account,SENDER);
        assertThatThrownBy(()->rules.enable(account,id,(UUID)p.get("id"),true)).hasMessageContaining("protected");assertThat(creates).isZero();
        mockMvc.perform(get("/rules/"+id+"?preview=true").with(authenticatedAs(EMAIL))).andExpect(status().isOk());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"lost-response","rejected","unavailable-readback","not-applied"})
    void outlookSenderUpdateRecoversWithoutSendingAnotherMutation(String outcome) throws Exception {
        long outlook=userAccounts.createAccount("outlook-recovery@example.test","microsoft");
        var a=accounts.findById(outlook).orElseThrow();a.setScopes(List.of("MailboxSettings.ReadWrite"));accounts.save(a);
        var inventory=new LinkedHashMap<String,JsonNode>();var writes=new int[]{0};var failRead=new boolean[]{false};
        when(graph.request(any(),any(),anyString(),nullable(Object.class))).thenAnswer(i->{
            HttpMethod method=i.getArgument(1);String path=i.getArgument(2);Object payload=i.getArgument(3);
            if(method==HttpMethod.GET){
                if(failRead[0])throw new com.tidymail.mail.CleanupProviderException(503,null);
                var page=json.createObjectNode();var values=page.putArray("value");
                if(path.contains("messageRules"))inventory.values().forEach(n->values.add(n.deepCopy()));return page;
            }
            if(method==HttpMethod.PATCH){
                writes[0]++;
                if(outcome.equals("rejected"))throw new com.tidymail.mail.CleanupProviderException(400,null);
                if(outcome.equals("not-applied"))throw new com.tidymail.mail.CleanupProviderException(0,null);
                var changes=(Map<?,?>)payload;
                ((ObjectNode)inventory.get("native-recovery")).set("conditions",((JsonNode)changes.get("conditions")).deepCopy());
                failRead[0]=outcome.equals("unavailable-readback");
                throw new com.tidymail.mail.CleanupProviderException(0,null);
            }
            ObjectNode created=((JsonNode)payload).deepCopy();created.put("id","native-recovery");inventory.put("native-recovery",created);return created;
        });
        UUID id=rules.save(outlook,null,0,new RuleDefinition("Synthetic recovery",List.of(SENDER),"","","READ"));
        rules.enable(outlook,id,preview(outlook,id),true);
        var authorities=List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var auth=new OAuth2AuthenticationToken(new DefaultOAuth2User(authorities,Map.of("sub",a.getProviderAccountId(),"email",a.getEmail()),"sub"),authorities,"microsoft");
        // Follow the actual picker -> review -> commit HTTP path, with three senders total.
        String senders="billing@example.test,updates@example.test";
        mockMvc.perform(get("/rules/add-senders").param("account",""+outlook).param("senders",senders).with(authentication(auth)))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Review addition")));
        var reviewed=mockMvc.perform(post("/rules/"+id+"/senders/review").param("account",""+outlook).param("senders",senders).param("inbox","true").with(authentication(auth)).with(csrfToken()))
            .andExpect(status().isOk()).andReturn();
        UUID addition=(UUID)((Map<?,?>)reviewed.getModelAndView().getModel().get("addition")).get("id");
        var response=mockMvc.perform(post("/rules/"+id+"/senders/add").param("account",""+outlook).param("preview",addition.toString()).param("inbox","true").with(authentication(auth)).with(csrfToken()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(writes[0]).isEqualTo(1);
        if(outcome.equals("rejected")){
            assertThat(rules.get(outlook,id).pending()).isNull();
            assertThat(rules.get(outlook,id).state()).isEqualTo("ACTIVE");
            assertThat(rules.get(outlook,id).definition().senders()).containsExactly(SENDER);
            assertThat(response).contains("HTTP 400","data-addition-saved=\"false\"").doesNotContain("<h2>Sender added to rule</h2>");
        }else if(outcome.equals("unavailable-readback")||outcome.equals("not-applied")){
            assertThat(rules.get(outlook,id).pending()).isEqualTo("UPDATE_SENDERS");
            assertThat(response).contains("Check rule status");
            mockMvc.perform(get("/rules/"+id).with(authentication(auth))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("The rule update needs checking")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Pause it to change its action"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("name=\"senders\""))));
            failRead[0]=false;
            if(outcome.equals("not-applied"))jdbc.update("UPDATE automation_rules SET updated_at=now()-interval '3 minutes' WHERE id=?",id);
            mockMvc.perform(post("/rules/"+id+"/check").param("account",""+outlook).param("inbox","true").with(authentication(auth)).with(csrfToken()))
                .andExpect(status().isOk()).andExpect(view().name("rule-add-senders"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(outcome.equals("not-applied")?"Review addition again":"Sender added to rule")));
            assertThat(rules.get(outlook,id).pending()).isNull();assertThat(rules.get(outlook,id).definition().senders()).hasSize(outcome.equals("not-applied")?1:3);
            assertThat(writes[0]).isEqualTo(1);
        }else{
            assertThat(rules.get(outlook,id).state()).isEqualTo("ACTIVE");assertThat(rules.get(outlook,id).pending()).isNull();
            assertThat(rules.get(outlook,id).definition().senders()).hasSize(3);
            assertThat(response).contains("data-addition-saved=\"true\"");
        }
    }

    @Test void outlookOrganizeUsesNativeFolderAndReportsObservedMailWithoutChangingOldMessages(){
        long outlook=userAccounts.createAccount("outlook-organize@example.test","microsoft");
        var a=accounts.findById(outlook).orElseThrow();a.setScopes(List.of("MailboxSettings.ReadWrite"));accounts.save(a);
        var inventory=new LinkedHashMap<String,JsonNode>();
        when(graph.request(any(),any(),anyString(),nullable(Object.class))).thenAnswer(i->{
            HttpMethod method=i.getArgument(1);String path=i.getArgument(2);Object payload=i.getArgument(3);
            if(path.equals("/v1.0/me/mailFolders")&&method==HttpMethod.POST)return json.createObjectNode().put("id","offers-folder");
            if(path.contains("offers-folder?$select=totalItemCount"))return json.createObjectNode().put("totalItemCount",3);
            if(path.contains("mailFolders/archive"))return json.createObjectNode().put("id","archive-folder");
            if(method==HttpMethod.GET){var page=json.createObjectNode();var values=page.putArray("value");
                if(path.contains("messageRules"))inventory.values().forEach(values::add);return page;}
            if(method==HttpMethod.PATCH){var changes=(Map<?,?>)payload;var current=(ObjectNode)inventory.get("native-organize");
                if(changes.containsKey("isEnabled"))current.put("isEnabled",(Boolean)changes.get("isEnabled"));
                if(changes.containsKey("conditions"))current.set("conditions",((JsonNode)changes.get("conditions")).deepCopy());return null;}
            JsonNode created=((JsonNode)payload).deepCopy();((ObjectNode)created).put("id","native-organize");inventory.put("native-organize",created);return created;
        });
        UUID id=rules.save(outlook,null,0,new RuleDefinition("Organize offers",List.of(SENDER),"","","ORGANIZE","TidyMail - Offers",false));
        UUID review=preview(outlook,id);
        verify(graph,never()).request(any(),eq(HttpMethod.POST),anyString(),any());
        rules.enable(outlook,id,review,true);
        assertThat(rules.get(outlook,id).state()).isEqualTo("ACTIVE");
        assertThat(inventory.get("native-organize").path("actions").path("moveToFolder").asText()).isEqualTo("offers-folder");
        jdbc.update("INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,outlook_folder_id,received_at) VALUES(?,'microsoft','outlook-new',?,'offers-folder',now()+interval '1 second')",outlook,SENDER);
        assertThat(rules.results(outlook,id).get("observed")).isEqualTo(1L);
        assertThat(rules.results(outlook,id).get("baseline_count")).isEqualTo(3L);
        var beforeUpdate=inventory.get("native-organize").deepCopy();
        var addition=rules.reviewSenders(outlook,id,List.of("billing@example.test"));rules.addSenders(outlook,id,(UUID)addition.get("id"),true);
        assertThat(rules.get(outlook,id).state()).isEqualTo("ACTIVE");
        assertThat(inventory.get("native-organize").path("actions")).isEqualTo(beforeUpdate.path("actions"));
        assertThat(inventory.get("native-organize").path("sequence")).isEqualTo(beforeUpdate.path("sequence"));
        rules.manage(outlook,id,"pause");
        var pausedAddition=rules.reviewSenders(outlook,id,List.of("paused@example.test"));rules.addSenders(outlook,id,(UUID)pausedAddition.get("id"),true);
        assertThat(rules.get(outlook,id).state()).isEqualTo("PAUSED");
        assertThat(inventory.get("native-organize").path("isEnabled").asBoolean()).isFalse();
        rules.enable(outlook,id,preview(outlook,id),true);
        assertThat(inventory).hasSize(1);assertThat(rules.get(outlook,id).state()).isEqualTo("ACTIVE");
    }

    @Test void existingOutlookReadArchiveRuleCanBeAdoptedPausedEditedAndReenabledWithoutLosingNativeSettings() throws Exception {
        mockMvc.perform(get("/accounts/connect/microsoft?rules=true").with(authenticatedAs(EMAIL)))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/rules/connect/microsoft?rules=true").with(authenticatedAs(EMAIL)))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/oauth2/authorization/microsoft?rules=true"));
        mockMvc.perform(get("/oauth2/authorization/microsoft?rules=true")).andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location",org.hamcrest.Matchers.containsString("MailboxSettings.ReadWrite")));
        mockMvc.perform(get("/oauth2/authorization/microsoft")).andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location",org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("MailboxSettings.ReadWrite"))));
        long outlook=userAccounts.createAccount("outlook@example.test","microsoft");
        var a=accounts.findById(outlook).orElseThrow();a.setScopes(List.of("MailboxSettings.ReadWrite"));accounts.save(a);
        var inventory=new LinkedHashMap<String,JsonNode>();
        JsonNode existing=json.readTree("""
            {"id":"existing","displayName":"Existing offers","isEnabled":true,"isReadOnly":false,"sequence":1,
            "conditions":{"fromAddresses":[{"emailAddress":{"address":"offers@example.test","name":"Offers"}}]},
            "actions":{"moveToFolder":"archive-folder","markAsRead":true,"stopProcessingRules":false}}
            """);inventory.put("existing",existing);
        when(graph.request(any(),any(),anyString(),nullable(Object.class))).thenAnswer(i->{
            HttpMethod method=i.getArgument(1);String path=i.getArgument(2);Object payload=i.getArgument(3);
            if(path.contains("mailFolders/archive"))return json.createObjectNode().put("id","archive-folder");
            if(method==HttpMethod.GET){var page=json.createObjectNode();var values=page.putArray("value");inventory.values().forEach(values::add);return page;}
            if(method==HttpMethod.DELETE){inventory.remove("existing");return null;}
            if(method==HttpMethod.PATCH){String key=path.substring(path.lastIndexOf('/')+1);((ObjectNode)inventory.get(key)).put("isEnabled",(Boolean)((Map<?,?>)payload).get("isEnabled"));return null;}
            JsonNode created=((JsonNode)payload).deepCopy();((ObjectNode)created).put("id","replacement");inventory.put("replacement",created);return created;
        });
        UUID id=rules.adopt(outlook,"existing",rules.fingerprint(existing));assertThat(inventory).hasSize(1);
        rules.manage(outlook,id,"pause");assertThat(existing.path("isEnabled").asBoolean()).isFalse();
        UUID replacement=rules.save(outlook,id,rules.get(outlook,id).version(),definition());assertThat(inventory).isEmpty();
        rules.enable(outlook,replacement,preview(outlook,replacement),true);
        assertThat(rules.get(outlook,replacement).state()).isEqualTo("ACTIVE");
        assertThat(inventory.get("replacement").path("actions").path("moveToFolder").asText()).isEqualTo("archive-folder");
        assertThat(inventory.get("replacement").path("actions").path("markAsRead").asBoolean()).isTrue();
        var authorities=List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var auth=new OAuth2AuthenticationToken(new DefaultOAuth2User(authorities,Map.of("sub",a.getProviderAccountId(),"email",a.getEmail()),"sub"),authorities,"microsoft");
        mockMvc.perform(get("/rules").with(authentication(auth))).andExpect(status().isOk());
    }
}
