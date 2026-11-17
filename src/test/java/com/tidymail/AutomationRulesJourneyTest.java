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
