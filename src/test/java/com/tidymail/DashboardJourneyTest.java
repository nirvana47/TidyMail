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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

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
    ActionScopeService scopes;
    @Autowired
    ObjectMapper json;
    long account;

    @BeforeEach
    void account() {
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
        assertThat(page.groups()).filteredOn(g -> g.label().equals("list.shop.example")).singleElement()
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
    void organizationGroupsCombineChaseSubdomainsAndKeepExactSelectionsAndCorrections() {
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
        assertThat(dashboard.details(account, query, group.id(), 0).addresses()).hasSize(5);
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
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Select groups on this page")))
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
                            VALUES(?,'google',?,?,?, ?,?,?,?,?,?,?,?,?,?,?) RETURNING dashboard_message_id
                            """);
            int i = 1;
            ps.setLong(i++, account);
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
