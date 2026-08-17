package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.tidymail.entity.MessageMetadata;
import com.tidymail.entity.SenderCategory;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.LoadedMessageProgress;
import com.tidymail.mail.AttachmentState;
import com.tidymail.mail.MailMessage;
import com.tidymail.mail.ProviderCapabilities.LoadedMessageScope;
import com.tidymail.mail.gmail.GmailClientFactory;
import com.tidymail.mail.gmail.GmailMailService;
import com.tidymail.mail.gmail.GmailRateLimiter;
import com.tidymail.repository.SenderCategoryAssignmentRepository;
import com.tidymail.repository.SenderCategoryRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.service.MessageMetadataMapper;
import com.tidymail.service.SenderCategoryNameSanitizer;
import com.tidymail.service.SenderCategoryService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

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
class SharedDraftFoundationsTest {

    @Autowired private Flyway flyway;
    @Autowired private UserAccountRepository accounts;
    @Autowired private SenderCategoryRepository categories;
    @Autowired private SenderCategoryAssignmentRepository assignments;
    @Autowired private SenderCategoryService categoryService;

    @BeforeEach
    void clean() {
        assignments.deleteAll();
        categories.deleteAll();
        accounts.deleteAll();
    }

    @Test
    void v12IsAppliedAndCategoryNamesAreBoundedPlainText() {
        assertThat(flyway.info().applied())
            .extracting(info -> info.getVersion().getVersion())
            .contains("12");

        SenderCategoryNameSanitizer sanitizer = new SenderCategoryNameSanitizer();
        assertThat(sanitizer.sanitize("  Travel Plans  ").value()).isEqualTo("Travel Plans");
        assertThatThrownBy(() -> sanitizer.sanitize("x".repeat(81)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sanitizer.sanitize("Sales\u0000Ops"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sanitizer.sanitize("<script>alert(1)</script>"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sanitizer.sanitize("javascript:alert(1)"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void categoryAndAssignmentsAreConfinedToOwnerAndAccount() {
        UserAccount owned = account("owner-1", "owned@example.com", "provider-1");
        UserAccount foreign = account("owner-2", "foreign@example.com", "provider-2");

        SenderCategory travel = categoryService.create(
            "owner-1", owned.getId(), "  Travel  ");
        categoryService.assign(
            "owner-1", owned.getId(), travel.getId(),
            List.of("Sender <NEWS@Example.com>"));

        assertThat(categoryService.list("owner-1", owned.getId()))
            .extracting(SenderCategory::getName)
            .containsExactly("Travel");
        assertThat(categoryService.assignmentsForSenders(
                "owner-1", owned.getId(), List.of("news@example.com")))
            .singleElement()
            .satisfies(assignment -> {
                assertThat(assignment.getAccountId()).isEqualTo(owned.getId());
                assertThat(assignment.getCategoryId()).isEqualTo(travel.getId());
                assertThat(assignment.getSenderAddress()).isEqualTo("news@example.com");
            });
        assertThatThrownBy(() -> categoryService.list("owner-2", owned.getId()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not owned");
        assertThatThrownBy(() -> categoryService.assign(
                "owner-2", foreign.getId(), travel.getId(), List.of("news@example.com")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Category is not owned");
    }

    @Test
    void gmailSizeMeansFullMessageAndMetadataMimeShapeKeepsAttachmentsUnknown() {
        MessagePart attachment = new MessagePart()
            .setFilename("invoice.pdf")
            .setBody(new MessagePartBody().setAttachmentId("attachment-id"));
        Message gmail = new Message()
            .setId("message-1")
            .setThreadId("thread-1")
            .setInternalDate(1_700_000_000_000L)
            .setSizeEstimate(1_234_567)
            .setPayload(new MessagePart().setParts(List.of(attachment)));

        MailMessage projected = GmailMailService.toMailMessage("google", gmail);
        MessageMetadata metadata = new MessageMetadataMapper().apply(
            1L, projected, null, Instant.parse("2026-08-10T00:00:00Z"));

        assertThat(projected.sizeEstimate()).isEqualTo(1_234_567L);
        assertThat(projected.hasAttachments()).isFalse();
        assertThat(projected.attachmentState()).isEqualTo(AttachmentState.UNKNOWN);
        assertThat(metadata.getSizeEstimate()).isEqualTo(1_234_567L);
        assertThat(metadata.getHasAttachments()).isFalse();
        assertThat(metadata.getAttachmentState()).isEqualTo(AttachmentState.UNKNOWN);
        assertThatThrownBy(() -> new MailMessage(
            "id", "thread", "google", Instant.now(), Map.of(), Set.of(),
            -1L, null, AttachmentState.UNKNOWN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gmailArbitraryFiltersFallBackToLoadedCountWithoutDenominator() {
        GmailMailService gmail = new GmailMailService(
            mock(GmailClientFactory.class), mock(GmailRateLimiter.class));

        LoadedMessageProgress filtered = gmail.capabilities().loadedProgress(
            LoadedMessageScope.ARBITRARY_FILTER, 42, 100L);
        LoadedMessageProgress mailbox = gmail.capabilities().loadedProgress(
            LoadedMessageScope.MAILBOX, 42, 100L);

        assertThat(filtered.hasReliableDenominator()).isFalse();
        assertThat(filtered.denominator()).isEmpty();
        assertThat(filtered.statusText()).isEqualTo("Loaded 42 messages");
        assertThat(mailbox.denominator()).hasValue(100L);
        assertThat(mailbox.statusText()).isEqualTo("Loaded 42 out of 100 messages");
    }

    private UserAccount account(String ownerKey, String email, String providerAccountId) {
        UserAccount account = new UserAccount();
        account.setOwnerKey(ownerKey);
        account.setEmail(email);
        account.setProvider("google");
        account.setProviderAccountId(providerAccountId);
        account.setEncryptedAccessToken(new byte[] {1});
        account.setEncryptedRefreshToken(new byte[] {2});
        account.setScopes(List.of("gmail.modify"));
        return accounts.save(account);
    }
}
