package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.tidymail.entity.MessageMetadata;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.AttachmentState;
import com.tidymail.mail.MailProvider;
import com.tidymail.mail.gmail.GmailMailService;
import com.tidymail.repository.MessageMetadataRepository;
import com.tidymail.repository.UserAccountRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
class Phase17UnreadOnlyTest {
    private static final Path TEMPLATES = Path.of("src/main/resources/templates");
    private static final Path CSS = Path.of("src/main/resources/static/css/workspace.css");
    private static final Path SCRIPT =
        Path.of("src/main/resources/static/js/cleanup-categories.js");

    @Autowired MockMvc mockMvc;
    @Autowired UserAccountRepository accounts;
    @Autowired MessageMetadataRepository metadata;
    @Autowired JdbcTemplate jdbc;

    @MockBean(name = "gmailMailService")
    MailProvider gmail;

    @MockBean(name = "outlookMailService")
    MailProvider outlook;

    @BeforeEach
    void clean() {
        metadata.deleteAll();
        accounts.deleteAll();
        reset(gmail, outlook);
    }

    @Test
    void unreadPageRendersVisibleInboxUnreadTotalAndNoStaleNotes()
            throws Exception {
        UserAccount account = account("visible-total@example.com");
        row(account, "inbox-unread-a", "a@example.com", true, List.of("INBOX"));
        row(account, "inbox-unread-b", "b@example.com", true, List.of("INBOX"));
        row(account, "archived-unread", "archived@example.com", true, List.of());
        row(account, "inbox-read", "read@example.com", false, List.of("INBOX"));
        jdbc.update("""
            INSERT INTO mailbox_sync_state(
                account_id, provider, initial_batch_completed_at
            ) VALUES (?, 'google', CURRENT_TIMESTAMP)
            ON CONFLICT (account_id) DO UPDATE
                SET initial_batch_completed_at = EXCLUDED.initial_batch_completed_at
            """, account.getId());

        mockMvc.perform(get("/unread")
                .with(authentication(auth(account.getEmail()))))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "Inbox unread")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "Provider Inbox messages that are still unread.")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                ">2</strong>")))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString(
                    "Counts and rows reflect the last completed metadata sync"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString(
                    "Unread groups are not mutually exclusive"))));
    }

    @Test
    void sourceKeepsTextOnlyRailAndVisibleUnreadTotalContract()
            throws Exception {
        String unread = Files.readString(TEMPLATES.resolve("unread.html"));
        String rail = Files.readString(TEMPLATES.resolve("fragments/workspace-rail.html"));
        String fragment = Files.readString(
            TEMPLATES.resolve("fragments/unread-results-frag.html"));
        String service = Files.readString(Path.of(
            "src/main/java/com/tidymail/service/UnreadDecisionQueryService.java"));

        assertThat(unread)
            .contains(
                "class=\"unread-total-panel\"",
                "aria-label=\"Inbox unread message total\"",
                "Inbox unread",
                "page.workspace.inboxUnreadCountAvailable()",
                "page.workspace.inboxUnreadCount")
            .doesNotContain(
                "Tidy up your unread emails",
                "Open a group of Inbox-unread mail and correct sender classification when needed.");
        assertThat(rail)
            .contains("Unread Only")
            .doesNotContain("rail-badge", "Unread Only <span");
        assertThat(fragment)
            .contains(
                "category.key == 'attachments'",
                "category.dataAvailable")
            .doesNotContain(
                "Counts and rows reflect the last completed metadata sync",
                "Unread groups are not mutually exclusive");
        assertThat(service)
            .contains(
                "group(\"attachments\", \"Has attachments\"",
                "Unread mail with attachment metadata in the approved index.",
                "UnreadSenderAccumulator::hasAttachments");
    }

    @Test
    void gmailMetadataMimeShapeDoesNotInferAttachmentPresence() {
        Message gmailMessage = new Message()
            .setId("gmail-attachment")
            .setThreadId("thread-attachment")
            .setInternalDate(Instant.parse("2026-08-13T12:00:00Z").toEpochMilli())
            .setLabelIds(List.of("INBOX", "UNREAD"))
            .setSizeEstimate(4096)
            .setPayload(new MessagePart()
                .setHeaders(List.of(
                    new MessagePartHeader().setName("From")
                        .setValue("Attachment Sender <attach@example.com>"),
                    new MessagePartHeader().setName("Subject")
                        .setValue("Metadata attachment")))
                .setParts(List.of(new MessagePart()
                    .setFilename("invoice.pdf")
                    .setBody(new MessagePartBody().setAttachmentId("att-1")))));

        var message = GmailMailService.toMailMessage("google", gmailMessage);

        assertThat(message.hasAttachments()).isFalse();
        assertThat(message.attachmentState()).isEqualTo(AttachmentState.UNKNOWN);
        assertThat(message.labelIds()).contains("INBOX", "UNREAD");
        assertThatThrownByBodyAccess(message);
    }

    private static void assertThatThrownByBodyAccess(com.tidymail.mail.MailMessage message) {
        org.assertj.core.api.Assertions.assertThatThrownBy(message::body)
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("METADATA-only privacy contract");
    }

    private UserAccount account(String email) {
        UserAccount account = new UserAccount();
        account.setEmail(email);
        account.setProvider("google");
        account.setProviderAccountId("provider-" + email);
        account.setEncryptedAccessToken(new byte[] {1});
        account.setEncryptedRefreshToken(new byte[] {2});
        account.setScopes(List.of("email", "gmail.modify"));
        account.setAesKeyId("hkdf-sha256-v1");
        return accounts.saveAndFlush(account);
    }

    private MessageMetadata row(
            UserAccount account,
            String messageId,
            String sender,
            boolean unread,
            List<String> labels) {
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
        row.setUnread(unread);
        row.setLabels(labels);
        row.setMessageIdValues(List.of("<" + messageId + ">"));
        return metadata.saveAndFlush(row);
    }

    private static OAuth2AuthenticationToken auth(String email) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("email", email);
        attributes.put("sub", "provider-" + email);
        attributes.put("name", email);
        List<GrantedAuthority> authorities =
            List.of(new SimpleGrantedAuthority("ROLE_USER"));
        DefaultOAuth2User user = new DefaultOAuth2User(authorities, attributes, "sub");
        return new OAuth2AuthenticationToken(user, authorities, "google");
    }
}
