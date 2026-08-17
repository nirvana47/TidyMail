package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.controller.SenderAggregationController;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import com.tidymail.dto.DomainRollup;
import com.tidymail.dto.ReplyZeroItem;
import com.tidymail.dto.ReplyZeroPageResponse;
import com.tidymail.dto.SenderCard;
import com.tidymail.dto.SenderPageResponse;
import com.tidymail.entity.SenderStat;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.BulkActionRequest;
import com.tidymail.mail.BulkActionResult;
import com.tidymail.mail.MailFolderOrLabel;
import com.tidymail.mail.MailMessage;
import com.tidymail.mail.MailProvider;
import com.tidymail.mail.MailThreadSummary;
import com.tidymail.mail.PageResult;
import com.tidymail.repository.SenderStatRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.security.crypto.CryptoUtil;
import com.tidymail.service.ReplyZeroDetector;
import com.tidymail.service.SenderAggregator;
import com.tidymail.service.SenderStatsPersistService;
import com.tidymail.util.CursorUtil;
import com.tidymail.util.Rfc5322Util;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

class Phase6SenderAggregationTest {

    // ============================================================
    // T6(a): Pure-Java DTO, Rfc5322Util, CursorUtil,
    //        SenderAggregator.score, SenderAggregator.aggregate
    // No Spring context. Plain JUnit 5 + Mockito where needed.
    // ============================================================

    @Test
    @DisplayName("T6_a_01 Rfc5322Util.extractAddrSpec happy paths — 4 cases")
    void T6_a_01_extractAddrSpecHappyPaths() {
        assertEquals("john@example.com",
            Rfc5322Util.extractAddrSpec("John Doe <john@Example.COM>"));
        assertEquals("support@github.com",
            Rfc5322Util.extractAddrSpec("<support@github.com>"));
        assertEquals("jane@doe.org",
            Rfc5322Util.extractAddrSpec("jane@doe.org"));
        assertEquals("dr.smith@univ.edu",
            Rfc5322Util.extractAddrSpec("\"Dr. Smith (OnLeave)\"  <dr.smith@UNIV.EDU> "));
    }

    @Test
    @DisplayName("T6_a_02 Rfc5322Util.extractAddrSpec B18 guards — nulls and malformed")
    void T6_a_02_extractAddrSpecGuards() {
        assertNull(Rfc5322Util.extractAddrSpec(null));
        assertNull(Rfc5322Util.extractAddrSpec(""));
        assertNull(Rfc5322Util.extractAddrSpec("@nodomainprefix.com"));
        assertNull(Rfc5322Util.extractAddrSpec("noatsign.com"));
        assertNull(Rfc5322Util.extractAddrSpec("user@"));
        assertEquals("only@spaces.com",
            Rfc5322Util.extractAddrSpec(" <only@spaces.com>"));
    }

    @Test
    @DisplayName("T6_a_03 Rfc5322Util.extractDomain happy paths — 4 cases")
    void T6_a_03_extractDomainHappyPaths() {
        assertEquals("example.com",
            Rfc5322Util.extractDomain("John <john@example.com>"));
        assertEquals("github.com",
            Rfc5322Util.extractDomain("<support@github.com>"));
        assertEquals("doe.org",
            Rfc5322Util.extractDomain("jane@doe.org"));
        assertEquals("case.com",
            Rfc5322Util.extractDomain("MiXeD@CaSe.CoM"));
    }

    @Test
    @DisplayName("T6_a_04 Rfc5322Util.extractDomain B18 guards")
    void T6_a_04_extractDomainGuards() {
        assertNull(Rfc5322Util.extractDomain(null));
        assertNull(Rfc5322Util.extractDomain("@prefixempty.com"));
        assertNull(Rfc5322Util.extractDomain("noat"));
        assertNull(Rfc5322Util.extractDomain("trailing@"));
        assertNull(Rfc5322Util.extractDomain("<bademail>"));
    }

    @Test
    @DisplayName("T6_a_05 CursorUtil roundtrip + 50 random + invalid guards + first-page empty token")
    void T6_a_05_cursorUtilRoundtrip() {
        String encoded = CursorUtil.encode("pageToken123", 5L, "scoreDesc");
        assertNotNull(encoded);
        assertFalse(encoded.contains("|"));
        CursorUtil.CursorParts parts = CursorUtil.decode(encoded);
        assertEquals("pageToken123", parts.googlePageToken());
        assertEquals(5L, parts.localOffset());
        assertEquals("scoreDesc", parts.localSort());

        Random r = new Random(42);
        for (int i = 0; i < 50; i++) {
            String tok = "tok-" + r.nextInt(100_000);
            long off = r.nextLong(1000);
            String srt = List.of("countDesc", "oldestFirst", "scoreDesc").get(r.nextInt(3));
            CursorUtil.CursorParts p = CursorUtil.decode(CursorUtil.encode(tok, off, srt));
            assertEquals(tok, p.googlePageToken());
            assertEquals(off, p.localOffset());
            assertEquals(srt, p.localSort());
        }

        assertThrows(IllegalArgumentException.class,
            () -> CursorUtil.decode("not-a-valid-base64!!!*_-(garbled-chars-that-fail"));

        String bad2part = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("a|b".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class,
            () -> CursorUtil.decode(bad2part));

        CursorUtil.CursorParts fp = CursorUtil.decode(
            CursorUtil.encode("", 0, "countDesc"));
        assertEquals("", fp.googlePageToken());
        assertEquals(0L, fp.localOffset());
        assertEquals("countDesc", fp.localSort());
    }

    @Test
    @DisplayName("T6_a_06 SenderAggregator.bulkMarketingScore baseline zero points")
    void T6_a_06_bulkScoreBaselineZero() {
        SenderAggregator.SenderAccumulator acc1 = new SenderAggregator.SenderAccumulator();
        acc1.totalCount = 1;
        acc1.replyCount = 0;
        acc1.oldestMessageAt = Instant.now().minus(30, ChronoUnit.DAYS);
        acc1.hasListUnsubscribe = false;
        acc1.hasPrecedenceBulk = false;
        assertEquals(0, SenderAggregator.bulkMarketingScore(acc1, Instant.now()));

        SenderAggregator.SenderAccumulator acc2 = new SenderAggregator.SenderAccumulator();
        acc2.totalCount = 3;
        acc2.replyCount = 1;
        acc2.oldestMessageAt = Instant.now().minus(30, ChronoUnit.DAYS);
        acc2.hasListUnsubscribe = false;
        acc2.hasPrecedenceBulk = false;
        assertEquals(0, SenderAggregator.bulkMarketingScore(acc2, Instant.now()));
    }

    @Test
    @DisplayName("T6_a_07 SenderAggregator.bulkMarketingScore award each of 3 points")
    void T6_a_07_bulkScoreAwardEachPoint() {
        Instant now = Instant.parse("2026-08-07T00:00:00Z");

        SenderAggregator.SenderAccumulator sA = new SenderAggregator.SenderAccumulator();
        sA.totalCount = 5;
        sA.replyCount = 2;
        sA.oldestMessageAt = Instant.parse("2026-07-01T00:00:00Z");
        sA.hasListUnsubscribe = true;
        sA.hasPrecedenceBulk = true;
        assertEquals(2, SenderAggregator.bulkMarketingScore(sA, now));

        SenderAggregator.SenderAccumulator sB = new SenderAggregator.SenderAccumulator();
        sB.totalCount = 5;
        sB.replyCount = 0;
        sB.oldestMessageAt = Instant.parse("2026-07-28T00:00:00Z");
        sB.hasListUnsubscribe = false;
        sB.hasPrecedenceBulk = false;
        assertEquals(1, SenderAggregator.bulkMarketingScore(sB, now));

        SenderAggregator.SenderAccumulator sC = new SenderAggregator.SenderAccumulator();
        sC.totalCount = 10;
        sC.replyCount = 0;
        sC.oldestMessageAt = Instant.parse("2026-07-01T00:00:00Z");
        sC.hasListUnsubscribe = true;
        sC.hasPrecedenceBulk = true;
        assertEquals(3, SenderAggregator.bulkMarketingScore(sC, now));
    }

    @Test
    @DisplayName("T6_a_08 SenderAggregator.bulkMarketingScore edge totalCount=1 skips age point")
    void T6_a_08_bulkScoreTotalOne() {
        SenderAggregator.SenderAccumulator acc = new SenderAggregator.SenderAccumulator();
        acc.totalCount = 1;
        acc.replyCount = 0;
        acc.oldestMessageAt = Instant.now().minus(30, ChronoUnit.DAYS);
        acc.hasListUnsubscribe = false;
        acc.hasPrecedenceBulk = false;
        assertEquals(0, SenderAggregator.bulkMarketingScore(acc, Instant.now()));
    }

    private static Map<String, List<String>> headers(String from, String to,
                                                     String subject,
                                                     String listUnsub,
                                                     String precedence) {
        Map<String, List<String>> h = new LinkedHashMap<>();
        if (from != null) h.put("From", List.of(from));
        if (to != null) h.put("To", List.of(to));
        if (subject != null) h.put("Subject", List.of(subject));
        if (listUnsub != null) h.put("List-Unsubscribe", List.of(listUnsub));
        if (precedence != null) h.put("Precedence", List.of(precedence));
        return h;
    }

    private static MailMessage msg(String id, String threadId, String from, String to,
                                   String subject, Instant receivedAt, Set<String> labels,
                                   String listUnsub, String precedence) {
        return new MailMessage(id, threadId, "google", receivedAt,
            headers(from, to, subject, listUnsub, precedence),
            labels == null ? Set.of() : labels);
    }

    @Test
    @DisplayName("T6_a_09 SenderAggregator.aggregate on 10 messages — 4 unique senders, header flags")
    void T6_a_09_aggregateTenMessages() {
        List<MailMessage> msgs = new ArrayList<>();
        Instant base = Instant.parse("2026-08-01T00:00:00Z");

        msgs.add(msg("m1", "t1", "Newsletter <news@example.com>",
            "me@example.com", "Weekly digest",
            base, Set.of("UNREAD"),
            "<https://unsub.example.com/u/1>", null));
        msgs.add(msg("m2", "t2", "news@example.com",
            "me@example.com", "Digest v2",
            base.plus(1, ChronoUnit.HOURS), Set.of(),
            null, null));
        msgs.add(msg("m3", "t3", "Alice <alice@work.com>",
            "me@example.com", "Project kickoff",
            base.plus(2, ChronoUnit.HOURS), Set.of("UNREAD"),
            null, null));
        msgs.add(msg("m4", "t4", "Alice <alice@work.com>",
            "me@example.com", "Re: kickoff",
            base.plus(3, ChronoUnit.HOURS), Set.of(),
            null, null));
        msgs.add(msg("m5", "t5", "Alice <alice@work.com>",
            "me@example.com", "Follow-up",
            base.plus(10, ChronoUnit.HOURS), Set.of("UNREAD"),
            null, null));
        msgs.add(msg("m6", "t6", "Bob <bob@work.com>",
            "me@example.com", "Lunch?",
            base.plus(4, ChronoUnit.HOURS), Set.of(),
            null, null));
        msgs.add(msg("m7", "t7", "Bob <bob@work.com>",
            "me@example.com", "Re: lunch",
            base.plus(5, ChronoUnit.HOURS), Set.of("UNREAD"),
            null, null));
        msgs.add(msg("m8", "t8", "HR <hr@corp.com>",
            "me@example.com", "Benefits update",
            base.plus(6, ChronoUnit.HOURS), Set.of(),
            null, "bulk"));
        msgs.add(msg("m9", "t9", "HR <hr@corp.com>",
            "me@example.com", "Payroll reminder",
            base.plus(7, ChronoUnit.HOURS), Set.of("UNREAD"),
            null, "bulk"));
        msgs.add(msg("m10", "t10", "news@example.com",
            "me@example.com", "Promo",
            base.plus(12, ChronoUnit.HOURS), Set.of(),
            null, null));

        SenderAggregator agg = new SenderAggregator(null);
        SenderAggregator.AggregationResult res = agg.aggregate(msgs);

        assertEquals(4, res.perSender().size());
        assertEquals(10, res.totalMessagesScanned());

        SenderAggregator.SenderAccumulator news = res.perSender().get("news@example.com");
        assertNotNull(news);
        assertEquals(3, news.totalCount);
        assertTrue(news.hasListUnsubscribe);
        assertFalse(news.hasPrecedenceBulk);
        assertTrue(news.sampleMessageIds.contains("m1"));
        assertEquals(3, news.sampleMessageIds.size());

        SenderAggregator.SenderAccumulator hr = res.perSender().get("hr@corp.com");
        assertNotNull(hr);
        assertTrue(hr.hasPrecedenceBulk);
        assertEquals(2, hr.totalCount);

        SenderAggregator.SenderAccumulator alice = res.perSender().get("alice@work.com");
        assertNotNull(alice);
        assertEquals(3, alice.totalCount);
    }

    // ============================================================
    // T6(c): toPageResponse sort invariants + controller wiring
    // Pure Java + Mockito. No Spring context.
    // ============================================================

    private static SenderAggregator.SenderAccumulator makeAcc(
            String fromAddress, String fromDomain, int totalCount,
            int replyCount, boolean listUnsub, boolean precBulk,
            Instant oldest) {
        SenderAggregator.SenderAccumulator a = new SenderAggregator.SenderAccumulator();
        a.fromAddress = fromAddress;
        a.fromDisplayName = fromAddress;
        a.fromDomain = fromDomain;
        a.totalCount = totalCount;
        a.replyCount = replyCount;
        a.hasListUnsubscribe = listUnsub;
        a.hasPrecedenceBulk = precBulk;
        a.oldestMessageAt = oldest;
        a.newestMessageAt = oldest;
        return a;
    }

    @Test
    @DisplayName("T6_c_01 SenderAggregator.toPageResponse sort invariants across 4 sort values")
    void T6_c_01_toPageResponseSortInvariants() {
        Instant now = Instant.parse("2026-08-07T00:00:00Z");
        Instant d1 = now.minus(1, ChronoUnit.DAYS);
        Instant d5 = now.minus(5, ChronoUnit.DAYS);
        Instant d30 = now.minus(30, ChronoUnit.DAYS);
        Instant d120 = now.minus(120, ChronoUnit.DAYS);

        Map<String, SenderAggregator.SenderAccumulator> perSender = new LinkedHashMap<>();
        perSender.put("a@a.com", makeAcc("a@a.com", "a.com", 100, 0, true, true, d30));
        perSender.put("b@b.com", makeAcc("b@b.com", "b.com", 50, 0, false, false, d5));
        perSender.put("c@c.com", makeAcc("c@c.com", "c.com", 75, 0, true, false, d120));
        perSender.put("d@d.com", makeAcc("d@d.com", "d.com", 25, 0, false, false, d1));

        SenderAggregator.AggregationResult agg =
            new SenderAggregator.AggregationResult(perSender, 250);
        SenderAggregator aggSvc = new SenderAggregator(null);

        SenderPageResponse pCount = aggSvc.toPageResponse(agg, null, "countDesc");
        assertEquals("a@a.com", pCount.items().get(0).fromAddress());
        assertEquals("c@c.com", pCount.items().get(1).fromAddress());
        assertEquals("b@b.com", pCount.items().get(2).fromAddress());
        assertEquals("d@d.com", pCount.items().get(3).fromAddress());

        SenderPageResponse pScore = aggSvc.toPageResponse(agg, null, "scoreDesc");
        assertEquals(3, pScore.items().get(0).bulkMarketingScore());
        assertEquals("a@a.com", pScore.items().get(0).fromAddress());
        assertEquals(2, pScore.items().get(1).bulkMarketingScore());
        assertEquals("c@c.com", pScore.items().get(1).fromAddress());
        assertEquals("b@b.com", pScore.items().get(2).fromAddress());
        assertEquals("d@d.com", pScore.items().get(3).fromAddress());
        assertTrue(pScore.items().get(2).fromAddress()
            .compareTo(pScore.items().get(3).fromAddress()) < 0);

        SenderPageResponse pOld = aggSvc.toPageResponse(agg, null, "oldestFirst");
        assertEquals("c@c.com", pOld.items().get(0).fromAddress());
        assertEquals("a@a.com", pOld.items().get(1).fromAddress());
        assertEquals("b@b.com", pOld.items().get(2).fromAddress());
        assertEquals("d@d.com", pOld.items().get(3).fromAddress());

        SenderPageResponse pDefault = aggSvc.toPageResponse(agg, null, "bogus");
        assertEquals(pCount.items().get(0).fromAddress(), pDefault.items().get(0).fromAddress());
        assertEquals(pCount.items().size(), pDefault.items().size());

        assertEquals(250, pCount.totalMessagesScanned());
        assertEquals(4, pCount.totalUniqueSenders());
        assertEquals(4, pCount.domainRollups().size());
        List<DomainRollup> dr = pCount.domainRollups();
        for (int i = 1; i < dr.size(); i++) {
            assertTrue(dr.get(i - 1).countAcrossAllSenders() >= dr.get(i).countAcrossAllSenders());
        }
    }

    @Test
    @DisplayName("T6_c_02 SenderAggregationController wiring via direct Java call + Mockito")
    void T6_c_02_controllerWiringDirectCall() {
        MailProvider mockProvider = mock(MailProvider.class);
        UserAccountRepository mockRepo = mock(UserAccountRepository.class);

        List<MailMessage> fixtures = new ArrayList<>();
        Instant base = Instant.parse("2026-08-01T00:00:00Z");
        fixtures.add(msg("cm1", "ct1", "S1 <s1@x.com>", "me@example.com", "Hi",
            base, Set.of(), null, null));
        fixtures.add(msg("cm2", "ct2", "S1 <s1@x.com>", "me@example.com", "Hi2",
            base.plus(1, ChronoUnit.HOURS), Set.of(), null, null));
        fixtures.add(msg("cm3", "ct3", "S2 <s2@y.com>", "me@example.com", "Hey",
            base.plus(2, ChronoUnit.HOURS), Set.of(),
            "<https://unsub>", "bulk"));
        fixtures.add(msg("cm4", "ct4", "S3 <s3@z.com>", "me@example.com", "Yo",
            base.plus(3, ChronoUnit.HOURS), Set.of(), null, null));

        when(mockProvider.listMessages(any(), any(), anyInt()))
            .thenReturn(PageResult.of(fixtures, "page2tok", 20));

        Map<String, MailProvider> providerMap = new HashMap<>();
        providerMap.put("gmailMailService", mockProvider);

        SenderStatsPersistService mockPersist = mock(SenderStatsPersistService.class);
        when(mockPersist.upsertSnapshot(any(), any())).thenReturn(List.of());

        SenderAggregator realAgg = new SenderAggregator(mockPersist);
        ReplyZeroDetector realRzd = new ReplyZeroDetector();

        SenderAggregationController controller = new SenderAggregationController(
            providerMap, mockRepo, realAgg, realRzd, mockPersist);

        OAuth2User principal = mock(OAuth2User.class);
        when(principal.getAttribute("email")).thenReturn("me@example.com");
        Set<GrantedAuthority> authorities = Set.of(new SimpleGrantedAuthority("ROLE_USER"));
        OAuth2AuthenticationToken token = new OAuth2AuthenticationToken(
            principal, authorities, "google");

        UserAccount me = new UserAccount();
        me.setId(101L);
        me.setEmail("me@example.com");
        me.setProvider("google");
        when(mockRepo.findByEmailAndProvider("me@example.com", "google"))
            .thenReturn(java.util.Optional.of(me));

        SenderPageResponse resp = controller.getSenders(null, 20, "countDesc", token);
        assertNotNull(resp);
        assertFalse(resp.items().isEmpty());
        assertEquals(3, resp.items().size());
        assertNotNull(resp.nextCursor());
        CursorUtil.CursorParts cp = CursorUtil.decode(resp.nextCursor());
        assertEquals("page2tok", cp.googlePageToken());
        assertEquals(1L, cp.localOffset());
    }

    // ============================================================
    // T6(d): ReplyZeroDetector with FakeMailProvider
    // Pure Java. No Spring context.
    // ============================================================

    static class FakeMailProvider implements MailProvider {
        private final Map<String, MailThreadSummary> threadMap = new HashMap<>();

        void put(String threadId, List<String> participants, Instant at,
                 List<String> orderedIds) {
            Set<String> domains = new HashSet<>();
            for (String p : participants) {
                String d = Rfc5322Util.extractDomain(p);
                if (d != null) domains.add(d);
            }
            threadMap.put(threadId, new MailThreadSummary(
                threadId, participants, domains,
                orderedIds.size(), at, at, orderedIds));
        }

        @Override public MailThreadSummary getThreadMetadata(UserAccount me, String tid) {
            MailThreadSummary s = threadMap.get(tid);
            if (s == null) {
                return new MailThreadSummary(tid, List.of(), Set.of(), 0,
                    null, null, List.of());
            }
            return s;
        }

        @Override public PageResult<MailMessage> listMessages(UserAccount a, String pt, int n) {
            throw new UnsupportedOperationException(); }
        @Override public List<MailFolderOrLabel> listFoldersOrLabels(UserAccount a) {
            throw new UnsupportedOperationException(); }
        @Override public BulkActionResult batchModify(UserAccount a, BulkActionRequest r) {
            throw new UnsupportedOperationException(); }
        @Override public BulkActionResult applyLabel(UserAccount a, List<String> ids,
                                                       String lid, boolean add) {
            throw new UnsupportedOperationException(); }
    }

    @Test
    @DisplayName("T6_d_01 ReplyZeroDetector.analyze — exactly 1 reply-zero from 5 candidates")
    void T6_d_01_replyZeroDetectorOneMatch() {
        Instant now = Instant.parse("2026-08-07T12:00:00Z");
        Instant tAge7d = now.minus(7, ChronoUnit.DAYS);
        Instant tAge2d = now.minus(2, ChronoUnit.DAYS);

        List<MailMessage> all = new ArrayList<>();
        all.add(msg("m1", "t1", "Alice Friend <alice@example.com>",
            "me@example.com, carol@example.com", "Project sync notes",
            tAge7d, Set.of(), null, null));
        all.add(msg("m2", "t2", "Bob Coworker <bob@example.com>",
            "me@example.com", "Doc review",
            tAge7d, Set.of(), null, null));
        all.add(msg("m3", "t3", "News <news@bulk-esp.example.sendgrid.net>",
            "me@example.com", "Bulk promo",
            tAge7d, Set.of(), null, null));
        all.add(msg("m4", "t4", "Dana <dana@example.com>",
            "me@example.com", "Quick question",
            tAge2d, Set.of(), null, null));
        all.add(msg("m5", "t5", "Phishy <diamond@hotmail.ru>",
            "me@example.com", "You won!",
            tAge7d, Set.of(), null, null));

        FakeMailProvider fake = new FakeMailProvider();
        fake.put("t1", List.of("Alice <alice@example.com>"), tAge7d, List.of("m1"));
        fake.put("t2", List.of("Bob <bob@example.com>", "Me <me@example.com>"),
            tAge7d, List.of("m2"));

        UserAccount me = new UserAccount();
        me.setEmail("me@example.com");
        me.setId(1L);
        me.setProvider("google");
        me.setProviderAccountId("prov1");

        SenderAggregator agg = new SenderAggregator(null);
        SenderAggregator.AggregationResult aggRes = agg.aggregate(all);
        var senderAggs = aggRes.perSender();

        ReplyZeroDetector rzd = new ReplyZeroDetector();
        List<ReplyZeroItem> items = rzd.analyze(all, senderAggs, me, fake, 3, now);

        assertEquals(1, items.size());
        ReplyZeroItem it = items.get(0);
        assertEquals("t1", it.threadId());
        assertEquals("alice@example.com", it.fromAddress());
        assertEquals(7L, it.ageDays());
        assertFalse(it.bulkSender());
        assertEquals(1, it.participantCount());
        assertEquals("Project sync notes", it.subject());
    }

    // ============================================================
    // T6(b): SenderStats persistence — @SpringBootTest with JPA
    // ============================================================

    @Nested
    @SpringBootTest
    @TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "TOKEN_ENCRYPT_SECRET=0000000000000000000000000000000000000000000000000000000000000000",
        "TOKEN_ENCRYPT_SALT=00000000000000000000000000000000",
        "GOOGLE_CLIENT_ID=",
        "GOOGLE_CLIENT_SECRET=",
        "MICROSOFT_CLIENT_ID=",
        "MICROSOFT_CLIENT_SECRET=",
        "MICROSOFT_TENANT_ID=common"
    })
    class T6b_PersistenceTests {

        @Autowired
        SenderStatRepository repo;

        @Autowired
        UserAccountRepository userAccounts;

        @Autowired
        CryptoUtil cryptoUtil;

        @Autowired
        EntityManager em;

        @BeforeEach
        void cleanSenderStats() {
            repo.deleteAll();
            userAccounts.deleteAll();
        }

        private Long createAccount() {
            UserAccount a = new UserAccount();
            a.setEmail("phase6b-test@example.com");
            a.setProvider("google");
            a.setProviderAccountId("phase6b-prov-1");
            a.setDisplayName("Phase6B");
            a.setEncryptedAccessToken(cryptoUtil.encrypt("at".getBytes(StandardCharsets.UTF_8)));
            a.setEncryptedRefreshToken(cryptoUtil.encrypt("rt".getBytes(StandardCharsets.UTF_8)));
            a.setScopes(List.of("openid", "email"));
            return userAccounts.save(a).getId();
        }

        @Test
        @DisplayName("T6_b_01 upsertSnapshot 2 passes — latest-wins merge, untouched rows remain")
        void T6_b_01_upsertTwoPasses() {
            Long accountId = createAccount();
            SenderStatsPersistService svc = new SenderStatsPersistService(repo);

            Instant oldestA = Instant.parse("2026-01-01T00:00:00Z");
            Instant newestA1 = Instant.parse("2026-01-31T00:00:00Z");

            List<SenderStatsPersistService.SenderStatDelta> pass1 = List.of(
                new SenderStatsPersistService.SenderStatDelta(
                    "a@x.com", "A", "x.com",
                    5, 2, false, false,
                    oldestA, newestA1, 0),
                new SenderStatsPersistService.SenderStatDelta(
                    "b@x.com", "B", "x.com",
                    12, 4, false, false,
                    oldestA, newestA1, 1),
                new SenderStatsPersistService.SenderStatDelta(
                    "c@y.com", "C", "y.com",
                    1, 0, false, false,
                    oldestA, newestA1, 0)
            );
            svc.upsertSnapshot(accountId, pass1);

            assertEquals(3L, repo.countByAccountId(accountId));
            SenderStat a1 = repo.findByAccountIdAndFromAddress(accountId, "a@x.com").orElseThrow();
            assertEquals(5, a1.getTotalCount());
            assertEquals(2, a1.getUnreadCount());
            assertEquals(0, a1.getReplyCount());
            assertNotNull(a1.getLastSyncedAt());
            SenderStat b1 = repo.findByAccountIdAndFromAddress(accountId, "b@x.com").orElseThrow();
            assertEquals(12, b1.getTotalCount());
            assertEquals(1, b1.getReplyCount());
            SenderStat c1 = repo.findByAccountIdAndFromAddress(accountId, "c@y.com").orElseThrow();
            assertEquals(1, c1.getTotalCount());

            Instant newestA2 = Instant.parse("2026-02-15T00:00:00Z");
            List<SenderStatsPersistService.SenderStatDelta> pass2 = List.of(
                new SenderStatsPersistService.SenderStatDelta(
                    "a@x.com", "A", "x.com",
                    7, 3, false, false,
                    oldestA, newestA2, 2),
                new SenderStatsPersistService.SenderStatDelta(
                    "d@z.com", "D", "z.com",
                    200, 50, true, true,
                    oldestA, newestA2, 5)
            );
            svc.upsertSnapshot(accountId, pass2);

            assertEquals(4L, repo.countByAccountId(accountId));
            SenderStat a2 = repo.findByAccountIdAndFromAddress(accountId, "a@x.com").orElseThrow();
            assertEquals(7, a2.getTotalCount());
            assertEquals(3, a2.getUnreadCount());
            assertEquals(2, a2.getReplyCount());
            assertEquals(newestA2, a2.getNewestMessageAt());
            SenderStat b2 = repo.findByAccountIdAndFromAddress(accountId, "b@x.com").orElseThrow();
            assertEquals(12, b2.getTotalCount());
            assertEquals(1, b2.getReplyCount());
            SenderStat d2 = repo.findByAccountIdAndFromAddress(accountId, "d@z.com").orElseThrow();
            assertEquals(200, d2.getTotalCount());
        }
    }

    // ============================================================
    // T6(e): Full-stack sender API MVC integration test
    // ============================================================

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "GOOGLE_CLIENT_ID=your-dummy-google-client-id",
        "GOOGLE_CLIENT_SECRET=your-dummy-google-secret",
        "SYNC_GMAIL_API_CONCURRENCY=2",
        "SYNC_GMAIL_BATCH_DELAY_MS=0",
        "TOKEN_ENCRYPT_SECRET=00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
        "TOKEN_ENCRYPT_SALT=ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100",
        "MICROSOFT_CLIENT_ID=",
        "MICROSOFT_CLIENT_SECRET=",
        "MICROSOFT_TENANT_ID=common",
    })
    class T6e_MvcIntegrationTests {

        @Autowired
        MockMvc mockMvc;

        @Autowired
        UserAccountRepository userAccounts;

        @Autowired
        SenderStatRepository senderStats;

        @Autowired
        CryptoUtil cryptoUtil;

        @MockBean(name = "gmailMailService")
        MailProvider gmailProvider;

        private UserAccount insertMeAccount() {
            UserAccount me = new UserAccount();
            me.setEmail("me@example.com");
            me.setProvider("google");
            me.setProviderAccountId("google-acc-42");
            me.setDisplayName("Me Tester");
            me.setEncryptedAccessToken(cryptoUtil.encrypt("fake-access".getBytes(StandardCharsets.UTF_8)));
            me.setEncryptedRefreshToken(cryptoUtil.encrypt("fake-refresh".getBytes(StandardCharsets.UTF_8)));
            me.setScopes(List.of("openid", "email", "profile"));
            me.setAesKeyId("hkdf-sha256-v1");
            return userAccounts.save(me);
        }

        @BeforeEach
        void cleanDb() {
            senderStats.deleteAll();
            userAccounts.deleteAll();
        }

        private OAuth2AuthenticationToken buildAuthToken(String email, String regId) {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("email", email);
            attrs.put("sub", "fake-sub-" + email);
            List<GrantedAuthority> auths = List.of(new SimpleGrantedAuthority("ROLE_USER"));
            DefaultOAuth2User user = new DefaultOAuth2User(auths, attrs, "sub");
            return new OAuth2AuthenticationToken(user, auths, regId);
        }

        private List<MailMessage> build40MsgDataset() {
            Instant now = Instant.parse("2026-08-07T12:00:00Z");
            List<MailMessage> out = new ArrayList<>(40);
            String[] newsletters = {
                "news@news1.com", "digest@news2.com", "promo@news3.com", "offers@promo.retailer.com"
            };
            int idc = 0;
            for (int i = 0; i < newsletters.length; i++) {
                String from = newsletters[i];
                String unsub = "<https://" + from.substring(from.indexOf('@') + 1) + "/u>";
                String prec = i == 3 ? "bulk" : null;
                for (int j = 0; j < 5; j++, idc++) {
                    out.add(msg("e" + idc, "et" + idc,
                        "News <" + from + ">",
                        "me@example.com",
                        "Newsletter " + i + "-" + j,
                        now.minus(10 + j, ChronoUnit.DAYS),
                        j % 2 == 0 ? Set.of("UNREAD") : Set.of(),
                        unsub, prec));
                }
            }
            String[] personalsame = { "personal1@example.com", "personal2@example.com" };
            for (int i = 0; i < personalsame.length; i++) {
                long age = 5 - i;
                out.add(msg("e" + idc, "epz" + i,
                    "Pers " + i + " <" + personalsame[i] + ">",
                    "me@example.com, teamlead@example.com",
                    "Personal msg " + i,
                    now.minus(age, ChronoUnit.DAYS),
                    Set.of(),
                    null, null));
                idc++;
            }
            int remaining = 40 - out.size();
            for (int i = 0; i < remaining; i++, idc++) {
                String from = "sender" + i + "@domain" + (i % 10) + ".com";
                out.add(msg("e" + idc, "ez" + idc,
                    "Sender " + i + " <" + from + ">",
                    "me@example.com",
                    "Random " + i,
                    now.minus(20 + i, ChronoUnit.DAYS),
                    i % 3 == 0 ? Set.of("UNREAD") : Set.of(),
                    null, null));
            }
            return out;
        }

        @Test
        @DisplayName("T6_e_01 GET /api/senders?sort=scoreDesc — score non-increasing, top ≥2")
        void T6_e_01_sendersScoreDescMvc() throws Exception {
            insertMeAccount();
            List<MailMessage> data = build40MsgDataset();
            when(gmailProvider.listMessages(any(), any(), anyInt()))
                .thenReturn(PageResult.of(data, null, 100));

            mockMvc.perform(get("/api/senders")
                    .param("sort", "scoreDesc")
                    .param("size", "10")
                    .with(authentication(buildAuthToken("me@example.com", "google"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(
                    org.hamcrest.Matchers.greaterThanOrEqualTo(10)))
                .andExpect(jsonPath("$.items[0].bulkMarketingScore").value(
                    org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.items[1].bulkMarketingScore").value(
                    org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.items[2].bulkMarketingScore").value(
                    org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.totalUniqueSenders").value(
                    org.hamcrest.Matchers.greaterThanOrEqualTo(20)))
                .andExpect(jsonPath("$.totalMessagesScanned").value(40));
        }

        @Test
        @DisplayName("T6_e_02 GET /api/reply-zero?olderThanDays=3 — 2 personal threads reply-zero")
        void T6_e_02_replyZeroMvc() throws Exception {
            UserAccount me = insertMeAccount();
            List<MailMessage> data = build40MsgDataset();
            when(gmailProvider.listMessages(any(), any(), anyInt()))
                .thenReturn(PageResult.of(data, null, 100));

            MailThreadSummary empty = new MailThreadSummary(
                null, List.of(), Set.of(), 0, null, null, List.of());
            when(gmailProvider.getThreadMetadata(any(), any())).thenReturn(empty);

            when(gmailProvider.getThreadMetadata(eq(me), eq("epz0")))
                .thenReturn(new MailThreadSummary("epz0",
                    List.of("Pers 0 <personal1@example.com>"),
                    Set.of("example.com"),
                    1,
                    Instant.parse("2026-08-07T12:00:00Z").minus(5, ChronoUnit.DAYS),
                    Instant.parse("2026-08-07T12:00:00Z").minus(5, ChronoUnit.DAYS),
                    List.of("e20")));
            when(gmailProvider.getThreadMetadata(eq(me), eq("epz1")))
                .thenReturn(new MailThreadSummary("epz1",
                    List.of("Pers 1 <personal2@example.com>"),
                    Set.of("example.com"),
                    1,
                    Instant.parse("2026-08-07T12:00:00Z").minus(4, ChronoUnit.DAYS),
                    Instant.parse("2026-08-07T12:00:00Z").minus(4, ChronoUnit.DAYS),
                    List.of("e21")));

            mockMvc.perform(get("/api/reply-zero")
                    .param("olderThanDays", "3")
                    .with(authentication(buildAuthToken("me@example.com", "google"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(
                    org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
        }
    }
}
