package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tidymail.dto.SenderQuery;
import com.tidymail.entity.MessageMetadata;
import com.tidymail.entity.SenderCategory;
import com.tidymail.entity.SenderTypeOverride;
import com.tidymail.entity.SenderTypeOverride.SenderType;
import com.tidymail.entity.UserAccount;
import com.tidymail.repository.DecisionSurfaceQueryRepository;
import com.tidymail.repository.MessageMetadataRepository;
import com.tidymail.repository.SenderCategoryAssignmentRepository;
import com.tidymail.repository.SenderCategoryRepository;
import com.tidymail.repository.SenderTypeOverrideRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.service.DecisionRuleSet;
import com.tidymail.service.DecisionRuleSet.ReasonCode;
import com.tidymail.service.SenderCategoryService;
import com.tidymail.service.SenderDecisionQueryService;
import com.tidymail.service.SenderTypeOverrideService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
class Phase15ClassificationTest {

    @Autowired private Flyway flyway;
    @Autowired private UserAccountRepository accounts;
    @Autowired private SenderCategoryRepository categories;
    @Autowired private SenderCategoryAssignmentRepository assignments;
    @Autowired private SenderTypeOverrideRepository overrides;
    @Autowired private DecisionSurfaceQueryRepository decisionQueries;
    @Autowired private MessageMetadataRepository metadata;
    @Autowired private SenderCategoryService categoryService;
    @Autowired private SenderDecisionQueryService senderDecisionQueryService;
    @Autowired private SenderTypeOverrideService overrideService;

    @BeforeEach
    void clean() {
        metadata.deleteAll();
        overrides.deleteAll();
        assignments.deleteAll();
        categories.deleteAll();
        accounts.deleteAll();
    }

    @Test
    void v14PostgresSchemaValidatesAndSameSenderRemainsAccountLocal() {
        assertThat(flyway.info().applied())
            .extracting(info -> info.getVersion().getVersion())
            .contains("14");
        UserAccount first = account("owner", "first@example.com", "provider-first");
        UserAccount second = account("owner", "second@example.com", "provider-second");

        SenderTypeOverride firstOverride = overrideService.upsert(
            "owner", first.getId(), "Sender <SAME@Example.com>", SenderType.PERSONAL);
        SenderTypeOverride secondOverride = overrideService.upsert(
            "owner", second.getId(), "same@example.com", SenderType.AUTOMATED);

        assertThat(firstOverride.getSenderAddress()).isEqualTo("same@example.com");
        assertThat(firstOverride.getSenderType()).isEqualTo(SenderType.PERSONAL);
        assertThat(secondOverride.getSenderType()).isEqualTo(SenderType.AUTOMATED);
        assertThat(overrides.findAll()).hasSize(2);
        assertThatThrownBy(() -> overrideService.find(
                "other-owner", first.getId(), "same@example.com"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not owned");
    }

    @Test
    void semanticCorrectionDoesNotMutateCustomCategoryPlacement() {
        UserAccount account = account("owner", "category@example.com", "provider-category");
        SenderCategory category = categoryService.create(
            "owner", account.getId(), "Travel");
        categoryService.assign(
            "owner", account.getId(), category.getId(), List.of("news@example.com"));

        overrideService.upsert(
            "owner", account.getId(), "news@example.com", SenderType.LIST);
        overrideService.upsert(
            "owner", account.getId(), "news@example.com", SenderType.TRANSACTIONAL);
        overrideService.remove("owner", account.getId(), "news@example.com");

        assertThat(categoryService.assignmentsForSenders(
                "owner", account.getId(), List.of("news@example.com")))
            .singleElement()
            .satisfies(assignment -> {
                assertThat(assignment.getCategoryId()).isEqualTo(category.getId());
                assertThat(assignment.getSenderAddress()).isEqualTo("news@example.com");
            });
        assertThat(overrideService.find(
            "owner", account.getId(), "news@example.com")).isEmpty();
    }

    @Test
    void personalWithAutomatedHeaderIsNotClassifiedAsAutomated() {
        var decision = classify(facts(false, true, false, true, true));

        assertThat(decision.type()).isEqualTo(DecisionRuleSet.SenderType.PERSONAL);
        assertThat(decision.reasons()).contains(ReasonCode.DIRECT_MESSAGE);
    }

    @Test
    void personalWithReturnPathIsNotClassifiedAsTransactional() {
        var decision = classify(facts(false, false, true, true, true));

        assertThat(decision.type()).isEqualTo(DecisionRuleSet.SenderType.PERSONAL);
        assertThat(decision.reasons()).contains(ReasonCode.DIRECT_MESSAGE);
    }

    @Test
    void transactionalWithListLikeHeaderIsNotClassifiedAsList() {
        var decision = classify(facts(true, false, true, true, false));

        assertThat(decision.type()).isEqualTo(DecisionRuleSet.SenderType.TRANSACTIONAL);
        assertThat(decision.reasons()).contains(ReasonCode.TRANSACTIONAL_RETURN_PATH);
    }

    @Test
    void automatedListWithReturnPathIsNotClassifiedAsTransactional() {
        var decision = classify(facts(true, true, true, true, false));

        assertThat(decision.type()).isEqualTo(DecisionRuleSet.SenderType.LIST);
        assertThat(decision.reasons()).contains(ReasonCode.LIST_HEADERS_PRESENT);
    }

    @Test
    void sqlAndJavaStayInParityForCorrectedFalsePositiveDirections() {
        UserAccount account = account("owner", "parity@example.com", "provider-parity");
        metadata.saveAllAndFlush(List.of(
            message(account, "personal-automated@example.com", false, true, false, true),
            message(account, "personal-transactional@example.com", false, false, true, true),
            message(account, "transactional-list@example.com", true, false, true, false),
            message(account, "list-transactional@example.com", true, true, true, false)));

        SenderQuery query = SenderQuery.parse(Map.of(
            "account", List.of(account.getId().toString()),
            "window", List.of("all"),
            "queue", List.of("all"),
            "protected", List.of("include")));
        Map<String, DecisionRuleSet.SenderType> types = senderDecisionQueryService
            .query(List.of(account.getId()), query)
            .rows()
            .stream()
            .collect(Collectors.toMap(row -> row.senderAddress(), row -> row.type()));

        assertThat(types)
            .containsEntry(
                "personal-automated@example.com", DecisionRuleSet.SenderType.PERSONAL)
            .containsEntry(
                "personal-transactional@example.com", DecisionRuleSet.SenderType.PERSONAL)
            .containsEntry(
                "transactional-list@example.com", DecisionRuleSet.SenderType.TRANSACTIONAL)
            .containsEntry(
                "list-transactional@example.com", DecisionRuleSet.SenderType.LIST);
    }

    @Test
    void manualOverrideIsExplainableAndRemovalRevealsCorrectedInference() {
        UserAccount account = account("owner", "manual@example.com", "provider-manual");
        overrideService.upsert(
            "owner", account.getId(), "sender@example.com", SenderType.PERSONAL);
        var senderFacts = facts(false, true, false, false, false);

        var overridden = DecisionRuleSet.V1.classify(
            senderFacts, Instant.parse("2026-08-11T00:00:00Z"),
            DecisionRuleSet.SenderType.PERSONAL);
        assertThat(overridden.type()).isEqualTo(DecisionRuleSet.SenderType.PERSONAL);
        assertThat(overridden.reasons())
            .contains(ReasonCode.MANUAL_SENDER_TYPE_OVERRIDE)
            .doesNotContain(ReasonCode.AUTOMATED_HEADER_PRESENT);

        overrideService.remove("owner", account.getId(), "sender@example.com");
        assertThat(overrideService.find(
            "owner", account.getId(), "sender@example.com")).isEmpty();
        var inferred = classify(senderFacts);
        assertThat(inferred.type()).isEqualTo(DecisionRuleSet.SenderType.AUTOMATED);
        assertThat(inferred.reasons())
            .contains(ReasonCode.AUTOMATED_HEADER_PRESENT)
            .doesNotContain(ReasonCode.MANUAL_SENDER_TYPE_OVERRIDE);
    }

    @Test
    void effectiveSqlOverrideWinsAfterMetadataUpdatesAndStaysAccountLocal() {
        UserAccount first = account("owner", "effective-first@example.com", "effective-first");
        UserAccount second = account("owner", "effective-second@example.com", "effective-second");
        MessageMetadata firstRow =
            message(first, "same@example.com", false, false, false, false);
        MessageMetadata secondRow =
            message(second, "same@example.com", false, false, false, false);
        metadata.saveAllAndFlush(List.of(firstRow, secondRow));
        overrideService.upsert(
            "owner", first.getId(), "same@example.com", SenderType.PERSONAL);
        overrideService.upsert(
            "owner", second.getId(), "same@example.com", SenderType.AUTOMATED);

        SenderQuery query = SenderQuery.parse(Map.of(
            "account", List.of("all"),
            "window", List.of("all"),
            "queue", List.of("all"),
            "protected", List.of("include")));
        Map<Long, DecisionRuleSet.SenderType> effective = senderDecisionQueryService
            .query(List.of(first.getId(), second.getId()), query)
            .rows()
            .stream()
            .collect(Collectors.toMap(row -> row.accountId(), row -> row.type()));
        assertThat(effective)
            .containsEntry(first.getId(), DecisionRuleSet.SenderType.PERSONAL)
            .containsEntry(second.getId(), DecisionRuleSet.SenderType.AUTOMATED);
        assertThat(decisionQueries.senders(
                List.of(first.getId(), second.getId()), query, Instant.now()).rows())
            .extracting(
                DecisionSurfaceQueryRepository.SenderFact::accountId,
                DecisionSurfaceQueryRepository.SenderFact::manualType)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(
                    first.getId(), DecisionRuleSet.SenderType.PERSONAL),
                org.assertj.core.groups.Tuple.tuple(
                    second.getId(), DecisionRuleSet.SenderType.AUTOMATED));

        firstRow.setAutoSubmittedValues(List.of("auto-generated"));
        metadata.saveAndFlush(firstRow);
        assertThat(senderDecisionQueryService.query(
                List.of(first.getId()),
                queryFor(first.getId())).rows().getFirst().type())
            .isEqualTo(DecisionRuleSet.SenderType.PERSONAL);

        overrideService.remove("owner", first.getId(), "same@example.com");
        assertThat(senderDecisionQueryService.query(
                List.of(first.getId()),
                queryFor(first.getId())).rows().getFirst().type())
            .isEqualTo(DecisionRuleSet.SenderType.AUTOMATED);
    }

    private static DecisionRuleSet.SenderDecision classify(
            DecisionRuleSet.SenderFacts facts) {
        return DecisionRuleSet.V1.classify(
            facts, Instant.parse("2026-08-11T00:00:00Z"));
    }

    private static SenderQuery queryFor(long accountId) {
        return SenderQuery.parse(Map.of(
            "account", List.of(Long.toString(accountId)),
            "window", List.of("all"),
            "queue", List.of("all"),
            "protected", List.of("include")));
    }

    private static DecisionRuleSet.SenderFacts facts(
            boolean list,
            boolean automated,
            boolean returnPath,
            boolean messageId,
            boolean thread) {
        return new DecisionRuleSet.SenderFacts(
            list, automated, returnPath, messageId, thread,
            false, 2, 1, Instant.parse("2026-08-01T00:00:00Z"));
    }

    private static MessageMetadata message(
            UserAccount account,
            String sender,
            boolean list,
            boolean automated,
            boolean returnPath,
            boolean directThread) {
        MessageMetadata row = new MessageMetadata();
        row.setAccountId(account.getId());
        row.setProvider("google");
        row.setProviderMessageId("parity-" + sender);
        row.setFromAddress(sender);
        row.setFromDisplayName(sender);
        row.setFromDomain("example.com");
        row.setReceivedAt(Instant.parse("2026-08-01T00:00:00Z"));
        row.setListIdValues(list ? List.of("list.example.com") : List.of());
        row.setAutoSubmittedValues(automated ? List.of("auto-generated") : List.of());
        row.setReturnPathValues(returnPath ? List.of("<bounce@example.com>") : List.of());
        row.setMessageIdValues(directThread ? List.of("<message@example.com>") : List.of());
        row.setReferencesValues(directThread ? List.of("<parent@example.com>") : List.of());
        return row;
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
