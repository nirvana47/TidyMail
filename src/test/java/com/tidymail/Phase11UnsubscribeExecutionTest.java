package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import com.tidymail.dto.UnsubscribeQuery;
import com.tidymail.entity.MessageMetadata;
import com.tidymail.entity.UserAccount;
import com.tidymail.repository.MessageMetadataRepository;
import com.tidymail.repository.UnsubscribeAttemptRepository;
import com.tidymail.repository.UnsubscribeIntentNonceRepository;
import com.tidymail.repository.UnsubscribeOutcomeEventRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.security.crypto.CryptoUtil;
import com.tidymail.service.UnsubscribeCandidateService;
import com.tidymail.service.UnsubscribeExecutionService;
import com.tidymail.service.UnsubscribeOutcomeService;
import com.tidymail.service.UnsubscribeOutcomeService.OutcomeEventView;
import com.tidymail.unsubscribe.DestinationResolver;
import com.tidymail.unsubscribe.ResolvedDestination;
import com.tidymail.unsubscribe.UnsubscribeConfirmationSource;
import com.tidymail.unsubscribe.UnsubscribeHttpTransport;
import com.tidymail.unsubscribe.UnsubscribeIntentService;
import com.tidymail.unsubscribe.UnsubscribeIntentService.IntentException;
import com.tidymail.unsubscribe.UnsubscribeMethod;
import com.tidymail.unsubscribe.UnsubscribeOutcomeStatus;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
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
class Phase11UnsubscribeExecutionTest {
    @Autowired UserAccountRepository accounts;
    @Autowired MessageMetadataRepository metadata;
    @Autowired UnsubscribeCandidateService candidates;
    @Autowired UnsubscribeIntentService intents;
    @Autowired UnsubscribeExecutionService execution;
    @Autowired UnsubscribeOutcomeService outcomes;
    @Autowired UnsubscribeAttemptRepository attempts;
    @Autowired UnsubscribeOutcomeEventRepository events;
    @Autowired UnsubscribeIntentNonceRepository nonces;
    @Autowired JdbcTemplate jdbc;
    @Autowired CryptoUtil crypto;

    @MockBean DestinationResolver resolver;
    @MockBean UnsubscribeHttpTransport transport;

    @BeforeEach
    void clean() {
        reset(resolver, transport);
        jdbc.update("DELETE FROM user_accounts");
    }

    @Test
    void pendingIsCommittedBeforeOneTransportCallAndAcceptedRemainsVerificationPending()
            throws Exception {
        UserAccount owner = account("owner@example.com");
        seed(owner, true);
        String key = candidateKey(owner);
        String intent = intents.issue(
            List.of(owner.getId()), key, UnsubscribeMethod.RFC8058_POST);
        when(resolver.resolveAndValidate("unsubscribe.example")).thenAnswer(invocation -> {
            assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM unsubscribe_intent_nonces
                WHERE consumed_at IS NOT NULL
                """, Long.class)).isOne();
            assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM unsubscribe_attempts
                WHERE latest_status = 'PENDING'
                """, Long.class)).isOne();
            assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM unsubscribe_outcome_events
                WHERE status = 'PENDING'
                """, Long.class)).isOne();
            return new ResolvedDestination(
                "unsubscribe.example", List.of(InetAddress.getByName("8.8.8.8")));
        });
        doAnswer(invocation -> {
            assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM unsubscribe_attempts
                WHERE latest_status = 'PENDING'
                """, Long.class)).isOne();
            assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM unsubscribe_outcome_events
                WHERE status = 'PENDING'
                """, Long.class)).isOne();
            return UnsubscribeHttpTransport.TransportResult.accepted();
        }).when(transport).execute(any());

        var result = execution.execute(
            List.of(owner.getId()), key, UnsubscribeMethod.RFC8058_POST, intent);

        assertThat(result.outcome().latestStatus())
            .isEqualTo(UnsubscribeOutcomeStatus.VERIFICATION_PENDING);
        assertThat(result.outcome().events())
            .extracting(OutcomeEventView::status)
            .containsExactly(
                UnsubscribeOutcomeStatus.PENDING,
                UnsubscribeOutcomeStatus.ACCEPTED,
                UnsubscribeOutcomeStatus.VERIFICATION_PENDING);
        assertThat(result.outcome().events().get(1).confirmationSource())
            .isEqualTo("Endpoint accepted");
        assertThat(result.outcome().events())
            .noneMatch(event -> event.status() == UnsubscribeOutcomeStatus.CONFIRMED);
        assertThatThrownBy(() -> outcomes.failed(
            owner.getId(), result.outcome().attemptId(),
            com.tidymail.unsubscribe.UnsubscribeReasonCode.TRANSPORT_ERROR, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(events.findByAttemptIdAndAccountIdOrderByOccurredAtAscIdAsc(
            result.outcome().attemptId(), owner.getId())).hasSize(3);
        verify(transport).execute(any());
        assertThatThrownBy(() -> execution.execute(
            List.of(owner.getId()), key, UnsubscribeMethod.RFC8058_POST, intent))
            .isInstanceOf(IntentException.class);
        verify(transport).execute(any());
    }

    @Test
    void explicitLegacyGetIsBodylessAndPostFailureNeverFallsBack() throws Exception {
        UserAccount owner = account("owner@example.com");
        seed(owner, false);
        String key = candidateKey(owner);
        String intent = intents.issue(
            List.of(owner.getId()), key, UnsubscribeMethod.LEGACY_HTTPS_GET);
        when(resolver.resolveAndValidate("unsubscribe.example")).thenReturn(
            new ResolvedDestination(
                "unsubscribe.example", List.of(InetAddress.getByName("8.8.8.8"))));
        when(transport.execute(any())).thenReturn(
            UnsubscribeHttpTransport.TransportResult.failed(
                3, UnsubscribeHttpTransport.FailureReason.REDIRECT_BLOCKED));

        var result = execution.execute(
            List.of(owner.getId()), key, UnsubscribeMethod.LEGACY_HTTPS_GET, intent);

        ArgumentCaptor<com.tidymail.unsubscribe.ValidatedUnsubscribeRequest> request =
            ArgumentCaptor.forClass(
                com.tidymail.unsubscribe.ValidatedUnsubscribeRequest.class);
        verify(transport, times(1)).execute(request.capture());
        assertThat(request.getValue().method())
            .isEqualTo(
                com.tidymail.unsubscribe.ValidatedUnsubscribeRequest.Method.LEGACY_HTTPS_GET);
        assertThat(request.getValue().body()).isEmpty();
        assertThat(request.getValue().uri().toASCIIString())
            .contains("https://unsubscribe.example/private/TOKEN_CANARY");
        assertThat(result.outcome().latestStatus())
            .isEqualTo(UnsubscribeOutcomeStatus.FAILED);
        assertThat(result.outcome().events())
            .extracting(OutcomeEventView::reason)
            .contains(com.tidymail.unsubscribe.UnsubscribeReasonCode.REDIRECT_BLOCKED);
    }

    @Test
    void changedProtectionRollsBackNonceThenSuccessfulExecutionConsumesItOnce()
            throws Exception {
        UserAccount owner = account("owner@example.com");
        UserAccount foreign = account("foreign@example.com");
        seed(owner, true);
        String key = candidateKey(owner);
        String intent = intents.issue(
            List.of(owner.getId()), key, UnsubscribeMethod.RFC8058_POST);
        jdbc.update("""
            INSERT INTO protected_senders(account_id, sender_address, source)
            VALUES (?, 'news@example.com', 'USER')
            """, owner.getId());

        assertThatThrownBy(() -> execution.execute(
            List.of(owner.getId()), key, UnsubscribeMethod.RFC8058_POST, intent))
            .isInstanceOfSatisfying(
                IntentException.class,
                failure -> assertThat(failure.reason().name())
                    .isEqualTo("PROTECTED_SENDER"));
        jdbc.update("""
            DELETE FROM protected_senders
            WHERE account_id = ? AND sender_address = 'news@example.com'
            """, owner.getId());
        when(resolver.resolveAndValidate("unsubscribe.example")).thenReturn(
            new ResolvedDestination(
                "unsubscribe.example", List.of(InetAddress.getByName("8.8.8.8"))));
        when(transport.execute(any())).thenReturn(
            UnsubscribeHttpTransport.TransportResult.accepted());

        var result = execution.execute(
            List.of(owner.getId()), key, UnsubscribeMethod.RFC8058_POST, intent);
        assertThat(result.outcome().latestStatus())
            .isEqualTo(UnsubscribeOutcomeStatus.VERIFICATION_PENDING);

        assertThatThrownBy(() -> execution.execute(
            List.of(owner.getId()), key, UnsubscribeMethod.RFC8058_POST, intent))
            .isInstanceOfSatisfying(
                IntentException.class,
                failure -> assertThat(failure.reason().name())
                    .isEqualTo("REPLAYED_INTENT"));
        assertThatThrownBy(() -> execution.execute(
            List.of(foreign.getId()), key, UnsubscribeMethod.RFC8058_POST, intent))
            .isInstanceOf(IntentException.class);
        assertThat(attempts.count()).isOne();
        assertThat(events.count()).isEqualTo(3);
        verify(resolver).resolveAndValidate("unsubscribe.example");
        verify(transport).execute(any());
    }

    @Test
    void changedDestinationAfterInspectionFailsBeforeAttemptOrIo() {
        UserAccount owner = account("owner@example.com");
        MessageMetadata row = seed(owner, true);
        String key = candidateKey(owner);
        String intent = intents.issue(
            List.of(owner.getId()), key, UnsubscribeMethod.RFC8058_POST);
        row.setListUnsubscribeValues(List.of(
            "<https://replacement.example/private?secret=CHANGED_CANARY>"));
        metadata.saveAndFlush(row);

        assertThatThrownBy(() -> execution.execute(
            List.of(owner.getId()), key, UnsubscribeMethod.RFC8058_POST, intent))
            .isInstanceOfSatisfying(
                IntentException.class,
                failure -> assertThat(failure.reason())
                    .isEqualTo(com.tidymail.unsubscribe.UnsubscribeReasonCode.STALE_INTENT));
        assertThat(attempts.count()).isZero();
        assertThat(events.count()).isZero();
        verifyNoInteractions(resolver, transport);
    }

    @Test
    void manualCompletionIsExplicitAppendOnlyAndConfirmationNamesItsSource() {
        UserAccount owner = account("owner@example.com");
        MessageMetadata row = seed(owner, false);
        row.setListUnsubscribeValues(List.of("<mailto:leave@example.com?subject=hidden>"));
        row.setListUnsubscribePostValues(List.of());
        metadata.save(row);
        String key = candidateKey(owner);
        String intent = intents.issue(
            List.of(owner.getId()), key, UnsubscribeMethod.MAILTO);

        assertThat(attempts.count()).isZero();
        var result = execution.completeManual(
            List.of(owner.getId()), key, UnsubscribeMethod.MAILTO, intent, true);

        assertThat(result.outcome().events())
            .extracting(OutcomeEventView::status)
            .containsExactly(
                UnsubscribeOutcomeStatus.MANUAL_REQUIRED,
                UnsubscribeOutcomeStatus.VERIFICATION_PENDING,
                UnsubscribeOutcomeStatus.CONFIRMED);
        assertThat(result.outcome().events().getLast().confirmationSource())
            .isEqualTo("Confirmed by you");
        assertThat(attempts.findAll()).singleElement().satisfies(attempt -> {
            assertThat(attempt.getConfirmationSource())
                .isEqualTo(UnsubscribeConfirmationSource.USER_CONFIRMED);
            assertThat(attempt.toString()).doesNotContain(
                "leave@example.com", "subject=hidden");
        });
        verifyNoInteractions(resolver, transport);
    }

    @Test
    void concurrentDoubleSubmitConsumesOnceAndTimeoutIsNotRetried() throws Exception {
        UserAccount owner = account("owner@example.com");
        seed(owner, true);
        String key = candidateKey(owner);
        String intent = intents.issue(
            List.of(owner.getId()), key, UnsubscribeMethod.RFC8058_POST);
        when(resolver.resolveAndValidate("unsubscribe.example")).thenReturn(
            new ResolvedDestination(
                "unsubscribe.example", List.of(InetAddress.getByName("8.8.8.8"))));
        when(transport.execute(any())).thenReturn(
            UnsubscribeHttpTransport.TransportResult.failed(
                null,
                UnsubscribeHttpTransport.FailureReason.TIMEOUT_UNKNOWN_REMOTE_RESULT));

        Callable<Boolean> submit = () -> {
            try {
                execution.execute(
                    List.of(owner.getId()), key, UnsubscribeMethod.RFC8058_POST, intent);
                return true;
            } catch (IntentException replayed) {
                return false;
            }
        };
        List<Boolean> results;
        try (var executor = Executors.newFixedThreadPool(2)) {
            results = executor.invokeAll(List.of(submit, submit)).stream()
                .map(future -> {
                    try {
                        return future.get();
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                }).toList();
        }

        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(attempts.findAll()).singleElement()
            .satisfies(attempt -> {
                assertThat(attempt.getLatestStatus())
                    .isEqualTo(UnsubscribeOutcomeStatus.FAILED);
                assertThat(attempt.getReasonCode().name())
                    .isEqualTo("TIMEOUT_UNKNOWN_REMOTE_RESULT");
            });
        verify(transport, times(1)).execute(any());
    }

    @Test
    void frozenSchemaAndEntitiesContainOnlyBoundedAllowlistedFacts() {
        Set<String> expectedAttemptColumns = Set.of(
            "id", "account_id", "candidate_fingerprint", "sender_key_fingerprint",
            "method", "destination_host", "destination_fingerprint", "created_at",
            "completed_at", "latest_status", "http_status_class", "reason_code",
            "confirmation_source");
        Set<String> expectedEventColumns = Set.of(
            "id", "account_id", "attempt_id", "status", "occurred_at",
            "reason_code", "http_status_class", "confirmation_source");
        Set<String> expectedNonceColumns = Set.of(
            "id", "account_id", "nonce_fingerprint", "candidate_fingerprint",
            "destination_fingerprint", "method", "issued_at", "expires_at",
            "consumed_at");
        assertThat(columns("unsubscribe_attempts")).containsExactlyInAnyOrderElementsOf(
            expectedAttemptColumns);
        assertThat(columns("unsubscribe_outcome_events")).containsExactlyInAnyOrderElementsOf(
            expectedEventColumns);
        assertThat(columns("unsubscribe_intent_nonces")).containsExactlyInAnyOrderElementsOf(
            expectedNonceColumns);
        String fields = String.join(" ",
            java.util.Arrays.stream(com.tidymail.entity.UnsubscribeAttempt.class
                .getDeclaredFields()).map(java.lang.reflect.Field::getName).toList());
        assertThat(fields).doesNotContain(
            "uri", "query", "header", "body", "providerMessage", "token",
            "cookie", "credential", "exception", "response");
    }

    @Test
    void transportResultsRejectContradictoryOrUnpersistableFacts() {
        assertThatThrownBy(() -> new UnsubscribeHttpTransport.TransportResult(
            UnsubscribeHttpTransport.Outcome.ACCEPTED, 2,
            UnsubscribeHttpTransport.FailureReason.IO_FAILURE))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UnsubscribeHttpTransport.TransportResult.failed(
            1, UnsubscribeHttpTransport.FailureReason.REMOTE_REJECTED))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UnsubscribeHttpTransport.TransportResult.failed(
            null, UnsubscribeHttpTransport.FailureReason.NONE))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private Set<String> columns(String table) {
        return Set.copyOf(jdbc.queryForList("""
            SELECT column_name FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = ?
            """, String.class, table));
    }

    private String candidateKey(UserAccount account) {
        return candidates.query(List.of(account.getId()), UnsubscribeQuery.parse(Map.of()))
            .rows().getFirst().candidateKey();
    }

    private UserAccount account(String email) {
        UserAccount account = new UserAccount();
        account.setEmail(email);
        account.setProvider("google");
        account.setProviderAccountId("google-" + email);
        account.setEncryptedAccessToken(crypto.encrypt("access".getBytes(StandardCharsets.UTF_8)));
        account.setEncryptedRefreshToken(
            crypto.encrypt("refresh".getBytes(StandardCharsets.UTF_8)));
        account.setScopes(List.of("email"));
        account.setAesKeyId("hkdf-sha256-v1");
        return accounts.save(account);
    }

    private MessageMetadata seed(UserAccount account, boolean trusted) {
        MessageMetadata row = new MessageMetadata();
        row.setAccountId(account.getId());
        row.setProvider("google");
        row.setProviderMessageId("PRIVATE_PROVIDER_ID");
        row.setProviderThreadId("PRIVATE_THREAD_ID");
        row.setFromAddress("news@example.com");
        row.setFromDisplayName("Example News");
        row.setFromDomain("example.com");
        row.setReceivedAt(Instant.now());
        row.setListIdValues(List.of("news.list.example"));
        row.setListUnsubscribeValues(List.of(
            "<https://unsubscribe.example/private/TOKEN_CANARY?secret=TOKEN_CANARY>"));
        row.setListUnsubscribePostValues(List.of("List-Unsubscribe=One-Click"));
        row.setUnsubscribeDkimPass(trusted);
        row.setUnsubscribeDkimCoversListUnsubscribe(trusted);
        row.setUnsubscribeDkimCoversListUnsubscribePost(trusted);
        row.setUnsubscribeAuthReason(trusted ? "TRUSTED_PASS" : "NO_TRUSTED_RESULT");
        return metadata.save(row);
    }
}
