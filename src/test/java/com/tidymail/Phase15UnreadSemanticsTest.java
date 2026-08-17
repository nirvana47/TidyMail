package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;

import com.tidymail.dto.SelectionMode;
import com.tidymail.dto.UnreadCategoryGroup;
import com.tidymail.dto.UnreadQuery;
import com.tidymail.entity.MessageMetadata;
import com.tidymail.entity.SenderTypeOverride.SenderType;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.AttachmentState;
import com.tidymail.repository.DecisionSurfaceQueryRepository;
import com.tidymail.repository.DecisionSurfaceQueryRepository.SelectionLocator;
import com.tidymail.repository.MessageMetadataRepository;
import com.tidymail.repository.SenderTypeOverrideRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.service.DecisionRuleSet.UnreadCategory;
import com.tidymail.service.SenderTypeOverrideService;
import com.tidymail.service.UnreadDecisionQueryService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
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
class Phase15UnreadSemanticsTest {

    @Autowired private UserAccountRepository accounts;
    @Autowired private MessageMetadataRepository metadata;
    @Autowired private SenderTypeOverrideRepository overrides;
    @Autowired private SenderTypeOverrideService overrideService;
    @Autowired private DecisionSurfaceQueryRepository decisions;
    @Autowired private UnreadDecisionQueryService unreadService;

    @BeforeEach
    void clean() {
        metadata.deleteAll();
        overrides.deleteAll();
        accounts.deleteAll();
    }

    @Test
    void everyUnreadReadUsesInboxAndUnreadTogether() {
        UserAccount account = account("inbox@example.com");
        seed(account, "inbox-unread", "sender@example.com", true, List.of("INBOX"));
        seed(account, "inbox-read", "sender@example.com", false, List.of("INBOX"));
        seed(account, "archived-unread", "sender@example.com", true, List.of());
        seed(account, "spam-unread", "sender@example.com", true, List.of("SPAM"));
        seed(account, "trash-unread", "sender@example.com", true, List.of("TRASH"));
        UnreadQuery query = query(account);

        assertThat(decisions.countUnread(
            List.of(account.getId()), query, Instant.now())).isOne();
        assertThat(decisions.unread(
                List.of(account.getId()), query, Instant.now()).rows())
            .extracting(DecisionSurfaceQueryRepository.UnreadFact::providerMessageId)
            .containsExactly("inbox-unread");
        assertThat(unreadService.query(List.of(account.getId()), query).rows())
            .extracting(row -> row.senderAddress())
            .containsExactly("sender@example.com");
        assertThat(unreadService.categories(List.of(account.getId()), query).stream()
            .flatMap(group -> group.rows().stream())
            .allMatch(row -> row.unreadCount() == 1)).isTrue();
    }

    @Test
    void manualTypeControlsOverlappingGroupsWithoutChangingInboxBase() {
        UserAccount account = account("groups@example.com");
        MessageMetadata row = seed(
            account, "overlap", "same@example.com", true, List.of("INBOX"));
        row.setListIdValues(List.of("list.example.com"));
        row.setHasAttachments(true);
        row.setAttachmentState(AttachmentState.PRESENT);
        row.setAttachmentObservedAt(Instant.now());
        row.setAttachmentSource("GMAIL_SEARCH");
        row.setReceivedAt(Instant.now().minus(40, ChronoUnit.DAYS));
        metadata.saveAndFlush(row);
        UnreadQuery query = query(account);

        List<UnreadCategoryGroup> inferred =
            unreadService.categories(List.of(account.getId()), query);
        assertThat(group(inferred, "lists-automated").rows()).hasSize(1);
        assertThat(group(inferred, "older-than-30-days").rows()).hasSize(1);
        assertThat(group(inferred, "attachments").rows()).hasSize(1);
        assertThat(group(inferred, "direct-mail").rows()).isEmpty();

        overrideService.upsert(
            account.getOwnerKey(), account.getId(), "same@example.com", SenderType.PERSONAL);
        List<UnreadCategoryGroup> effective =
            unreadService.categories(List.of(account.getId()), query);
        assertThat(group(effective, "lists-automated").rows()).isEmpty();
        assertThat(group(effective, "direct-mail").rows()).hasSize(1);
        assertThat(group(effective, "older-than-30-days").rows()).hasSize(1);
        assertThat(group(effective, "attachments").rows()).hasSize(1);
        assertThat(decisions.unread(
                List.of(account.getId()), query, Instant.now()).rows().getFirst())
            .satisfies(fact -> {
                assertThat(fact.category()).isEqualTo(UnreadCategory.ONE_TO_ONE);
                assertThat(fact.manualType())
                    .isEqualTo(com.tidymail.service.DecisionRuleSet.SenderType.PERSONAL);
            });
    }

    @Test
    void overlappingSenderSelectionsDeduplicateExactProviderIds() {
        UserAccount account = account("materialize@example.com");
        MessageMetadata first =
            seed(account, "exact-a", "same@example.com", true, List.of("INBOX"));
        MessageMetadata second =
            seed(account, "exact-b", "same@example.com", true, List.of("INBOX"));
        List<SelectionLocator> overlapping = List.of(
            locator(account, first),
            locator(account, second),
            locator(account, first),
            locator(account, second));

        var result = decisions.materializeUnread(
            List.of(account.getId()),
            query(account),
            SelectionMode.INDIVIDUAL,
            overlapping,
            false,
            Instant.now());

        assertThat(result.matchedTargetCount()).isEqualTo(2);
        assertThat(result.accounts()).singleElement().satisfies(materialized -> {
            assertThat(materialized.exactMessageCount()).isEqualTo(2);
            assertThat(materialized.messageIds()).containsExactly("exact-a", "exact-b");
        });
    }

    @Test
    void inboxUnreadMaterializationPreservesThe1000And1001Boundary() {
        UserAccount account = account("cap@example.com");
        ArrayList<MessageMetadata> rows = new ArrayList<>();
        for (int index = 0; index < 1_000; index++) {
            rows.add(message(
                account, "cap-" + index, "bulk@example.com", true, List.of("INBOX")));
        }
        metadata.saveAllAndFlush(rows);

        var accepted = decisions.materializeUnread(
            List.of(account.getId()),
            query(account),
            SelectionMode.ALL_MATCHING,
            List.of(),
            false,
            Instant.now());
        assertThat(accepted.accounts()).singleElement().satisfies(materialized -> {
            assertThat(materialized.exactMessageCount()).isEqualTo(1_000);
            assertThat(materialized.messageIds()).hasSize(1_000);
        });

        metadata.saveAndFlush(
            message(account, "cap-1000", "bulk@example.com", true, List.of("INBOX")));
        var guarded = decisions.materializeUnread(
            List.of(account.getId()),
            query(account),
            SelectionMode.ALL_MATCHING,
            List.of(),
            false,
            Instant.now());
        assertThat(guarded.accounts()).singleElement().satisfies(materialized -> {
            assertThat(materialized.exactMessageCount()).isEqualTo(1_001);
            assertThat(materialized.messageIds()).hasSize(1_001);
        });
    }

    private static SelectionLocator locator(UserAccount account, MessageMetadata row) {
        return new SelectionLocator(account.getId(), Long.toString(row.getId()));
    }

    private static UnreadCategoryGroup group(
            List<UnreadCategoryGroup> groups, String key) {
        return groups.stream()
            .filter(group -> group.key().equals(key))
            .findFirst()
            .orElseThrow();
    }

    private static UnreadQuery query(UserAccount account) {
        return UnreadQuery.parse(Map.of(
            "account", List.of(Long.toString(account.getId()))));
    }

    private UserAccount account(String email) {
        UserAccount account = new UserAccount();
        account.setOwnerKey("owner:google");
        account.setEmail(email);
        account.setProvider("google");
        account.setProviderAccountId("provider-" + email);
        account.setEncryptedAccessToken(new byte[] {1});
        account.setEncryptedRefreshToken(new byte[] {2});
        account.setScopes(List.of("gmail.modify"));
        return accounts.saveAndFlush(account);
    }

    private MessageMetadata seed(
            UserAccount account,
            String messageId,
            String sender,
            boolean unread,
            List<String> labels) {
        return metadata.saveAndFlush(message(account, messageId, sender, unread, labels));
    }

    private static MessageMetadata message(
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
        row.setFromDomain("example.com");
        row.setSubjectHeader(messageId);
        row.setReceivedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        row.setUnread(unread);
        row.setLabels(labels);
        return row;
    }
}
