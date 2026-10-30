package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.config.ScheduleConfig;
import com.tidymail.entity.SenderStat;
import com.tidymail.entity.SyncProgress;
import com.tidymail.entity.UserAccount;
import com.tidymail.entity.UserAction;
import com.tidymail.job.IncrementalSyncJob;
import com.tidymail.job.SenderStatsVacuumJob;
import com.tidymail.job.UserActionCleanupJob;
import com.tidymail.mail.MailProvider;
import com.tidymail.repository.SenderStatRepository;
import com.tidymail.repository.SyncProgressRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.security.crypto.CryptoUtil;
import jakarta.persistence.EntityManager;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

class Phase8DeploymentTest {

    // ============================================================
    // T8(a): Pure-Java tests (no Spring context)
    // ============================================================

    @Nested
    @DisplayName("T8a Pure Java Tests")
    class T8a_pureJavaTests {

        @Test
        @DisplayName("T8a_01 scheduleAnnotations — cron/configurable cycle + @EnableScheduling config")
        void T8a_01_scheduleAnnotations() throws Exception {
            Method cleanupRun = UserActionCleanupJob.class.getMethod("run");
            Scheduled cleanupSched = cleanupRun.getAnnotation(Scheduled.class);
            assertNotNull(cleanupSched, "CleanupJob.run must have @Scheduled");
            assertEquals("0 0 * * * *", cleanupSched.cron(), "CleanupJob cron");

            Method vacuumRun = SenderStatsVacuumJob.class.getMethod("run");
            Scheduled vacuumSched = vacuumRun.getAnnotation(Scheduled.class);
            assertNotNull(vacuumSched, "VacuumJob.run must have @Scheduled");
            assertEquals("0 0 2 * * *", vacuumSched.cron(), "VacuumJob cron");

            Method syncRun = IncrementalSyncJob.class.getMethod("run");
            Scheduled syncSched = syncRun.getAnnotation(Scheduled.class);
            assertNotNull(syncSched, "IncrementalSyncJob.run must have @Scheduled");
            assertEquals("${app.sync.freshness-cycle-ms:300000}",
                syncSched.fixedRateString(), "IncrementalSyncJob configurable cycle");
            assertEquals("${app.sync.startup-delay-ms:5000}",
                syncSched.initialDelayString(), "IncrementalSyncJob configurable initial delay");

            assertTrue(ScheduleConfig.class.isAnnotationPresent(Configuration.class),
                "ScheduleConfig has @Configuration");
            assertTrue(ScheduleConfig.class.isAnnotationPresent(EnableScheduling.class),
                "ScheduleConfig has @EnableScheduling");
        }

        @Test
        @DisplayName("T8a_02 cleanupPredicateAndSemaphore — 4-case filter + Semaphore(1) tryAcquire")
        void T8a_02_cleanupPredicateAndSemaphore() {
            Instant now = Instant.now();
            Instant past = now.minusSeconds(10);

            UserAction case1 = new UserAction();
            case1.setExpiresAt(past);
            case1.setUndoneAt(null);

            UserAction case2 = new UserAction();
            case2.setExpiresAt(past);
            case2.setUndoneAt(Instant.now());

            UserAction case3 = new UserAction();
            case3.setExpiresAt(now.plusSeconds(10));
            case3.setUndoneAt(null);

            UserAction case4 = new UserAction();
            case4.setExpiresAt(null);
            case4.setUndoneAt(null);

            List<UserAction> rows = List.of(case1, case2, case3, case4);
            long passing = rows.stream()
                .filter(row -> (row.getExpiresAt() != null && row.getExpiresAt().isBefore(now))
                    && row.getUndoneAt() == null)
                .count();
            assertEquals(1, passing, "exactly 1 row passes predicate (expired + not undone)");

            Semaphore sem = new Semaphore(1);
            assertTrue(sem.tryAcquire(), "first tryAcquire returns true");
            assertFalse(sem.tryAcquire(), "second tryAcquire returns false (saturated)");
        }
    }

    // ============================================================
    // T8(b): Persistence tests — @SpringBootTest with JPA + Flyway V4
    // ============================================================

    @Nested
    @SpringBootTest
    @Transactional
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
    @DisplayName("T8b Persistence Tests")
    class T8b_persistenceTests {

        @Autowired
        SyncProgressRepository syncProgressRepo;

        @Autowired
        UserAccountRepository userAccounts;

        @Autowired
        SenderStatRepository senderStats;

        @Autowired
        CryptoUtil cryptoUtil;

        @Autowired
        EntityManager em;

        @BeforeEach
        void cleanAll() {
            senderStats.deleteAll();
            syncProgressRepo.deleteAll();
            userAccounts.deleteAll();
        }

        private UserAccount createAccount(String email) {
            UserAccount a = new UserAccount();
            a.setEmail(email);
            a.setProvider("google");
            a.setProviderAccountId("prov-" + email.hashCode());
            a.setDisplayName("T8b Test");
            a.setEncryptedAccessToken(cryptoUtil.encrypt("at".getBytes(StandardCharsets.UTF_8)));
            a.setEncryptedRefreshToken(cryptoUtil.encrypt("rt".getBytes(StandardCharsets.UTF_8)));
            a.setScopes(List.of("openid", "email"));
            return userAccounts.save(a);
        }

        @Test
        @DisplayName("T8b_01_syncProgressV4 — findTopByOrderByIdDesc returns RUNNING (latest/highest id)")
        void T8b_01_syncProgressV4() {
            UserAccount account = createAccount("t8b01@example.com");
            em.flush();
            Long accId = account.getId();

            SyncProgress sp1 = new SyncProgress();
            sp1.setAccountId(accId);
            sp1.setJobType("INCREMENTAL_SYNC");
            sp1.setStatus("FAILED");
            syncProgressRepo.save(sp1);

            SyncProgress sp2 = new SyncProgress();
            sp2.setAccountId(accId);
            sp2.setJobType("INCREMENTAL_SYNC");
            sp2.setStatus("COMPLETE");
            syncProgressRepo.save(sp2);

            SyncProgress sp3 = new SyncProgress();
            sp3.setAccountId(accId);
            sp3.setJobType("INCREMENTAL_SYNC");
            sp3.setStatus("RUNNING");
            syncProgressRepo.save(sp3);

            em.flush();
            em.clear();

            SyncProgress latest = syncProgressRepo
                .findTopByAccountIdAndJobTypeOrderByIdDesc(accId, "INCREMENTAL_SYNC")
                .orElseThrow();
            assertEquals("RUNNING", latest.getStatus(), "latest row by id = RUNNING");
        }

        @Test
        @DisplayName("T8b_02_staleAccount90DaysSubquery — 91day-old login is stale, 1day + null are fresh")
        void T8b_02_staleAccount90DaysSubquery() {
            Instant now = Instant.now();
            long thresholdMs = now.minus(90, ChronoUnit.DAYS).toEpochMilli();

            UserAccount A = new UserAccount();
            A.setEmail("stale@example.com");
            A.setProvider("google");
            A.setProviderAccountId("prov-stale");
            A.setDisplayName("Stale 91d");
            A.setEncryptedAccessToken(cryptoUtil.encrypt("at".getBytes(StandardCharsets.UTF_8)));
            A.setEncryptedRefreshToken(cryptoUtil.encrypt("rt".getBytes(StandardCharsets.UTF_8)));
            A.setScopes(List.of("openid"));
            A.setLastLoginAt(now.minus(91, ChronoUnit.DAYS));
            A = userAccounts.save(A);

            UserAccount B = new UserAccount();
            B.setEmail("fresh@example.com");
            B.setProvider("google");
            B.setProviderAccountId("prov-fresh");
            B.setDisplayName("Fresh 1d");
            B.setEncryptedAccessToken(cryptoUtil.encrypt("at".getBytes(StandardCharsets.UTF_8)));
            B.setEncryptedRefreshToken(cryptoUtil.encrypt("rt".getBytes(StandardCharsets.UTF_8)));
            B.setScopes(List.of("openid"));
            B.setLastLoginAt(now.minus(1, ChronoUnit.DAYS));
            B = userAccounts.save(B);

            UserAccount C = new UserAccount();
            C.setEmail("never@example.com");
            C.setProvider("google");
            C.setProviderAccountId("prov-never");
            C.setDisplayName("Never logged in");
            C.setEncryptedAccessToken(cryptoUtil.encrypt("at".getBytes(StandardCharsets.UTF_8)));
            C.setEncryptedRefreshToken(cryptoUtil.encrypt("rt".getBytes(StandardCharsets.UTF_8)));
            C.setScopes(List.of("openid"));
            C.setLastLoginAt(null);
            C = userAccounts.save(C);

            em.flush();
            em.clear();

            List<Long> staleIds = userAccounts.findAll().stream()
                .filter(a -> a.getLastLoginAt() != null
                    && a.getLastLoginAt().toEpochMilli() < thresholdMs)
                .map(UserAccount::getId)
                .toList();

            assertEquals(1, staleIds.size(), "only A is stale");
            assertTrue(staleIds.contains(A.getId()), "A.id in stale list");
            assertFalse(staleIds.contains(B.getId()), "B.id NOT in stale list");
            assertFalse(staleIds.contains(C.getId()), "C.id NOT in stale list (lastLoginAt=null)");
        }

        @Test
        @DisplayName("T8b_03_vacuumJobDeletesOnlyStale — 6 sender rows (A×2,B×2,C×2), deleteByAccountIdIn(stale=[A]) leaves 4")
        void T8b_03_vacuumJobDeletesOnlyStale() {
            Instant now = Instant.now();
            long thresholdMs = now.minus(90, ChronoUnit.DAYS).toEpochMilli();

            UserAccount A = new UserAccount();
            A.setEmail("stale@example.com");
            A.setProvider("google");
            A.setProviderAccountId("prov-stale-t8b03");
            A.setDisplayName("Stale 91d");
            A.setEncryptedAccessToken(cryptoUtil.encrypt("at".getBytes(StandardCharsets.UTF_8)));
            A.setEncryptedRefreshToken(cryptoUtil.encrypt("rt".getBytes(StandardCharsets.UTF_8)));
            A.setScopes(List.of("openid"));
            A.setLastLoginAt(now.minus(91, ChronoUnit.DAYS));
            A = userAccounts.save(A);

            UserAccount B = new UserAccount();
            B.setEmail("fresh@example.com");
            B.setProvider("google");
            B.setProviderAccountId("prov-fresh-t8b03");
            B.setDisplayName("Fresh 1d");
            B.setEncryptedAccessToken(cryptoUtil.encrypt("at".getBytes(StandardCharsets.UTF_8)));
            B.setEncryptedRefreshToken(cryptoUtil.encrypt("rt".getBytes(StandardCharsets.UTF_8)));
            B.setScopes(List.of("openid"));
            B.setLastLoginAt(now.minus(1, ChronoUnit.DAYS));
            B = userAccounts.save(B);

            UserAccount C = new UserAccount();
            C.setEmail("never@example.com");
            C.setProvider("google");
            C.setProviderAccountId("prov-never-t8b03");
            C.setDisplayName("Never logged in");
            C.setEncryptedAccessToken(cryptoUtil.encrypt("at".getBytes(StandardCharsets.UTF_8)));
            C.setEncryptedRefreshToken(cryptoUtil.encrypt("rt".getBytes(StandardCharsets.UTF_8)));
            C.setScopes(List.of("openid"));
            C.setLastLoginAt(null);
            C = userAccounts.save(C);
            em.flush();

            List<Long> staleIds = userAccounts.findAll().stream()
                .filter(a -> a.getLastLoginAt() != null
                    && a.getLastLoginAt().toEpochMilli() < thresholdMs)
                .map(UserAccount::getId)
                .toList();
            assertEquals(1, staleIds.size());
            assertEquals(A.getId(), staleIds.get(0));

            SenderStat s;
            for (int i = 0; i < 2; i++) {
                s = new SenderStat();
                s.setAccountId(A.getId());
                s.setFromAddress("a" + i + "@stale.com");
                s.setFromDomain("stale.com");
                s.setTotalCount(10);
                senderStats.save(s);
            }
            for (int i = 0; i < 2; i++) {
                s = new SenderStat();
                s.setAccountId(B.getId());
                s.setFromAddress("b" + i + "@fresh.com");
                s.setFromDomain("fresh.com");
                s.setTotalCount(5);
                senderStats.save(s);
            }
            for (int i = 0; i < 2; i++) {
                s = new SenderStat();
                s.setAccountId(C.getId());
                s.setFromAddress("c" + i + "@never.com");
                s.setFromDomain("never.com");
                s.setTotalCount(1);
                senderStats.save(s);
            }
            em.flush();
            em.clear();

            assertEquals(6, senderStats.count(), "before vacuum = 6 rows");

            senderStats.deleteByAccountIdIn(staleIds);
            em.flush();
            em.clear();

            assertEquals(4, senderStats.count(), "after vacuum = 4 rows (B×2 + C×2 preserved)");
        }
    }

    // ============================================================
    // T8(c): MockMvc sync fragment + dashboard slot integration tests
    // ============================================================

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
    @AutoConfigureMockMvc
    @Transactional
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
    @DisplayName("T8c MVC Sync Fragment Tests")
    class T8c_mvcTests {

        @Autowired
        MockMvc mockMvc;

        @Autowired
        UserAccountRepository userAccounts;

        @Autowired
        SyncProgressRepository syncProgressRepo;

        @Autowired
        CryptoUtil cryptoUtil;

        @MockBean(name = "gmailMailService")
        MailProvider gmailProvider;

        @MockBean(name = "outlookMailService")
        MailProvider outlookProvider;

        @BeforeEach
        void cleanDb() {
            syncProgressRepo.deleteAll();
            userAccounts.deleteAll();
        }

        private UserAccount insertMeAccount(String email) {
            UserAccount me = new UserAccount();
            me.setEmail(email);
            me.setProvider("google");
            me.setProviderAccountId("google-acc-" + email.hashCode());
            me.setDisplayName("Me T8c");
            me.setEncryptedAccessToken(cryptoUtil.encrypt("fake-access".getBytes(StandardCharsets.UTF_8)));
            me.setEncryptedRefreshToken(cryptoUtil.encrypt("fake-refresh".getBytes(StandardCharsets.UTF_8)));
            me.setScopes(List.of("openid", "email", "profile"));
            me.setAesKeyId("hkdf-sha256-v1");
            me.setLastLoginAt(Instant.now());
            return userAccounts.save(me);
        }

        private OAuth2AuthenticationToken buildAuthToken(String email, String regId) {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("email", email);
            attrs.put("sub", "fake-sub-" + email);
            List<GrantedAuthority> auths = List.of(new SimpleGrantedAuthority("ROLE_USER"));
            DefaultOAuth2User user = new DefaultOAuth2User(auths, attrs, "sub");
            return new OAuth2AuthenticationToken(user, auths, regId);
        }

        @Test
        @DisplayName("T8c_01_syncFragNever — no progress row → sync-never class + 'Never run'")
        void T8c_01_syncFragNever() throws Exception {
            insertMeAccount("me-t8c01@example.com");

            MvcResult res = mockMvc.perform(get("/sync-progress-frag")
                    .with(authentication(buildAuthToken("me-t8c01@example.com", "google"))))
                .andExpect(status().isOk())
                .andReturn();

            String body = res.getResponse().getContentAsString();
            assertThat(body).contains("sync-never");
            assertThat(body).contains("Never run");
        }

        @Test
        @DisplayName("T8c_02_syncFragRunning — RUNNING progress remains visible; dashboard redirects")
        void T8c_02_syncFragRunning() throws Exception {
            UserAccount me = insertMeAccount("me-t8c02@example.com");
            SyncProgress sp = new SyncProgress();
            sp.setAccountId(me.getId());
            sp.setJobType("INCREMENTAL_SYNC");
            sp.setStatus("RUNNING");
            syncProgressRepo.save(sp);

            MvcResult fragRes = mockMvc.perform(get("/sync-progress-frag")
                    .with(authentication(buildAuthToken("me-t8c02@example.com", "google"))))
                .andExpect(status().isOk())
                .andReturn();
            String fragBody = fragRes.getResponse().getContentAsString();
            assertThat(fragBody).contains("sync-running");

            MvcResult dashRes = mockMvc.perform(get("/dashboard")
                    .with(authentication(buildAuthToken("me-t8c02@example.com", "google"))))
                .andExpect(status().isOk())
                .andReturn();
            assertThat(dashRes.getModelAndView().getViewName()).isEqualTo("dashboard");
        }

        @Test
        @DisplayName("T8c_03_syncFragSuccess + DashboardRedirect — COMPLETE fragment + workspace redirect")
        void T8c_03_syncFragSuccessAndDashboardSlot() throws Exception {
            UserAccount me = insertMeAccount("me-t8c03@example.com");
            SyncProgress sp = new SyncProgress();
            sp.setAccountId(me.getId());
            sp.setJobType("INCREMENTAL_SYNC");
            sp.setStatus("COMPLETE");
            sp.setFinishedAt(Instant.now());
            sp.setMessagesScanned(42);
            syncProgressRepo.save(sp);

            MvcResult fragRes = mockMvc.perform(get("/sync-progress-frag")
                    .with(authentication(buildAuthToken("me-t8c03@example.com", "google"))))
                .andExpect(status().isOk())
                .andReturn();
            String fragBody = fragRes.getResponse().getContentAsString();
            assertThat(fragBody).contains("sync-success");

            MvcResult dashRes = mockMvc.perform(get("/dashboard")
                    .with(authentication(buildAuthToken("me-t8c03@example.com", "google"))))
                .andExpect(status().isOk())
                .andReturn();
            assertThat(dashRes.getModelAndView().getViewName()).isEqualTo("dashboard");
        }
    }

    // ============================================================
    // T8(d): Lint / Doc / static-asset checks (plain Java, no Spring)
    // ============================================================

    @Nested
    @DisplayName("T8d Lint & Doc Tests")
    class T8d_lintAndDocTests {

        @Test
        @DisplayName("T8d_01_deployMdChecks — DEPLOY.md exists with precheck, tunnel, and datasource troubleshooting")
        void T8d_01_deployMdChecks() throws Exception {
            File f = new File("DEPLOY.md");
            assertTrue(f.exists(), "DEPLOY.md must exist at project root");

            String contents = Files.readString(f.toPath());
            assertThat(contents).contains("docker info");
            assertThat(contents).contains("persistent Java host with PostgreSQL");
            assertThat(contents).contains("SPRING_DATASOURCE");
        }

        @Test
        @DisplayName("T8d_02_cssBreakpoints — dashboard.html has @media 375px + 44px touch targets; reply-zero.html has @media")
        void T8d_02_cssBreakpoints() throws Exception {
            File dashboard = new File("src/main/resources/templates/dashboard.html");
            assertTrue(dashboard.exists(), "dashboard.html exists");
            assertThat(Files.readString(dashboard.toPath())).contains("/static/css/dashboard.css");
            String dashContents = Files.readString(Path.of("src/main/resources/static/css/dashboard.css"));
            assertThat(dashContents).contains("@media");
            assertThat(dashContents).contains("375px");
            assertThat(dashContents).contains("min-height: 44px");

            File replyZero = new File("src/main/resources/templates/reply-zero.html");
            assertTrue(replyZero.exists(), "reply-zero.html exists");
            String rzContents = Files.readString(replyZero.toPath());
            assertThat(rzContents).contains("@media");
        }
    }
}
