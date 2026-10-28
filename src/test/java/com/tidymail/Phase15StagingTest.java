package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.dto.SelectionRequest.PreviewAction;
import com.tidymail.dto.SenderQuery;
import com.tidymail.entity.MessageMetadata;
import com.tidymail.entity.UserAccount;
import com.tidymail.entity.UserAction;
import com.tidymail.mail.AttachmentState;
import com.tidymail.mail.MailProvider;
import com.tidymail.repository.ActionConflictRepository;
import com.tidymail.repository.ActionScopeRepository;
import com.tidymail.repository.DecisionSurfaceQueryRepository;
import com.tidymail.repository.MessageMetadataRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.repository.UserActionRepository;
import com.tidymail.service.CleanupActionService;
import com.tidymail.service.CleanupActionService.StageRequest;
import com.tidymail.service.DecisionRowKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.task.scheduling.enabled=false",
    "TOKEN_ENCRYPT_SECRET=0000000000000000000000000000000000000000000000000000000000000000",
    "TOKEN_ENCRYPT_SALT=00000000000000000000000000000000",
    "GOOGLE_CLIENT_ID=",
    "GOOGLE_CLIENT_SECRET=",
    "MICROSOFT_CLIENT_ID=",
    "MICROSOFT_CLIENT_SECRET="
})
class Phase15StagingTest {
    @Autowired MockMvc mockMvc;
    @Autowired CleanupActionService cleanupActions;
    @Autowired UserAccountRepository accounts;
    @Autowired MessageMetadataRepository metadata;
    @Autowired ActionScopeRepository scopes;
    @Autowired UserActionRepository actions;
    @Autowired ActionConflictRepository conflicts;
    @Autowired DecisionSurfaceQueryRepository decisionQueries;
    @Autowired JdbcTemplate jdbc;

    @MockBean(name = "gmailMailService")
    MailProvider gmail;

    @MockBean(name = "outlookMailService")
    MailProvider outlook;

    @BeforeEach
    void clean() {
        actions.deleteAll();
        scopes.deleteAll();
        metadata.deleteAll();
        accounts.deleteAll();
        reset(gmail, outlook);
    }

    @Test
    void defaultSenderFormStagesThreeDeletesWithAnEmptySearchWithoutChangingGmail()
            throws Exception {
        UserAccount account = account("default-form@example.com");
        String[] senders = {"one@example.com", "two@example.com", "three@example.com"};
        for (int index = 0; index < senders.length; index++) {
            seed(account, "default-message-" + index, senders[index]);
        }
        var post = post("/api/cleanup-actions")
            .with(csrf())
            .with(authentication(auth(account.getEmail())))
            .header("HX-Request", "true")
            .param("surface", "SENDERS")
            .param("action", "TRASH")
            .param("deleteAcknowledged", "true")
            .param("requestToken", UUID.randomUUID().toString())
            .param("rowKey", java.util.Arrays.stream(senders)
                .map(sender -> DecisionRowKey.sender(account.getId(), sender))
                .toArray(String[]::new));
        // Use every canonical field rendered by the default sender form, including search="".
        SenderQuery.parse(Map.of("account", List.of(account.getId().toString())))
            .canonicalParameters().forEach((name, value) -> post.param(name, value));

        mockMvc.perform(post).andExpect(status().isOk());

        assertThat(actions.findAll()).hasSize(3).allSatisfy(action -> {
            assertThat(action.getActionType()).isEqualTo("TRASH");
            assertThat(action.getStatus()).isEqualTo("QUEUED");
            assertThat(action.getExecutionMode()).isEqualTo("STAGED");
            assertThat(action.getMessageIds()).hasSize(1);
        });
        verifyNoInteractions(gmail, outlook);
    }

    @Test
    void directPostsPersistExactPerSenderScopesReuseActiveActionsAndReturnCanonicalRoute()
            throws Exception {
        UserAccount account = account("stage@example.com");
        seed(account, "a-2", "a@example.com");
        seed(account, "a-1", "a@example.com");
        seed(account, "b-1", "b@example.com");
        String a = DecisionRowKey.sender(account.getId(), "a@example.com");
        String b = DecisionRowKey.sender(account.getId(), "b@example.com");
        SenderQuery exactQuery = query(String.valueOf(account.getId()));
        assertThat(decisionQueries.materializeSenders(
            List.of(account.getId()),
            exactQuery,
            com.tidymail.dto.SelectionMode.INDIVIDUAL,
            List.of(
                new DecisionSurfaceQueryRepository.SelectionLocator(
                    account.getId(), "a@example.com"),
                new DecisionSurfaceQueryRepository.SelectionLocator(
                    account.getId(), "b@example.com")),
            false,
            Instant.now()).accounts().getFirst().messageIds())
            .containsExactly("a-1", "a-2", "b-1");

        mockMvc.perform(post("/api/cleanup-actions")
                .with(csrf())
                .with(authentication(auth(account.getEmail())))
                .header("HX-Request", "true")
                .param("surface", "SENDERS")
                .param("action", "ARCHIVE")
                .param("rowKey", b, a, a)
                .param("requestToken", UUID.randomUUID().toString())
                .param("account", "current")
                .param("window", "all")
                .param("queue", "all")
                .param("protected", "include"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "\"distinctMessageCount\":3")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "\"exactMessageCount\":2")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "\"reviewCount\":2")));

        assertThat(actions.findAll()).hasSize(2);
        assertThat(scopes.findAll()).hasSize(2);
        assertThat(scopes.findAll()).anySatisfy(scope -> {
            assertThat(scope.getTargetSummary().senderAddress()).isEqualTo("a@example.com");
            assertThat(scope.getMessageIds()).containsExactly("a-1", "a-2");
            assertThat(scope.getMessageCount()).isEqualTo(2);
        });
        assertThat(actions.findAll())
            .allSatisfy(action -> {
                assertThat(action.getExecutionMode()).isEqualTo("STAGED");
                assertThat(action.getStatus()).isEqualTo("QUEUED");
                assertThat(action.getAffectedCount()).isEqualTo(action.getMessageIds().size());
            });

        var firstHashes = scopes.findAll().stream()
            .collect(java.util.stream.Collectors.toMap(
                scope -> scope.getTargetSummary().senderAddress(),
                com.tidymail.entity.ActionScope::getScopeHash));
        mockMvc.perform(post("/api/cleanup-actions")
                .with(csrf())
                .with(authentication(auth(account.getEmail())))
                .header("HX-Request", "true")
                .param("surface", "SENDERS")
                .param("action", "ARCHIVE")
                .param("rowKey", a, b)
                .param("requestToken", UUID.randomUUID().toString())
                .param("window", "all")
                .param("queue", "all")
                .param("protected", "include"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "\"reused\":true")));
        assertThat(actions.findAll()).hasSize(2);
        assertThat(scopes.findAll()).hasSize(2);
        assertThat(scopes.findAll()).allSatisfy(scope ->
            assertThat(scope.getScopeHash()).isEqualTo(
                firstHashes.get(scope.getTargetSummary().senderAddress())));

        mockMvc.perform(post("/api/cleanup-actions")
                .with(csrf())
                .with(authentication(auth(account.getEmail())))
                .param("surface", "SENDERS")
                .param("action", "ARCHIVE")
                .param("rowKey", a)
                .param("requestToken", UUID.randomUUID().toString())
                .param("window", "all")
                .param("queue", "all")
                .param("protected", "include"))
            .andExpect(status().isSeeOther())
            .andExpect(header().string(
                "Location", org.hamcrest.Matchers.startsWith("/senders?")))
            .andExpect(header().string(
                "Location", org.hamcrest.Matchers.containsString(
                    "cleanupAction=archive")));
        verifyNoInteractions(gmail, outlook);
    }

    @Test
    void strictBoundaryRejectsCsrfSmugglingDuplicatesAndDeleteWithoutAcknowledgement()
            throws Exception {
        UserAccount account = account("strict@example.com");
        seed(account, "strict-1", "strict-sender@example.com");
        String row = DecisionRowKey.sender(account.getId(), "strict-sender@example.com");

        mockMvc.perform(basePost(account, row, "ARCHIVE"))
            .andExpect(status().isForbidden());
        mockMvc.perform(basePost(account, row, "ARCHIVE")
                .with(csrf())
                .param("owner", "foreign"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/cleanup-actions?account=current")
                .with(csrf())
                .with(authentication(auth(account.getEmail())))
                .param("surface", "SENDERS")
                .param("action", "ARCHIVE")
                .param("rowKey", row)
                .param("requestToken", UUID.randomUUID().toString()))
            .andExpect(status().isBadRequest());
        mockMvc.perform(basePost(account, row, "ARCHIVE")
                .with(csrf())
                .param("requestToken", UUID.randomUUID().toString()))
            .andExpect(status().isBadRequest());
        mockMvc.perform(basePost(account, row, "TRASH").with(csrf()))
            .andExpect(status().isBadRequest());
        mockMvc.perform(basePost(account, row, "ARCHIVE")
                .with(csrf())
                .param("deleteAcknowledged", "true"))
            .andExpect(status().isBadRequest());

        mockMvc.perform(basePost(account, row, "TRASH")
                .with(csrf())
                .header("HX-Request", "true")
                .param("deleteAcknowledged", "true"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "Gmail Trash can typically be restored for up to 30 days")));
        assertThat(actions.findAll()).singleElement()
            .extracting(UserAction::getActionType).isEqualTo("TRASH");
        verifyNoInteractions(gmail, outlook);
    }

    @Test
    void unreadPathStagesOnlyOwnedInboxUnreadIdsForSenderCategoryAndFilters()
            throws Exception {
        UserAccount account = account("unread-stage@example.com");
        UserAccount foreign = account("unread-foreign@example.com");
        MessageMetadata included =
            row(account, "target-attachment", "same@example.com");
        included.setLabels(List.of("INBOX"));
        confirmAttachment(included);
        metadata.saveAndFlush(included);
        MessageMetadata wrongCategory =
            row(account, "target-no-attachment", "same@example.com");
        wrongCategory.setLabels(List.of("INBOX"));
        metadata.saveAndFlush(wrongCategory);
        MessageMetadata archived =
            row(account, "target-archived", "same@example.com");
        confirmAttachment(archived);
        metadata.saveAndFlush(archived);
        MessageMetadata read =
            row(account, "target-read", "same@example.com");
        read.setLabels(List.of("INBOX"));
        read.setUnread(false);
        confirmAttachment(read);
        metadata.saveAndFlush(read);
        MessageMetadata foreignRow =
            row(foreign, "target-foreign", "same@example.com");
        foreignRow.setLabels(List.of("INBOX"));
        confirmAttachment(foreignRow);
        metadata.saveAndFlush(foreignRow);

        String selection = DecisionRowKey.unreadSender(
            account.getId(), "attachments", "same@example.com");
        mockMvc.perform(unreadPost(account, selection, "ARCHIVE")
                .with(csrf())
                .header("HX-Request", "true")
                .param("search", "target"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "\"distinctMessageCount\":1")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "\"senderAddress\":\"same@example.com\"")));

        assertThat(actions.findAll()).singleElement().satisfies(action -> {
            assertThat(action.getDecisionSource()).isEqualTo("UNREAD");
            assertThat(action.getMessageIds()).containsExactly("target-attachment");
        });
        assertThat(scopes.findAll()).singleElement().satisfies(scope -> {
            assertThat(scope.getTargetKind()).isEqualTo("UNREAD_SELECTION");
            assertThat(scope.getMessageIds()).containsExactly("target-attachment");
            assertThat(scope.getTargetSummary().senderAddress())
                .isEqualTo("same@example.com");
        });

        mockMvc.perform(unreadPost(account, selection, "ARCHIVE")
                .with(csrf())
                .param("search", "target"))
            .andExpect(status().isSeeOther())
            .andExpect(header().string(
                "Location", org.hamcrest.Matchers.startsWith("/unread?")))
            .andExpect(header().string(
                "Location", org.hamcrest.Matchers.containsString(
                    "cleanupAction=archive")));
        assertThat(actions.count()).isEqualTo(1);
        assertThat(scopes.count()).isEqualTo(1);

        String foreignSelection = DecisionRowKey.unreadSender(
            foreign.getId(), "attachments", "same@example.com");
        mockMvc.perform(unreadPost(account, foreignSelection, "ARCHIVE").with(csrf()))
            .andExpect(status().isBadRequest());
        mockMvc.perform(unreadPost(account, selection, "ARCHIVE")
                .with(csrf())
                .param("window", "all"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(unreadPost(account, selection, "ARCHIVE"))
            .andExpect(status().isForbidden());
        assertThat(actions.count()).isEqualTo(1);
        verifyNoInteractions(gmail, outlook);
    }

    @Test
    void unreadOverlappingCategorySelectionsUnionIdsIntoOneSenderAction()
            throws Exception {
        UserAccount account = account("unread-overlap@example.com");
        MessageMetadata attachment =
            row(account, "overlap-attachment", "overlap@example.com");
        attachment.setLabels(List.of("INBOX"));
        confirmAttachment(attachment);
        metadata.saveAndFlush(attachment);
        MessageMetadata direct =
            row(account, "overlap-direct", "overlap@example.com");
        direct.setLabels(List.of("INBOX"));
        metadata.saveAndFlush(direct);

        String attachmentSelection = DecisionRowKey.unreadSender(
            account.getId(), "attachments", "overlap@example.com");
        String directSelection = DecisionRowKey.unreadSender(
            account.getId(), "direct-mail", "overlap@example.com");
        mockMvc.perform(post("/api/cleanup-actions")
                .with(authentication(auth(account.getEmail())))
                .with(csrf())
                .header("HX-Request", "true")
                .param("surface", "UNREAD")
                .param("action", "MARK_READ")
                .param("rowKey",
                    attachmentSelection, directSelection, attachmentSelection)
                .param("requestToken", UUID.randomUUID().toString())
                .param("account", account.getId().toString()))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "\"distinctMessageCount\":2")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "\"exactMessageCount\":2")));

        assertThat(actions.findAll()).singleElement().satisfies(action ->
            assertThat(action.getMessageIds())
                .containsExactly("overlap-attachment", "overlap-direct"));
        assertThat(scopes.findAll()).singleElement().satisfies(scope ->
            assertThat(scope.getMessageIds())
                .containsExactly("overlap-attachment", "overlap-direct"));
        verifyNoInteractions(gmail, outlook);
    }

    @Test
    void unionCapAccepts1000AndRejects1001BeforeAnyScopeOrActionWrite() {
        UserAccount accepted = account("cap1000@example.com");
        seedMany(accepted, 500, "a@example.com", "a-");
        seedMany(accepted, 500, "b@example.com", "b-");
        var acceptedResult = cleanupActions.stage(
            owner(accepted),
            List.of(accepted.getId()),
            request(accepted, PreviewAction.ARCHIVE, false,
                "a@example.com", "b@example.com"));
        assertThat(acceptedResult.distinctMessageCount()).isEqualTo(1000);
        assertThat(acceptedResult.senders()).hasSize(2);
        assertThat(actions.findAll()).hasSize(2);
        assertThat(scopes.findAll()).hasSize(2);

        clean();
        UserAccount rejected = account("cap1001@example.com");
        seedMany(rejected, 500, "a@example.com", "a-");
        seedMany(rejected, 501, "b@example.com", "b-");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            cleanupActions.stage(
                owner(rejected),
                List.of(rejected.getId()),
                request(rejected, PreviewAction.ARCHIVE, false,
                    "a@example.com", "b@example.com")))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("1,000");
        assertThat(actions.count()).isZero();
        assertThat(scopes.count()).isZero();
        verifyNoInteractions(gmail, outlook);
    }

    @Test
    void protectedStaleAndForeignSelectionsRollBackWithoutProviderInteraction()
            throws Exception {
        UserAccount account = account("guard@example.com");
        UserAccount foreign = account("foreign@example.com");
        seed(account, "protected-1", "protected@example.com");
        seed(account, "ordinary-1", "ordinary@example.com");
        seed(foreign, "foreign-1", "foreign-sender@example.com");
        jdbc.update("""
            INSERT INTO protected_senders(account_id,sender_address,source)
            VALUES (?,?,'USER')
            """, account.getId(), "protected@example.com");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            cleanupActions.stage(
                owner(account),
                List.of(account.getId()),
                request(account, PreviewAction.ARCHIVE, false,
                    "ordinary@example.com", "protected@example.com")))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("protected");
        assertThat(actions.count()).isZero();
        assertThat(scopes.count()).isZero();

        mockMvc.perform(basePost(
                account,
                DecisionRowKey.sender(account.getId(), "protected@example.com"),
                "ARCHIVE").with(csrf()))
            .andExpect(status().isConflict());
        mockMvc.perform(basePost(
                account,
                DecisionRowKey.sender(account.getId(), "missing@example.com"),
                "ARCHIVE").with(csrf()))
            .andExpect(status().isBadRequest());
        mockMvc.perform(basePost(
                account,
                DecisionRowKey.sender(foreign.getId(), "foreign-sender@example.com"),
                "ARCHIVE").with(csrf()))
            .andExpect(status().isBadRequest());
        assertThat(actions.count()).isZero();
        assertThat(scopes.count()).isZero();
        verifyNoInteractions(gmail, outlook);
    }

    @Test
    void canonicalCreationOrderIsLifoDeterministicAndIncompatibleActionsConflict() {
        UserAccount account = account("order@example.com");
        seed(account, "a-1", "a@example.com");
        seed(account, "z-1", "z@example.com");

        cleanupActions.stage(
            owner(account),
            List.of(account.getId()),
            request(account, PreviewAction.ARCHIVE, false,
                "z@example.com", "a@example.com"));
        List<UserAction> ordered = actions.findAll().stream()
            .sorted(java.util.Comparator.comparingLong(UserAction::getSequence))
            .toList();
        assertThat(ordered).extracting(UserAction::getSenderGroupKey)
            .containsExactly("a@example.com", "z@example.com");
        assertThat(ordered.get(1).getSequence()).isGreaterThan(ordered.get(0).getSequence());

        cleanupActions.stage(
            owner(account),
            List.of(account.getId()),
            request(account, PreviewAction.TRASH, true, "a@example.com"));
        assertThat(conflicts.findAll()).singleElement().satisfies(conflict -> {
            assertThat(conflict.getAccountId()).isEqualTo(account.getId());
            assertThat(conflict.getReasonCode()).isEqualTo("INCOMPATIBLE_ACTIONS");
        });
        assertThat(actions.findAll()).hasSize(3);
        verifyNoInteractions(gmail, outlook);
    }

    @Test
    void concurrentEquivalentPostsReuseOneAction() throws Exception {
        UserAccount account = account("concurrent@example.com");
        seed(account, "race-1", "race@example.com");
        StageRequest request =
            request(account, PreviewAction.ARCHIVE, false, "race@example.com");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = executor.invokeAll(List.of(
                () -> cleanupActions.stage(
                    owner(account), List.of(account.getId()), request),
                () -> cleanupActions.stage(
                    owner(account), List.of(account.getId()), request)));
            assertThat(futures).allSatisfy(future ->
                assertThatCode(future::get));
        }
        assertThat(actions.count()).isEqualTo(1);
        assertThat(scopes.count()).isEqualTo(1);
        verifyNoInteractions(gmail, outlook);
    }

    private static void assertThatCode(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        org.assertj.core.api.Assertions.assertThatCode(callable).doesNotThrowAnyException();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder basePost(
            UserAccount account, String rowKey, String action) {
        return post("/api/cleanup-actions")
            .with(authentication(auth(account.getEmail())))
            .param("surface", "SENDERS")
            .param("action", action)
            .param("rowKey", rowKey)
            .param("requestToken", UUID.randomUUID().toString())
            .param("window", "all")
            .param("queue", "all")
            .param("protected", "include");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder unreadPost(
            UserAccount account, String rowKey, String action) {
        return post("/api/cleanup-actions")
            .with(authentication(auth(account.getEmail())))
            .param("surface", "UNREAD")
            .param("action", action)
            .param("rowKey", rowKey)
            .param("requestToken", UUID.randomUUID().toString())
            .param("account", account.getId().toString());
    }

    private static StageRequest request(
            UserAccount account,
            PreviewAction action,
            boolean deleteAcknowledged,
            String... senders) {
        return new StageRequest(
            query(String.valueOf(account.getId())),
            action,
            java.util.Arrays.stream(senders)
                .map(sender -> DecisionRowKey.sender(account.getId(), sender))
                .toList(),
            UUID.randomUUID(),
            deleteAcknowledged);
    }

    private static SenderQuery query(String account) {
        return new SenderQuery(
            account,
            "",
            SenderQuery.Window.ALL,
            SenderQuery.Type.ALL,
            SenderQuery.Risk.ALL,
            SenderQuery.UnreadPressure.ALL,
            SenderQuery.QueueState.ALL,
            SenderQuery.Protection.INCLUDE,
            SenderQuery.Sort.VOLUME_DESC,
            0);
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
        return accounts.saveAndFlush(account);
    }

    private MessageMetadata seed(
            UserAccount account, String messageId, String sender) {
        MessageMetadata row = row(account, messageId, sender);
        return metadata.saveAndFlush(row);
    }

    private void seedMany(
            UserAccount account, int count, String sender, String prefix) {
        ArrayList<MessageMetadata> rows = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            rows.add(row(account, prefix + index, sender));
        }
        metadata.saveAllAndFlush(rows);
    }

    private static MessageMetadata row(
            UserAccount account, String messageId, String sender) {
        MessageMetadata row = new MessageMetadata();
        row.setAccountId(account.getId());
        row.setProvider("google");
        row.setProviderMessageId(messageId);
        row.setProviderThreadId("thread-" + messageId);
        row.setFromAddress(sender);
        row.setFromDisplayName(sender);
        row.setFromDomain(sender.substring(sender.indexOf('@') + 1));
        row.setSubjectHeader(messageId);
        row.setReceivedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        row.setUnread(true);
        row.setMessageIdValues(List.of("<" + messageId + ">"));
        return row;
    }

    private static void confirmAttachment(MessageMetadata row) {
        row.setHasAttachments(true);
        row.setAttachmentState(AttachmentState.PRESENT);
        row.setAttachmentObservedAt(Instant.now());
        row.setAttachmentSource("GMAIL_SEARCH");
    }

    private static String owner(UserAccount account) {
        return account.getEmail().toLowerCase() + ":" + account.getProvider();
    }

    private static OAuth2AuthenticationToken auth(String email) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("email", email);
        attributes.put("sub", "sub-" + email);
        attributes.put("name", email);
        List<GrantedAuthority> authorities =
            List.of(new SimpleGrantedAuthority("ROLE_USER"));
        DefaultOAuth2User user = new DefaultOAuth2User(authorities, attributes, "sub");
        return new OAuth2AuthenticationToken(user, authorities, "google");
    }
}
