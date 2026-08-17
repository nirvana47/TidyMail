package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tidymail.dto.ActionScopePreview;
import com.tidymail.dto.ActionScopeRiskSummary;
import com.tidymail.dto.ActionScopeTargetSummary;
import com.tidymail.entity.ActionScope;
import com.tidymail.entity.MessageMetadata;
import com.tidymail.entity.UserAccount;
import com.tidymail.entity.UserAction;
import com.tidymail.mail.BulkActionRequest;
import com.tidymail.mail.BulkActionResult;
import com.tidymail.mail.MailProvider;
import com.tidymail.repository.ActionScopeRepository;
import com.tidymail.repository.MessageMetadataRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.repository.UserActionRepository;
import com.tidymail.service.ActionScopeHasher;
import com.tidymail.service.ActionScopePersistenceService;
import com.tidymail.service.ActionScopeService;
import com.tidymail.service.MessageMetadataQueryService;
import com.tidymail.service.UndoService;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

class Phase9ActionScopeTest {

    private static final ActionScopeTargetSummary TARGET =
        new ActionScopeTargetSummary("news@example.com", "news@example.com", "example.com", 1);
    private static final ActionScopeRiskSummary RISK =
        new ActionScopeRiskSummary(1, 0, List.of("PROTECTED_MESSAGES"));

    @Nested
    @DisplayName("Canonical hash and typed summary contracts")
    class PureContracts {

        private final ActionScopeHasher hasher = new ActionScopeHasher();

        @Test
        void knownHashIsVersionedDeterministicAndDeduplicated() {
            ActionScopeHasher.CanonicalScope canonical = hasher.canonicalize(
                7L, "archive", "sender_address", List.of("m-2", "m-1", "m-2"));

            assertThat(canonical.messageIds()).containsExactly("m-1", "m-2");
            assertThat(canonical.scopeHash())
                .isEqualTo("555241e523db980dd4a305a18cee10494f54e7735770e4ab3d3eeac1ca47ab45")
                .matches("[0-9a-f]{64}");
            assertThat(hasher.canonicalize(
                7L, "ARCHIVE", "SENDER_ADDRESS", List.of("m-1", "m-2")).scopeHash())
                .isEqualTo(canonical.scopeHash());
        }

        @Test
        void everyIdentityFieldChangesHashAndLengthPrefixesPreventAmbiguity() {
            String baseline = hash(7L, "ARCHIVE", "SENDER_ADDRESS", List.of("a", "bc"));
            assertThat(hash(8L, "ARCHIVE", "SENDER_ADDRESS", List.of("a", "bc")))
                .isNotEqualTo(baseline);
            assertThat(hash(7L, "TRASH", "SENDER_ADDRESS", List.of("a", "bc")))
                .isNotEqualTo(baseline);
            assertThat(hash(7L, "ARCHIVE", "DOMAIN", List.of("a", "bc")))
                .isNotEqualTo(baseline);
            assertThat(hash(7L, "ARCHIVE", "SENDER_ADDRESS", List.of("ab", "c")))
                .isNotEqualTo(baseline);
            assertThat(hash(7L, "ARCHIVE", "SENDER_ADDRESS", List.of("a|b", "雪")))
                .isNotEqualTo(hash(7L, "ARCHIVE", "SENDER_ADDRESS", List.of("a", "b|雪")));
            assertThatThrownBy(() -> hash(
                7L, "ARCHIVE", "SENDER_ADDRESS", List.of("\uD800")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid Unicode");
        }

        @Test
        void rejectsInvalidIdentityAndBoundsTypedSummaries() {
            assertThatThrownBy(() -> hasher.canonicalize(0L, "ARCHIVE", "SENDER_ADDRESS", List.of("m")))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> hasher.canonicalize(1L, "ARCHIVE", "SENDER_ADDRESS", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> hasher.canonicalize(1L, "ARCHIVE", "SENDER_ADDRESS", List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ActionScopeTargetSummary(
                "x".repeat(513), null, null, 1))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ActionScopeRiskSummary(-1, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ActionScopeRiskSummary(0, 0, List.of("nested.value")))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void summaryJsonRejectsUnknownAndNestedCallerValues() {
            ObjectMapper strict = new ObjectMapper();
            assertThatThrownBy(() -> strict.readValue(
                """
                {"displayLabel":"ok","senderAddress":"a@b.com","senderDomain":"b.com",
                 "selectedTargetCount":1,"subject":"forbidden"}
                """, ActionScopeTargetSummary.class))
                .isInstanceOf(Exception.class);
            assertThatThrownBy(() -> strict.readValue(
                """
                {"protectedCount":0,"highRiskCount":0,"warningCodes":[{"code":"NESTED"}]}
                """, ActionScopeRiskSummary.class))
                .isInstanceOf(Exception.class);
            ArrayList<String> source = new ArrayList<>(List.of("WARNING"));
            ActionScopeRiskSummary summary = new ActionScopeRiskSummary(0, 0, source);
            source.add("CHANGED");
            assertThat(summary.warningCodes()).containsExactly("WARNING");
            assertThatThrownBy(() -> summary.warningCodes().add("CHANGED"))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        private String hash(Long account, String action, String target, List<String> ids) {
            return hasher.canonicalize(account, action, target, ids).scopeHash();
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
    @AutoConfigureMockMvc
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
    @DisplayName("Persistence and exact-scope workflow")
    class Integration {

        @Autowired MockMvc mockMvc;
        @Autowired ActionScopeService scopeService;
        @Autowired ActionScopeRepository scopes;
        @Autowired UserActionRepository actions;
        @Autowired UserAccountRepository accounts;
        @Autowired MessageMetadataRepository metadata;
        @Autowired JdbcTemplate jdbc;

        @SpyBean
        MessageMetadataQueryService metadataQueryService;

        @MockBean(name = "gmailMailService")
        MailProvider gmailProvider;

        @MockBean(name = "outlookMailService")
        MailProvider outlookProvider;

        @BeforeEach
        void clean() {
            actions.deleteAll();
            scopes.deleteAll();
            metadata.deleteAll();
            accounts.deleteAll();
            reset(gmailProvider, outlookProvider);
        }

        @Test
        void persistsImmutableDefensiveScopeAndLoadsLegacyAction() {
            UserAccount account = account("persist@example.com");
            ArrayList<String> source = new ArrayList<>(List.of("z", "a", "z"));
            ActionScopePreview preview = scopeService.createOrGet(
                account.getId(), "archive", "sender_address", source, TARGET, RISK);
            source.add("later");

            ActionScope loaded = scopes.findByIdAndAccountId(
                preview.scopeId(), account.getId()).orElseThrow();
            assertThat(loaded.getMessageIds()).containsExactly("a", "z");
            assertThat(loaded.getMessageCount()).isEqualTo(2);
            assertThat(loaded.getTargetSummary()).isEqualTo(TARGET);
            assertThat(loaded.getRiskSummary()).isEqualTo(RISK);
            assertThatThrownBy(() -> loaded.getMessageIds().add("mutate"))
                .isInstanceOf(UnsupportedOperationException.class);
            assertThat(List.of(ActionScope.class.getMethods()))
                .noneMatch(method -> method.getName().startsWith("set"));

            UserAction legacy = action(account, null, "ARCHIVE", List.of("legacy"), 1);
            assertThat(actions.findById(actions.save(legacy).getId()).orElseThrow().getActionScopeId())
                .isNull();
        }

        @Test
        void equivalentCreationIsIdempotentAndForeignCompositeLinkIsRejected() {
            UserAccount first = account("first@example.com");
            UserAccount second = account("second@example.com");
            ActionScopePreview one = scopeService.createOrGet(
                first.getId(), "ARCHIVE", "SENDER_ADDRESS", List.of("b", "a"), TARGET, RISK);
            ActionScopePreview two = scopeService.createOrGet(
                first.getId(), "archive", "sender_address", List.of("a", "b", "a"), TARGET, RISK);
            assertThat(two.scopeId()).isEqualTo(one.scopeId());
            assertThat(scopes.count()).isEqualTo(1);

            assertThatThrownBy(() -> jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                    "insert into user_actions(account_id,action_scope_id,action_type,message_ids,"
                        + "inverse_action_data,target_id_json,affected_count,status) "
                        + "values (?,?,?,ARRAY['a','b'],JSON '{}',JSON '{}',2,'QUEUED')");
                statement.setLong(1, second.getId());
                statement.setLong(2, one.scopeId());
                statement.setString(3, "ARCHIVE");
                return statement;
            })).isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void tamperedPersistedScopeCannotBeQueued() throws Exception {
            UserAccount owner = account("scope-tamper@example.com");
            ActionScopePreview preview = scopeService.createOrGet(
                owner.getId(), "ARCHIVE", "SENDER_ADDRESS", List.of("one"), TARGET, RISK);
            jdbc.update(
                "update action_scopes set scope_hash = ? where id = ?",
                "0".repeat(64), preview.scopeId());

            mockMvc.perform(post("/api/actions/scopes/" + preview.scopeId() + "/queue")
                    .with(csrf())
                    .with(authentication(auth("scope-tamper@example.com"))))
                    .andExpect(status().isGone());
            assertThat(actions.count()).isZero();
        }

        @Test
        void previewQueueApplyAndUndoUseOnePersistedExactScope() throws Exception {
            UserAccount owner = account("owner@example.com");
            UserAccount foreign = account("foreign@example.com");
            seed(owner, "news@example.com", List.of("m-3", "m-1", "m-2"));
            when(gmailProvider.batchModify(any(), any())).thenAnswer(invocation -> {
                BulkActionRequest request = invocation.getArgument(1);
                return BulkActionResult.ok(request.messageIds().size(), request.messageIds());
            });
            when(gmailProvider.applyLabel(any(), any(), any(), any(Boolean.class)))
                .thenAnswer(invocation -> {
                    List<String> ids = invocation.getArgument(1);
                    return BulkActionResult.ok(ids.size(), ids);
                });

            String previewHtml = mockMvc.perform(post(
                        "/api/actions/bulk-by-sender/news@example.com")
                    .param("action", "ARCHIVE")
                    .with(csrf())
                    .with(authentication(auth("owner@example.com"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
            assertThat(actions.count()).isZero();
            ActionScope scope = scopes.findAll().get(0);
            assertThat(previewHtml)
                .startsWith("<div")
                .doesNotContain("<!DOCTYPE", "<html", "<body")
                .contains(scope.getScopeHash().substring(0, 12) + "...")
                .contains(">3<")
                .contains("news@example.com")
                .doesNotContain(scope.getScopeHash(), "m-1", "m-2", "m-3");
            verify(gmailProvider, never()).listMessages(any(), any(), anyInt());
            verify(metadataQueryService, times(1))
                .bySender(List.of(owner.getId()), "news@example.com", 0, 100);

            mockMvc.perform(post("/api/actions/scopes/" + scope.getId() + "/queue")
                    .with(csrf())
                    .with(authentication(auth("foreign@example.com"))))
                  .andExpect(status().isGone());
            mockMvc.perform(post("/api/actions/scopes/" + scope.getId() + "/queue")
                    .with(csrf())
                    .with(authentication(auth("owner@example.com"))))
                .andExpect(status().isGone());
            assertThat(actions.count()).isZero();

            mockMvc.perform(post("/api/actions/review/apply")
                    .with(csrf())
                    .with(authentication(auth("owner@example.com"))))
                .andExpect(status().isGone());
            mockMvc.perform(post("/api/actions/1/undo")
                    .with(csrf())
                    .with(authentication(auth("owner@example.com"))))
                  .andExpect(status().isBadRequest());
            verify(gmailProvider, never()).batchModify(any(), any());
            verify(gmailProvider, never()).applyLabel(any(), any(), any(), any(Boolean.class));
            verify(gmailProvider, never()).listMessages(any(), any(), anyInt());
            verify(metadataQueryService, times(1))
                .bySender(List.of(owner.getId()), "news@example.com", 0, 100);
        }

        @Test
        void tamperingFailsBeforeProviderMutation() throws Exception {
            UserAccount owner = account("tamper@example.com");
            ActionScopePreview preview = scopeService.createOrGet(
                owner.getId(), "TRASH", "SENDER_ADDRESS", List.of("one", "two"), TARGET, RISK);
            UserAction row = action(
                owner, preview.scopeId(), "TRASH", preview.messageIds(), preview.messageCount());
            row.setStatus("QUEUED");
            row = actions.save(row);

            row.setActionType("ARCHIVE");
            actions.save(row);
            mockMvc.perform(post("/api/actions/review/apply")
                    .with(csrf())
                    .with(authentication(auth("tamper@example.com"))))
                .andExpect(status().isGone());
            verify(gmailProvider, never()).batchModify(any(), any());

            row.setActionType("TRASH");
            row.setAffectedCount(99);
            actions.save(row);
            mockMvc.perform(post("/api/actions/review/apply")
                    .with(csrf())
                    .with(authentication(auth("tamper@example.com"))))
                .andExpect(status().isGone());
            verify(gmailProvider, never()).batchModify(any(), any());

            row.setAffectedCount(2);
            row.setMessageIds(List.of("wrong"));
            row.setStatus("APPLIED");
            row.setExpiresAt(Instant.now().plusSeconds(30));
            actions.save(row);
            mockMvc.perform(post("/api/actions/" + row.getId() + "/undo")
                    .with(csrf())
                    .with(authentication(auth("tamper@example.com"))))
                  .andExpect(status().isBadRequest());
            verify(gmailProvider, never()).applyLabel(any(), any(), any(), any(Boolean.class));

            row.setMessageIds(preview.messageIds());
            row.setInverseActionData(Map.of(
                "inverseType", "INBOX_ADD",
                "messageIds", preview.messageIds(),
                "forwardType", "TRASH",
                "formatVersion", 1));
            actions.save(row);
            mockMvc.perform(post("/api/actions/" + row.getId() + "/undo")
                    .with(csrf())
                    .with(authentication(auth("tamper@example.com"))))
                  .andExpect(status().isBadRequest());
            verify(gmailProvider, never()).applyLabel(any(), any(), any(), any(Boolean.class));
        }

        private UserAccount account(String email) {
            UserAccount account = new UserAccount();
            account.setEmail(email);
            account.setProvider("google");
            account.setProviderAccountId("provider-" + email);
            account.setEncryptedAccessToken(new byte[] {1});
            account.setEncryptedRefreshToken(new byte[] {2});
            account.setScopes(List.of("email"));
            account.setAesKeyId("hkdf-sha256-v1");
            return accounts.save(account);
        }

        private void seed(UserAccount account, String sender, List<String> ids) {
            List<MessageMetadata> rows = new ArrayList<>();
            for (int index = 0; index < ids.size(); index++) {
                MessageMetadata row = new MessageMetadata();
                row.setAccountId(account.getId());
                row.setProvider("google");
                row.setProviderMessageId(ids.get(index));
                row.setFromAddress(sender);
                row.setFromDomain("example.com");
                row.setReceivedAt(Instant.now().minusSeconds(index));
                row.setLabels(index == 0 ? List.of("STARRED") : List.of());
                rows.add(row);
            }
            metadata.saveAll(rows);
        }

        private OAuth2AuthenticationToken auth(String email) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("email", email);
            attributes.put("sub", "sub-" + email);
            List<GrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority("ROLE_USER"));
            DefaultOAuth2User user = new DefaultOAuth2User(authorities, attributes, "sub");
            return new OAuth2AuthenticationToken(user, authorities, "google");
        }

        private UserAction action(
                UserAccount account,
                Long scopeId,
                String actionType,
                List<String> ids,
                int count) {
            UserAction row = new UserAction();
            row.setAccountId(account.getId());
            row.setActionScopeId(scopeId);
            row.setActionType(actionType);
            row.setTargetKind("SENDER_ADDRESS");
            row.setTargetIdJson(Map.of("fromAddress", "news@example.com"));
            row.setMessageIds(new ArrayList<>(ids));
            row.setAffectedCount(count);
            row.setInverseActionData(
                UndoService.buildInverseActionData(UndoService.BulkAction.valueOf(actionType), ids));
            row.setStatus("APPLIED");
            row.setExpiresAt(Instant.now().plusSeconds(30));
            return row;
        }
    }

    @Test
    void uniqueRaceReloadsOnlyAfterRequiresNewInsertReturnsAndMismatchFailsClosed() {
        ActionScopeHasher hasher = new ActionScopeHasher();
        ActionScopeRepository repository = org.mockito.Mockito.mock(ActionScopeRepository.class);
        ActionScopePersistenceService persistence =
            org.mockito.Mockito.mock(ActionScopePersistenceService.class);
        ActionScopeService service = new ActionScopeService(hasher, repository, persistence);
        ActionScope winner = ActionScope.create(
            1L, "ARCHIVE", "SENDER_ADDRESS", List.of("a"), hash(hasher),
            TARGET, RISK);
        when(repository.findByAccountIdAndScopeHash(1L, hash(hasher)))
            .thenReturn(java.util.Optional.empty())
            .thenReturn(java.util.Optional.of(winner));
        when(persistence.insert(any())).thenThrow(uniqueRace());

        ActionScopePreview recovered = service.createOrGet(
            1L, "ARCHIVE", "SENDER_ADDRESS", List.of("a"), TARGET, RISK);
        assertThat(recovered.scopeHash()).isEqualTo(hash(hasher));
        InOrder order = inOrder(repository, persistence);
        order.verify(repository).findByAccountIdAndScopeHash(1L, hash(hasher));
        order.verify(persistence).insert(any());
        order.verify(repository).findByAccountIdAndScopeHash(1L, hash(hasher));

        ActionScope mismatched = ActionScope.create(
            1L, "TRASH", "SENDER_ADDRESS", List.of("a"), hash(hasher), TARGET, RISK);
        reset(repository, persistence);
        when(repository.findByAccountIdAndScopeHash(1L, hash(hasher)))
            .thenReturn(java.util.Optional.empty())
            .thenReturn(java.util.Optional.of(mismatched));
        when(persistence.insert(any())).thenThrow(uniqueRace());
        assertThatThrownBy(() -> service.createOrGet(
            1L, "ARCHIVE", "SENDER_ADDRESS", List.of("a"), TARGET, RISK))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("collision");
    }

    private static String hash(ActionScopeHasher hasher) {
        return hasher.canonicalize(
            1L, "ARCHIVE", "SENDER_ADDRESS", List.of("a")).scopeHash();
    }

    private static DataIntegrityViolationException uniqueRace() {
        return new DataIntegrityViolationException(
            "uq_action_scopes_account_hash", new SQLException("duplicate", "23505"));
    }
}
