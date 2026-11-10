package com.tidymail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tidymail.dto.DashboardFilter;
import com.tidymail.repository.DashboardRepository;
import com.tidymail.service.*;
import com.tidymail.support.PostgresJourneyTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

class DashboardJourneyTest extends PostgresJourneyTest {
    static final String OWNER = "dashboard@example.test";
    static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");
    @Autowired
    DashboardService dashboard;
    @Autowired
    DashboardRepository repository;
    @Autowired
    DashboardGroupingService grouping;
    @Autowired
    DashboardPreviewService previews;
    @Autowired
    DashboardJobService jobs;
    @Autowired
    ActionScopeService scopes;
    @Autowired
    ObjectMapper json;
    long account;
    String provider;

    @BeforeEach
    void account() {
        provider = "google";
        account = userAccounts.createAccount(OWNER);
        jdbc.update("DELETE FROM dashboard_domain_registry WHERE kind='DEDICATED'");
    }

    static DashboardFilter filter(String scope, String search) {
        return new DashboardFilter(scope, search, "", "", "UTC", 0);
    }

    @Test
    void receivedEligibilityIsPerMessageAndExclusionsOverrideInbox() {
        message("inbox", "Alice@shared.example", List.of("INBOX"), true, List.of(), true, NOW);
        message("archive", "Bob@shared.example", List.of(), true, List.of(), false, NOW);
        message("outgoing", "Me@shared.example", List.of("SENT"), true, List.of(), false, NOW);
        message("self", "Me@shared.example", List.of("SENT"), true, List.of(OWNER), false, NOW);
        message("unknown", "Unknown@shared.example", List.of(), false, List.of(), false, NOW);
        for (String excluded : List.of("SPAM", "TRASH", "DRAFT"))
            message(excluded, "Excluded@shared.example", List.of("INBOX", excluded), true, List.of(), true, NOW);
        // A matching To header alone must not turn sent mail into received mail.
        jdbc.update(
                "UPDATE message_metadata SET to_values=ARRAY[?],provider_thread_id='mixed' WHERE provider_message_id='outgoing'",
                OWNER);
        jdbc.update("UPDATE message_metadata SET provider_thread_id='mixed' WHERE provider_message_id='inbox'");
        assertThat(dashboard.page(account, filter("inbox", "")).summary().get("messages")).isEqualTo(1L);
        assertThat(dashboard.page(account, filter("all", "")).summary().get("messages")).isEqualTo(3L);
        assertThat(dashboard.page(account, filter("all", "")).coverage()).containsEntry("unknown_labels", 1L)
                .containsEntry("ambiguous_sent", 1L);
        jdbc.update("INSERT INTO dashboard_verified_aliases VALUES(?,?,?,now())", account, "alias@example.test",
                "Synthetic verified fixture");
        message("alias", "Me@shared.example", List.of("SENT"), true, List.of("alias@example.test"), false, NOW);
        assertThat(dashboard.page(account, filter("all", "")).summary().get("messages")).isEqualTo(4L);
        verifyNoInteractions(gmailProvider, outlookProvider);
    }

    @ParameterizedTest
    @ValueSource(strings = {"google", "microsoft"})
    void dashboardNamesUseSavedFromWithoutChangingGroupingOrSelection(String provider) {
        this.provider = provider;
        if (provider.equals("microsoft")) account = userAccounts.createAccount("outlook@example.test", provider);
        String list = "6261227.xt.local";
        listMessage("lending-a", "offers@lender.example", "Offers <" + list + ">");
        listMessage("lending-b", "news@lender.example", "Offers <" + list + ">");
        jdbc.update("INSERT INTO dashboard_domain_registry(domain,kind) VALUES('host.example','DEDICATED')");
        message("billing", "billing@host.example", List.of("INBOX"), true, List.of(), false, NOW);
        jdbc.update("""
            UPDATE message_metadata SET from_display_name=CASE WHEN provider_message_id='billing'
                THEN 'Hosting Billing' ELSE ' LendingTree ' END WHERE account_id=?
            """, account);
        long revision = repository.revision(account);
        UUID id = jdbc.queryForObject("SELECT public_id FROM sender_groups WHERE account_id=? AND group_key=?",
                UUID.class, account, "list:" + list);
        UUID domain = jdbc.queryForObject("SELECT public_id FROM sender_groups WHERE account_id=? AND group_key=?",
                UUID.class, account, "domain:host.example");
        var group = dashboard.page(account, filter("all", "")).groups().getFirst();
        assertThat(group.id()).isEqualTo(id);
        assertThat(group.label()).isEqualTo("LendingTree");
        assertThat(group.messages()).isEqualTo(2);
        assertThat(group.addresses()).isEqualTo(2);
        assertThat(dashboard.selection(account, filter("all", ""), List.of(id), null, revision)).hasSize(2);
        assertThat(dashboard.page(account, filter("all", "LendingTree")).groups().getFirst().label()).isEqualTo("LendingTree");
        assertThat(dashboard.page(account, filter("all", list)).groups().getFirst().label()).isEqualTo("LendingTree");
        assertThat(dashboard.page(account, filter("all", "Hosting Billing")).groups()).singleElement()
            .satisfies(g -> assertThat(g.label()).isEqualTo("Hosting Billing"));
        var drilldown = DashboardFilter.from(Map.of("composition","domain:host.example"));
        assertThat(dashboard.page(account, drilldown).groups()).extracting(DashboardRepository.Group::id)
            .containsExactly(domain);
        assertThat(dashboard.details(account, filter("all", ""), domain, 0).messages())
            .extracting(DashboardRepository.Message::displayName, DashboardRepository.Message::address)
            .containsExactly(tuple("Hosting Billing", "billing@host.example"));
        assertThat(jdbc.queryForObject("SELECT label FROM sender_groups WHERE public_id=?", String.class, id)).isEqualTo(list);
        var units = List.of(dashboard.grouping(account,id).units().getFirst().id(),
            dashboard.grouping(account,domain).units().getFirst().id());
        grouping.correct(account, new DashboardGroupingService.Correction("MERGE",units,"My accounts",null,revision),true);
        assertThat(dashboard.page(account, filter("all", "")).groups()).extracting(DashboardRepository.Group::label)
            .containsExactly("My accounts");
        verifyNoInteractions(gmailProvider, outlookProvider);
    }

    @ParameterizedTest
    @ValueSource(strings = {"google", "microsoft"})
    void senderAddressViewDrillsDownAndPreviewsExactMessagesWithoutChangingManualGroups(String provider) throws Exception {
        this.provider = provider;
        var auth = authenticatedAs(OWNER);
        if (provider.equals("microsoft")) {
            account = userAccounts.createAccount(OWNER, provider);
            var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
            var user = new DefaultOAuth2User(authorities,
                    Map.of("sub", "synthetic-microsoft-" + OWNER, "email", OWNER), "sub");
            auth = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .authentication(new OAuth2AuthenticationToken(user, authorities, provider));
        }
        String brian = "Brian@clickup.example", ryan = "Ryan@clickup.example";
        String list = "ClickUp <notifications.clickup.example>";
        listMessage("brian-list", brian, list);
        listMessage("ryan-list", ryan, list);
        var brianDirect = message("brian-direct", brian, List.of("INBOX"), true, List.of(), true, NOW);
        message("brian-archived", brian, List.of(), true, List.of(), false, NOW);
        jdbc.update("""
                UPDATE message_metadata SET from_display_name=CASE WHEN provider_message_id='brian-archived'
                    THEN 'Brian Shen' ELSE lower(dashboard_from_address) END WHERE dashboard_from_address=?
                """, brian);
        jdbc.update("UPDATE message_metadata SET from_display_name='Ryan Howe' WHERE dashboard_from_address=?", ryan);
        for (String id : List.of("missing", "ambiguous")) {
            var headers = id.equals("missing") ? List.<String>of() : List.of(brian, ryan);
            insert(id, brian, DashboardIdentity.parse(headers, List.of(), id), List.of("INBOX"), true, List.of(), true, NOW);
        }
        for (int index = 0; index < 7; index++)
            message("other-" + index, "other" + index + "@example.test", List.of("INBOX"), true, List.of(), false, NOW);

        var currentFilter = DashboardFilter.from(Map.of("sort", "unread"));
        var listGroup = dashboard.page(account, currentFilter).groups().getFirst();
        grouping.correct(account, new DashboardGroupingService.Correction("SPLIT", List.of(), "Brian list", null,
                0, "Ryan list", listGroup.id(), List.of(brian)), true);
        var current = dashboard.page(account, currentFilter);
        assertThat(current.groups()).extracting(DashboardRepository.Group::label).contains("Brian list", "Ryan list");
        assertThat(current.groups()).filteredOn(row -> brian.equals(row.address())).singleElement()
                .satisfies(row -> assertThat(row.label()).isEqualTo("Brian Shen"));
        assertThat(dashboard.inboxComposition(account, filter("inbox", "brian-direct")))
                .extracting(DashboardRepository.Composition::label).containsExactly("Brian Shen");
        var chart = dashboard.inboxComposition(account, currentFilter);

        var addressFilter = DashboardFilter.from(Map.of("groupBy", "address", "sort", "unread"));
        var addressPage = dashboard.page(account, addressFilter);
        var brianGroup = addressPage.groups().getFirst();
        assertThat(brianGroup.label()).isEqualTo("Brian Shen");
        assertThat(brianGroup.address()).isEqualTo(brian);
        assertThat(brianGroup.messages()).isEqualTo(2);
        assertThat(dashboard.page(account, DashboardFilter.from(Map.of("groupBy", "address", "scope", "all", "q", brian)))
                .groups()).filteredOn(row -> brian.equals(row.address())).singleElement().satisfies(row -> {
                    assertThat(row.label()).isEqualTo("Brian Shen");
                    assertThat(row.messages()).isEqualTo(3);
                });
        assertThat(addressPage.groups()).filteredOn(row -> row.address() == null)
                .hasSize(2).allSatisfy(row -> {
                    assertThat(row.messages()).isOne();
                    assertThat(row.addresses()).isZero();
                });
        mockMvc.perform(get("/dashboard/api/page").with(auth).param("groupBy", "address")
                .param("sort", "unread").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.groups.length()").value(1));
        mockMvc.perform(get("/dashboard/api/page").with(auth).param("groupBy", "address").param("q", "Ryan Howe"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.groups.length()").value(1))
                .andExpect(jsonPath("$.groups[0].address").value(ryan));

        String chartKey = "group:" + brianGroup.id();
        assertThat(dashboard.inboxComposition(account, addressFilter)).contains(
                new DashboardRepository.Composition(chartKey, "Brian Shen · " + brian, 2));
        var drilldown = DashboardFilter.from(Map.of("groupBy", "address", "composition", chartKey));
        assertThat(dashboard.page(account, drilldown).groups()).containsExactly(brianGroup);
        mockMvc.perform(get("/dashboard/api/groups/" + brianGroup.id()).with(auth).param("groupBy", "address")
                .param("composition", chartKey))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages.length()").value(2));
        var ids = dashboard.selection(account, drilldown, List.of(brianGroup.id()), null, current.revision())
                .stream().map(DashboardRepository.Selected::id).toList();
        assertThat(ids).hasSize(2).contains(brianDirect);
        var preview = mockMvc.perform(post("/dashboard/api/previews").with(auth).with(csrfToken())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                        new DashboardPreviewService.Request(drilldown, ids, "MARK_READ", current.revision()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.selected").value(2))
                .andExpect(jsonPath("$.effective").value(2)).andReturn().getResponse().getContentAsString();
        var previewId = UUID.fromString(json.readTree(preview).get("id").asText());
        assertThat(previews.targets(account, previewId, 0).rows()).extracting(row -> (UUID) row.get("message_id"))
                .containsExactlyInAnyOrderElementsOf(ids);
        assertThat(dashboard.page(account, currentFilter).groups()).isEqualTo(current.groups());
        assertThat(dashboard.inboxComposition(account, currentFilter)).isEqualTo(chart);
        var job = jobs.confirm(account, previewId);
        assertThat(dashboard.page(account, currentFilter).summary()).isEqualTo(current.summary());
        assertThat(jdbc.queryForList("SELECT message_id FROM cleanup_job_targets WHERE job_id=?", UUID.class, job.id()))
                .containsExactlyInAnyOrderElementsOf(ids);
        verifyNoInteractions(gmailProvider, outlookProvider);
    }

    @ParameterizedTest
    @ValueSource(strings = {"google", "microsoft"})
    void domainEvidenceIdentifiesTheBrandAmongDifferentSavedNames(String provider) {
        this.provider = provider;
        if (provider.equals("microsoft")) account = userAccounts.createAccount("outlook@example.test", provider);
        message("linkedin", "updates@linkedin.com", List.of("INBOX"), true, List.of(), false, NOW);
        message("linkedin-jobs", "jobs@linkedin.com", List.of("INBOX"), true, List.of(), false, NOW);
        message("linkedin-person", "member@linkedin.com", List.of("INBOX"), true, List.of(), false, NOW);
        message("skechers", "offers@skechers.com", List.of("INBOX"), true, List.of(), false, NOW);
        message("skechers-stores", "stores@skechers.com", List.of("INBOX"), true, List.of(), false, NOW);
        listMessage("target", "orders@oe.target.com", "<7282972.xt.local>");
        listMessage("target-circle", "offers@oe.target.com", "<7282972.xt.local>");
        jdbc.update("""
            UPDATE message_metadata SET from_display_name=CASE provider_message_id
                WHEN 'linkedin' THEN 'LinkedIn'
                WHEN 'linkedin-jobs' THEN 'LinkedIn Job Alerts'
                WHEN 'linkedin-person' THEN 'A Member'
                WHEN 'skechers' THEN 'SKECHERS'
                WHEN 'skechers-stores' THEN 'Skechers Stores'
                WHEN 'target' THEN 'Target'
                WHEN 'target-circle' THEN 'Target Circle'
            END WHERE account_id=?
            """, account);
        assertThat(dashboard.page(account, filter("inbox", "")).groups())
            .extracting(DashboardRepository.Group::label, DashboardRepository.Group::messages)
            .containsExactlyInAnyOrder(tuple("LinkedIn",3L),tuple("Skechers",2L),tuple("Target",2L));
        assertThat(dashboard.page(account, filter("inbox", "Target Circle")).groups()).singleElement()
            .satisfies(group -> {
                assertThat(group.label()).isEqualTo("Target");
                assertThat(dashboard.details(account,filter("inbox","Target Circle"),group.id(),0).messages())
                    .extracting(DashboardRepository.Message::displayName).containsExactly("Target Circle");
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"google", "microsoft"})
    void splitsTwoAddressesSharingOneListAndLeavesNewAddressesAutomatic(String provider) {
        this.provider = provider;
        if (provider.equals("microsoft")) account = userAccounts.createAccount("outlook@example.test", provider);
        String list = "shared-list.example";
        String header = "News <" + list + ">";
        listMessage("list-a", "a@first.example", header);
        listMessage("list-b", "b@second.example", header);
        var filter = filter("inbox", "");
        var original = dashboard.page(account, filter).groups().getFirst();
        var originalUnits = jdbc.queryForList("SELECT * FROM message_sender_units ORDER BY metadata_id");
        assertThat(dashboard.grouping(account,original.id()).units()).singleElement()
            .satisfies(unit -> assertThat(unit.addresses()).containsExactly("a@first.example","b@second.example"));
        var request = new DashboardGroupingService.Correction("SPLIT",List.of(),"First",null,0,"Second",
            original.id(),List.of("a@first.example"));
        assertThat(grouping.correct(account,request,false).affected()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sender_address_units",Long.class)).isZero();
        grouping.correct(account,request,true);
        assertThat(jdbc.queryForList("SELECT * FROM message_sender_units ORDER BY metadata_id")).isEqualTo(originalUnits);
        assertThat(dashboard.inboxComposition(account,filter)).extracting(DashboardRepository.Composition::label)
            .containsExactlyInAnyOrder("First","Second");
        for (var group : dashboard.page(account,filter).groups()) {
            var address = group.label().equals("First") ? "a@first.example" : "b@second.example";
            assertThat(dashboard.details(account,filter,group.id(),0).messages())
                .extracting(DashboardRepository.Message::address).containsExactly(address);
        }

        listMessage("later-a", "a@first.example", header);
        listMessage("new-c", "c@third.example", header);
        grouping.rebuild(account);
        var refreshed = dashboard.page(account,filter).groups();
        assertThat(refreshed)
            .extracting(DashboardRepository.Group::label,DashboardRepository.Group::messages)
            .containsExactlyInAnyOrder(tuple("First",2L),tuple("Second",1L),tuple("Display c@third.example",1L));
        assertThat(refreshed).filteredOn(group -> group.id().equals(original.id()))
            .singleElement().satisfies(group -> assertThat(group.messages()).isOne());
        var correction = jdbc.queryForObject("SELECT public_id FROM sender_group_corrections WHERE kind='SPLIT'",UUID.class);
        grouping.correct(account,new DashboardGroupingService.Correction("RESET",List.of(),null,correction,2),true);
        assertThat(dashboard.inboxComposition(account,filter)).singleElement().satisfies(bar -> {
            assertThat(bar.label()).isEqualTo("Multiple senders");
            assertThat(bar.messages()).isEqualTo(4L);
        });
        verifyNoInteractions(gmailProvider,outlookProvider);
    }

    @ParameterizedTest
    @ValueSource(strings = {"google", "microsoft"})
    void mailingListNeverPicksOneBrandWhenFiltersHideConflictingNames(String provider) {
        this.provider = provider;
        if (provider.equals("microsoft")) account = userAccounts.createAccount("outlook@example.test", provider);
        String list = "1050979.xt.local";
        listMessage("shared-a", "a@first.example", "News <" + list + ">");
        listMessage("shared-b", "a@first.example", "News <" + list + ">");
        jdbc.update("UPDATE message_metadata SET from_display_name='First Brand' WHERE provider_message_id='shared-a'");
        jdbc.update("UPDATE message_metadata SET from_display_name='Second Brand',labels=ARRAY[]::text[] WHERE provider_message_id='shared-b'");
        assertThat(dashboard.page(account, filter("all", "")).groups().getFirst().label()).isEqualTo("a@first.example");
        var filteredGroup = dashboard.page(account, filter("inbox", "First Brand")).groups().getFirst();
        assertThat(filteredGroup.label()).isEqualTo("a@first.example");
        assertThat(dashboard.details(account, filter("all", ""), filteredGroup.id(), 0).messages())
            .extracting(DashboardRepository.Message::displayName).containsExactly("First Brand", "Second Brand");
        jdbc.update("UPDATE message_metadata SET classification_override=CASE WHEN provider_message_id='shared-a' THEN 'MARKETING' ELSE 'NEWSLETTER' END");
        var category = new DashboardFilter("all", "", "", "", "UTC", 0, "MARKETING");
        assertThat(dashboard.page(account, category).groups().getFirst().label()).isEqualTo("a@first.example");
        jdbc.update("UPDATE message_metadata SET from_display_name='  ' WHERE account_id=?", account);
        assertThat(dashboard.page(account, filter("all", "")).groups().getFirst().label()).isEqualTo("a@first.example");
        verifyNoInteractions(gmailProvider, outlookProvider);
    }

    @Test
    void deterministicGroupingPreservesLocalCaseListsAndSharedDomainExceptions() {
        jdbc.update("INSERT INTO dashboard_domain_registry(domain,kind) VALUES('shop.example','DEDICATED')");
        message("receipt", "receipts@shop.example", List.of("INBOX"), true, List.of(), true, NOW);
        message("offers", "offers@shop.example", List.of("INBOX"), true, List.of(), true, NOW);
        message("sub", "offers@mail.shop.example", List.of("INBOX"), true, List.of(), true, NOW);
        for (String from : List.of("Alice@gmail.com", "alice@gmail.com", "bob@gmail.com", "a@relay.example",
                "b@relay.example"))
            message(from, from, List.of("INBOX"), true, List.of(), false, NOW);
        listMessage("list-a", "first@shop.example", "News <list.shop.example>");
        listMessage("list-b", "second@other.example", "News <list.shop.example>");
        listMessage("list-c", "first@shop.example", "Other <other.shop.example>");
        message("bad-from", "One <a@example.test>, Two <b@example.test>", List.of("INBOX"), true, List.of(), false,
                NOW);
        var page = dashboard.page(account, filter("all", ""));
        assertThat(page.summary().get("groups")).isEqualTo(9L);
        assertThat(page.groups()).filteredOn(g -> g.label().equals("shop.example")).singleElement()
                .satisfies(g -> assertThat(g.messages()).isEqualTo(3));
        assertThat(page.groups()).filteredOn(g -> g.label().equals("Multiple senders")).singleElement()
                .satisfies(g -> {
                    assertThat(g.messages()).isEqualTo(2);
                    assertThat(g.addresses()).isEqualTo(2);
                });
        assertThat(jdbc.queryForObject(
                "SELECT dashboard_from_address FROM message_metadata WHERE provider_message_id='bad-from'",
                String.class)).isNull();
        UUID fallback = jdbc.queryForObject(
                "SELECT public_id FROM sender_units WHERE account_id=? AND kind='MESSAGE' AND canonical_key='bad-from'",
                UUID.class, account);
        assertThatThrownBy(() -> grouping.correct(account,
                new DashboardGroupingService.Correction("SEPARATE", List.of(fallback), null, null, 0), true))
                .hasMessageContaining("Fresh sender metadata");
    }

    @Test
    void organizationGroupsCombineChaseSubdomainsAndKeepExactSelectionsAndCorrections() throws Exception {
        var addresses = List.of("no.reply.alerts@chase.com", "no-reply@alertsp.chase.com",
                "Chase@e.chase.com", "chase@email.chase.com", "Chase@notify.chase.com");
        int[] counts = { 53, 37, 21, 5, 1 };
        for (int a = 0; a < addresses.size(); a++)
            for (int i = 0; i < counts[a]; i++)
                message("chase-" + a + "-" + i, addresses.get(a), List.of("INBOX"), true, List.of(), a == 0 && i < 2,
                        NOW);
        var query = filter("inbox", "chase.com");
        var result = dashboard.page(account, query);
        assertThat(result.summary()).containsEntry("messages", 117L).containsEntry("unread", 2L).containsEntry("groups",
                1L);
        var group = result.groups().getFirst();
        assertThat(group.label()).isEqualTo("chase.com");
        assertThat(group.addresses()).isEqualTo(5);
        assertThat(dashboard.addresses(account, query, group.id()).addresses()).hasSize(5);
        mockMvc.perform(get("/dashboard/api/groups/{group}/addresses", group.id()).with(authenticatedAs(OWNER))
                .param("q", addresses.get(2)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.addresses.length()").value(1))
                .andExpect(jsonPath("$.addresses[0].messages").value(21))
                .andExpect(jsonPath("$.addresses[0].unread").value(0))
                .andExpect(jsonPath("$.revision").value(result.revision()))
                .andExpect(jsonPath("$.messages").doesNotExist()).andExpect(jsonPath("$.units").doesNotExist())
                .andExpect(jsonPath("$.recommendations").doesNotExist());
        mockMvc.perform(get("/dashboard/api/groups/{group}/grouping", group.id()).with(authenticatedAs(OWNER))
                .param("q", addresses.get(2)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.units.length()").value(5))
                .andExpect(jsonPath("$.units[*].key",org.hamcrest.Matchers.hasItem(addresses.get(2))))
                .andExpect(jsonPath("$.revision").value(result.revision()))
                .andExpect(jsonPath("$.messages").doesNotExist()).andExpect(jsonPath("$.addresses").doesNotExist())
                .andExpect(jsonPath("$.recommendations").doesNotExist());
        userAccounts.createAccount("group-reader@example.test");
        for (String suffix : List.of("", "/addresses", "/grouping"))
            mockMvc.perform(get("/dashboard/api/groups/" + group.id() + suffix)
                    .with(authenticatedAs("group-reader@example.test"))).andExpect(status().isNotFound());
        verifyNoInteractions(gmailProvider);
        assertThat(dashboard.selection(account, query, List.of(group.id()), null, 0)).hasSize(117);
        assertThat(dashboard.selection(account, query, List.of(group.id()), addresses.get(2), 0)).hasSize(21);
        var preview = previews.create(account, new DashboardPreviewService.Request(query,
                dashboard.selection(account, query, List.of(group.id()), null, 0).stream()
                        .map(DashboardRepository.Selected::id).toList(),
                "MARK_READ", 0));
        assertThat(preview.selected()).isEqualTo(117);
        assertThat(preview.effective()).isEqualTo(2);
        UUID unit = jdbc.queryForObject("SELECT public_id FROM sender_units WHERE account_id=? AND canonical_key=?",
                UUID.class, account, addresses.get(2));
        grouping.correct(account, new DashboardGroupingService.Correction("SEPARATE", List.of(unit), null, null, 0),
                true);
        message("chase-new", addresses.get(2), List.of("INBOX"), true, List.of(), false, NOW);
        message("chase-new-domain", "service@secure.chase.com", List.of("INBOX"), true, List.of(), false, NOW);
        assertThat(dashboard.page(account, query).summary().get("groups")).isEqualTo(2L);
        assertThatThrownBy(() -> previews.targets(account, preview.id(), 0)).hasMessageContaining("Grouping changed");
        verifyNoInteractions(gmailProvider, outlookProvider);
    }

    @Test
    void organizationBoundariesRespectPublicPrivateWildcardExceptionAndSharedDomains() {
        for (var entry : Map.of("alerts.mail.acme.co.uk", "acme.co.uk", "news.chase.com", "chase.com",
                "mail.alice.github.io", "alice.github.io", "mail.bob.github.io", "bob.github.io",
                "x.www.ck", "www.ck", "x.a.b.ck", "a.b.ck", "mail.xn--bcher-kva.de", "xn--bcher-kva.de").entrySet())
            assertThat(jdbc.queryForObject("SELECT dashboard_organization_domain(?)", String.class, entry.getKey()))
                    .isEqualTo(entry.getValue());
        for (String domain : List.of("co.uk", "github.io", "b.ck", "unknown.example", "chase.com.attacker.invalid",
                "gmail.com", "mail.gmail.com", "sendgrid.net", "x.sendgrid.net", "substack.com", "alice.substack.com",
                "onmicrosoft.com"))
            assertThat(jdbc.queryForObject("SELECT dashboard_organization_domain(?)", String.class, domain)).as(domain)
                    .isNull();
        for (String address : List.of("a@gmail.com", "b@gmail.com", "a@x.sendgrid.net", "b@x.sendgrid.net",
                "a@one.github.io", "b@two.github.io"))
            message(address, address, List.of("INBOX"), true, List.of(), false, NOW);
        listMessage("one-list", "a@chase.com", "One <one.chase.com>");
        listMessage("two-list", "b@chase.com", "Two <two.chase.com>");
        assertThat(dashboard.page(account, filter("all", "")).summary().get("groups")).isEqualTo(8L);
    }

    @Test
    void dateSearchSummaryExpansionAndSelectionsUseTheSamePredicate() {
        jdbc.update("INSERT INTO dashboard_domain_registry(domain,kind) VALUES('shop.example','DEDICATED')");
        UUID first = message("one", "a@shop.example", List.of("INBOX"), true, List.of(), true,
                Instant.parse("2026-10-28T07:00:00Z"));
        message("two", "b@shop.example", List.of("INBOX"), true, List.of(), false,
                Instant.parse("2026-10-29T07:00:00Z"));
        message("undated", "a@shop.example", List.of("INBOX"), true, List.of(), false, null);
        jdbc.update("UPDATE message_metadata SET subject_header='Literal %_! NEEDLE' WHERE provider_message_id='one'");
        var date = new DashboardFilter("inbox", "%_!", "2026-10-28", "2026-10-28", "America/Los_Angeles", 0);
        var page = dashboard.page(account, date);
        var group = page.groups().getFirst();
        assertThat(page.summary().get("messages")).isEqualTo(1L);
        assertThat(dashboard.details(account, date, group.id(), 0).messages())
                .extracting(DashboardRepository.Message::id).containsExactly(first);
        assertThat(dashboard.selection(account, date, List.of(group.id()), null, 0))
                .extracting(DashboardRepository.Selected::id).containsExactly(first);
        assertThat(dashboard.page(account, filter("all", "shop.example")).summary().get("messages")).isEqualTo(3L);
        assertThat(dashboard.page(account, filter("all", "needle")).summary().get("messages")).isEqualTo(1L);
        assertThat(dashboard.page(account, filter("all", "")).summary().get("undated")).isEqualTo(1L);
        var dst = new DashboardFilter("all", "", "2026-11-01", "2026-11-01", "America/Los_Angeles", 0);
        assertThat(java.time.Duration.between(dst.startInstant(), dst.endInstant()).toHours()).isEqualTo(25);
    }

    @Test
    void labelExpressionsSelectionAndChartUseTheSameFilteredMessagesAndSavedGroups() {
        for (int i=0;i<12;i++)
            message("newsletter-"+i,"digest@news.example",List.of("INBOX"),true,List.of(),true,NOW);
        message("security", "alert@alerts.example", List.of("INBOX"), true, List.of(), false, NOW);
        message("archive", "digest@news.example", List.of(), true, List.of(), false, NOW);
        jdbc.update("""
            UPDATE message_metadata SET classification_override =
                CASE WHEN provider_message_id='security' THEN 'SECURITY' ELSE 'NEWSLETTER' END
            """);
        var any = DashboardFilter.from(Map.of("scope","all","labels","NEWSLETTER,SECURITY","match","any"));
        var all = DashboardFilter.from(Map.of("scope","all","labels","NEWSLETTER,SECURITY","match","all"));
        assertThat(dashboard.page(account, all).groups()).isEmpty();
        assertThat(dashboard.page(account, any).summary()).containsEntry("messages",14L).containsEntry("unread",12L);
        assertThat(dashboard.inboxComposition(account,any)).extracting(DashboardRepository.Composition::messages)
            .containsExactly(12L,1L);
        var matchingGroups = dashboard.page(account,any).groups().stream().map(DashboardRepository.Group::id).toList();
        assertThat(dashboard.selection(account,any,matchingGroups,null,0)).hasSize(14);
        assertThat(dashboard.selection(account,any,List.of(),null,0)).isEmpty();

        var units = jdbc.queryForList("SELECT public_id FROM sender_units WHERE account_id=? ORDER BY id", UUID.class,account);
        grouping.correct(account,new DashboardGroupingService.Correction("MERGE",units,"Digest and alerts",null,0),true);
        assertThat(dashboard.inboxComposition(account,any)).singleElement().satisfies(group -> {
            assertThat(group.label()).isEqualTo("Digest and alerts");
            assertThat(group.messages()).isEqualTo(13);
            var drilldown = DashboardFilter.from(Map.of("labels","NEWSLETTER,SECURITY","match","any","composition",group.key()));
            var page = dashboard.page(account,drilldown);
            assertThat(page.summary()).containsEntry("messages",13L);
            assertThat(dashboard.selection(account,drilldown,List.of(page.groups().getFirst().id()),null,1)).hasSize(13);
        });
        var split = new DashboardGroupingService.Correction("SPLIT",List.of(),"Digests",null,1,"Alerts",
            dashboard.page(account,any).groups().getFirst().id(),List.of("digest@news.example"));
        grouping.correct(account,split,true);
        jdbc.update("UPDATE message_metadata SET dashboard_unit_key=dashboard_unit_key WHERE account_id=?",account);
        assertThat(dashboard.page(account,any).groups()).extracting(DashboardRepository.Group::label)
            .containsExactly("Digests","Alerts");
        grouping.rebuild(account);
        assertThat(dashboard.inboxComposition(account,any)).extracting(DashboardRepository.Composition::label)
            .containsExactly("Digests","Alerts");
        assertThat(dashboard.page(account,any).groups()).extracting(DashboardRepository.Group::inbox)
            .containsExactly(12L,1L);
        // Separate automatic service streams still share the organization's chart bar.
        jdbc.update("""
            UPDATE sender_groups SET group_key='classified-domain:example.test:'||id
            WHERE id IN (SELECT group_id FROM sender_group_members WHERE account_id=?)
            """, account);
        assertThat(dashboard.inboxComposition(account,any))
            .containsExactly(new DashboardRepository.Composition("domain:example.test","example.test",13));
        var domain = DashboardFilter.from(Map.of("composition","domain:example.test"));
        assertThat(dashboard.page(account,domain).summary()).containsEntry("messages",13L).containsEntry("groups",2L);
        verifyNoInteractions(gmailProvider,outlookProvider);
    }

    @Test
    void correctionsRejectTransitiveConflictsAndResetOnlyTheChosenCorrection() {
        for (String from : List.of("a@one.example", "b@two.example", "c@three.example"))
            message(from, from, List.of("INBOX"), true, List.of(), true, NOW);
        List<UUID> units = jdbc.queryForList("SELECT public_id FROM sender_units WHERE account_id=? ORDER BY id",
                UUID.class, account);
        grouping.correct(account,
                new DashboardGroupingService.Correction("MERGE", units.subList(0, 2), "Together", null, 0), false);
        assertThat(repository.revision(account)).isZero();
        grouping.correct(account,
                new DashboardGroupingService.Correction("MERGE", units.subList(0, 2), "Together", null, 0), true);
        assertThat(dashboard.page(account, filter("all", "")).summary().get("groups")).isEqualTo(2L);
        grouping.correct(account,
                new DashboardGroupingService.Correction("SEPARATE", List.of(units.get(1)), null, null, 1), true);
        grouping.correct(account,
                new DashboardGroupingService.Correction("MERGE", List.of(units.get(0), units.get(2)), "AC", null, 2),
                true);
        assertThatThrownBy(() -> grouping.correct(account,
                new DashboardGroupingService.Correction("MERGE", List.of(units.get(1), units.get(2)), "No", null, 3),
                true)).hasMessageContaining("kept separate");
        UUID separation = jdbc.queryForObject(
                "SELECT public_id FROM sender_group_corrections WHERE account_id=? AND kind='SEPARATE'", UUID.class,
                account);
        grouping.correct(account, new DashboardGroupingService.Correction("RESET", List.of(), null, separation, 3),
                true);
        assertThat(dashboard.page(account, filter("all", "")).summary().get("groups")).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sender_group_corrections WHERE active", Long.class))
                .isEqualTo(2L);
        assertThatThrownBy(() -> grouping.correct(account,
                new DashboardGroupingService.Correction("SEPARATE", List.of(units.get(0)), null, null, 0), true))
                .hasMessageContaining("Grouping changed");
    }

    @Test
    void exactPreviewDeduplicatesKeepsNoOpsAndSurvivesNewMailButInvalidatesOnCorrection() {
        UUID a = message("one", "a@example.test", List.of("INBOX"), true, List.of(), true, NOW);
        UUID b = message("two", "a@example.test", List.of("INBOX"), true, List.of(), false, NOW);
        var request = new DashboardPreviewService.Request(filter("inbox", ""), List.of(a, b, a), "MARK_READ", 0);
        var preview = previews.create(account, request);
        assertThat(preview.selected()).isEqualTo(2);
        assertThat(preview.effective()).isEqualTo(1);
        assertThat(preview.skipped()).isEqualTo(1);
        long scope = jdbc.queryForObject("SELECT scope_id FROM dashboard_previews WHERE public_id=?", Long.class,
                preview.id());
        assertThat(scopes.requireScope(account, scope).getMessageIds()).containsExactly("one", "two");
        message("new", "a@example.test", List.of("INBOX"), true, List.of(), true, NOW);
        jdbc.update("UPDATE message_metadata SET subject_header='Changed later' WHERE provider_message_id='one'");
        assertThat(previews.targets(account, preview.id(), 0).total()).isEqualTo(2);
        assertThat(previews.targets(account, preview.id(), 0).rows().getFirst().get("subject_header"))
                .isEqualTo("Subject one");
        UUID unit = jdbc.queryForObject("SELECT public_id FROM sender_units WHERE account_id=? AND kind='ADDRESS'",
                UUID.class, account);
        grouping.correct(account, new DashboardGroupingService.Correction("SEPARATE", List.of(unit), null, null, 0),
                true);
        assertThatThrownBy(() -> previews.targets(account, preview.id(), 0)).hasMessageContaining("Grouping changed");
        assertThat(scopes.requireScope(account, scope).getMessageIds()).containsExactly("one", "two");
    }

    @Test
    void previewsSkipChangedEligibilityProtectedAndNoOpArchiveWithoutSubstitution() {
        UUID archived = message("archived", "one@example.test", List.of(), true, List.of(), true, NOW);
        UUID protectedId = message("protected", "protected@example.test", List.of("INBOX"), true, List.of(), true, NOW);
        UUID stale = message("stale", "one@example.test", List.of("TRASH"), true, List.of(), true, NOW);
        jdbc.update(
                "INSERT INTO protected_senders(account_id,sender_address,source) VALUES(?,'protected@example.test','USER')",
                account);
        var preview = previews.create(account, new DashboardPreviewService.Request(filter("all", ""),
                List.of(archived, protectedId, stale), "ARCHIVE", 0));
        assertThat(preview.selected()).isEqualTo(3);
        assertThat(preview.effective()).isZero();
        assertThat(preview.skipped()).isEqualTo(3);
        assertThat(previews.targets(account, preview.id(), 0).rows()).extracting(m -> m.get("reason"))
                .containsExactlyInAnyOrder("Already outside Inbox", "Protected sender",
                        "No longer matches these filters or received-mail eligibility");
        previews.invalidate(account, preview.id());
        assertThatThrownBy(() -> previews.targets(account, preview.id(), 0)).hasMessageContaining("unavailable");
        verifyNoInteractions(gmailProvider, outlookProvider);
    }

    @Test
    void routesRequireOwnershipCsrfAndRenderTheApprovedDashboard() throws Exception {
        UUID id = message("owned", "owned@example.test", List.of("INBOX"), true, List.of(), true, NOW);
        long other = userAccounts.createAccount("other@example.test");
        UUID foreign = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO message_metadata(account_id,provider,provider_message_id,dashboard_message_id,labels) VALUES(?,'google','foreign',?,ARRAY['INBOX'])",
                other, foreign);
        mockMvc.perform(get("/dashboard").with(authenticatedAs(OWNER))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Select sender groups for organizing")))
                .andExpect(header().string("Cache-Control", "no-store"));
        var body = json.writeValueAsString(
                new DashboardPreviewService.Request(filter("all", ""), List.of(foreign), "ARCHIVE", 0));
        mockMvc.perform(post("/dashboard/api/previews").with(authenticatedAs(OWNER))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mockMvc.perform(post("/dashboard/api/previews").with(authenticatedAs(OWNER)).with(csrfToken())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        var preview = previews.create(account,
                new DashboardPreviewService.Request(filter("inbox", ""), List.of(id), "ARCHIVE", 0));
        mockMvc.perform(
                get("/dashboard/api/previews/{id}/targets", preview.id()).with(authenticatedAs("other@example.test")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/dashboard/api/page").with(authenticatedAs(OWNER))).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.messages").value(1));
        mockMvc.perform(get("/dashboard/api/page").with(authenticatedAs(OWNER)).param("scope", "all").param("q", "%_!"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.messages").value(0));
        mockMvc.perform(post("/dashboard/api/settings").with(authenticatedAs(OWNER)).with(csrfToken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"zone\":\"America/Los_Angeles\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/dashboard/api/settings").with(authenticatedAs(OWNER)))
                .andExpect(jsonPath("$.zone").value("America/Los_Angeles"));
        verifyNoInteractions(gmailProvider, outlookProvider);
    }

    @Test
    void senderPagesAndMessagePagesHaveIndependentTenRowBoundaries() {
        for (int i = 0; i < 25; i++)
            message("bulk-" + i, "bulk@example.test", List.of("INBOX"), true, List.of(), i % 2 == 0,
                    NOW.minusSeconds(i));
        for (int i = 0; i < 10; i++)
            message("sender-" + i, "sender" + i + "@example.test", List.of("INBOX"), true, List.of(), false, NOW);
        var query = filter("inbox", "");
        var first = dashboard.page(account, query);
        assertThat(first.groups()).hasSize(10);
        assertThat(first.summary().get("groups")).isEqualTo(11L);
        var group = first.groups().getFirst();
        assertThat(dashboard.details(account, query, group.id(), 0).messages()).hasSize(10);
        assertThat(dashboard.details(account, query, group.id(), 1).messages()).hasSize(10);
        assertThat(dashboard.details(account, query, group.id(), 2).messages()).hasSize(5);
        assertThat(dashboard.selection(account, query, List.of(group.id()), null, 0)).hasSize(25);
        assertThat(dashboard.selection(account, query,
                first.groups().stream().map(DashboardRepository.Group::id).toList(), null, 0)).hasSize(34);
        var second = dashboard.page(account, new DashboardFilter("inbox", "", "", "", "UTC", 1));
        assertThat(second.groups()).hasSize(1);
        assertThat(dashboard.selection(account, query, List.of(second.groups().getFirst().id()), null, 0)).hasSize(1);
        assertThatThrownBy(() -> dashboard.details(account, query, UUID.randomUUID(), 0))
                .hasMessageContaining("unavailable");
    }

    @Test
    void senderUnreadViewAndSelectionExcludeReadMessagesAcrossPages() throws Exception {
        var unreadIds = new ArrayList<UUID>();
        for (int i = 0; i < 25; i++) {
            var id = message("unread-view-" + i, "sender@example.test", List.of("INBOX"), true,
                    List.of(), i % 2 == 0, NOW.minusSeconds(i));
            if (i % 2 == 0) unreadIds.add(id);
        }
        message("other-sender", "other@example.test", List.of("INBOX"), true, List.of(), true, NOW);
        message("outside-inbox", "sender@example.test", List.of(), true, List.of(), true, NOW);
        var query = filter("inbox", "");
        var page = dashboard.page(account, query);
        var group = page.groups().getFirst().id();
        mockMvc.perform(get("/dashboard/api/groups/" + group).with(authenticatedAs(OWNER))
                .param("unreadOnly", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(13))
                .andExpect(jsonPath("$.messages.length()").value(10))
                .andExpect(jsonPath("$.recommendations.length()").value(10))
                .andExpect(jsonPath("$.addresses").doesNotExist()).andExpect(jsonPath("$.units").doesNotExist())
                .andExpect(jsonPath("$.messages[?(@.unread == false)]").isEmpty());
        mockMvc.perform(get("/dashboard/api/groups/" + group).with(authenticatedAs(OWNER))
                .param("unreadOnly", "true").param("messagePage", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages.length()").value(3));
        assertThat(dashboard.details(account, query, group, 0).total()).isEqualTo(25);

        mockMvc.perform(post("/dashboard/api/selection").with(authenticatedAs(OWNER))
                .with(csrfToken()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("filter", query, "revision", page.revision(), "allMatching", true))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        var response = mockMvc.perform(post("/dashboard/api/selection").with(authenticatedAs(OWNER))
                .with(csrfToken()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("filter", query, "groups", List.of(group),
                        "revision", page.revision(), "unreadOnly", true))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var ids = new ArrayList<UUID>();
        json.readTree(response).forEach(row -> ids.add(UUID.fromString(row.get("id").asText())));
        assertThat(ids).containsExactlyInAnyOrderElementsOf(unreadIds);
        for (String action : List.of("MARK_READ", "TRASH"))
            mockMvc.perform(post("/dashboard/api/previews").with(authenticatedAs(OWNER))
                    .with(csrfToken()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("filter", query, "ids", ids,
                            "action", action, "revision", page.revision()))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.selected").value(13))
                    .andExpect(jsonPath("$.effective").value(13));
    }

    @Test
    void unreadSortOrdersAllSendersBeforePagingAndUsesMatchingMessages() throws Exception {
        for (int sender = 0; sender < 12; sender++) {
            for (int i = 0; i < 24 - sender; i++)
                message("sort-" + sender + "-" + i, "sender" + sender + "@example.test",
                        List.of("INBOX"), true, List.of(), i < sender, NOW);
        }
        // Archived unread messages must not affect the inbox ordering.
        for (int i = 0; i < 15; i++)
            message("archived-" + i, "sender0@example.test", List.of(), true, List.of(), true, NOW);

        mockMvc.perform(get("/dashboard/api/page").with(authenticatedAs(OWNER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.groups[0].unread").value(0));
        mockMvc.perform(get("/dashboard/api/page").with(authenticatedAs(OWNER)).param("sort", "unread"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.groups.length()").value(10))
                .andExpect(jsonPath("$.groups[0].unread").value(11))
                .andExpect(jsonPath("$.groups[9].unread").value(2));
        mockMvc.perform(get("/dashboard/api/page").with(authenticatedAs(OWNER))
                .param("sort", "unread").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.groups.length()").value(2))
                .andExpect(jsonPath("$.groups[0].unread").value(1))
                .andExpect(jsonPath("$.groups[1].unread").value(0));
        mockMvc.perform(get("/dashboard/api/page").with(authenticatedAs(OWNER))
                .param("sort", "unread").param("scope", "all").param("q", "sender0@"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.groups[0].unread").value(15));
        mockMvc.perform(get("/dashboard/api/page").with(authenticatedAs(OWNER)).param("sort", "invalid"))
                .andExpect(status().isBadRequest());
        var filter = DashboardFilter.from(Map.of("sort", "unread", "page", "1"));
        assertThat(filter.firstPage().sort()).isEqualTo("unread");
        assertThat(filter.firstPage().page()).isZero();
    }

    UUID message(String id, String from, List<String> labels, boolean known, List<String> delivered, boolean unread,
            Instant at) {
        var identity = DashboardIdentity.parse(List.of(from), List.of(), id);
        return insert(id, from, identity, labels, known, delivered, unread, at);
    }

    void listMessage(String id, String from, String list) {
        insert(id, from, DashboardIdentity.parse(List.of(from), List.of(list), id), List.of("INBOX"), true, List.of(),
                true, NOW);
    }

    UUID insert(String id, String from, DashboardIdentity identity, List<String> labels, boolean known,
            List<String> delivered, boolean unread, Instant at) {
        return jdbc.query(connection -> {
            var ps = connection.prepareStatement(
                    """
                            INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,from_display_name,subject_header,
                              received_at,unread,labels,label_evidence_known,delivered_to_values,raw_from_values,
                              dashboard_from_address,dashboard_unit_kind,dashboard_unit_key,dashboard_identity_reason)
                            VALUES(?,?,?,?,?, ?,?,?,?,?,?,?,?,?,?,?) RETURNING dashboard_message_id
                            """);
            int i = 1;
            ps.setLong(i++, account);
            ps.setString(i++, provider);
            ps.setString(i++, id);
            ps.setString(i++, from.toLowerCase(Locale.ROOT));
            ps.setString(i++, "Display " + from);
            ps.setString(i++, "Subject " + id);
            ps.setTimestamp(i++, at == null ? null : Timestamp.from(at));
            ps.setBoolean(i++, unread);
            ps.setArray(i++, connection.createArrayOf("text", labels.toArray()));
            ps.setBoolean(i++, known);
            ps.setArray(i++, connection.createArrayOf("text", delivered.toArray()));
            ps.setArray(i++, connection.createArrayOf("text", new String[] { from }));
            ps.setString(i++, identity.address());
            ps.setString(i++, identity.kind());
            ps.setString(i++, identity.key());
            ps.setString(i, identity.reason());
            return ps;
        }, (r, n) -> r.getObject(1, UUID.class)).getFirst();
    }
}
