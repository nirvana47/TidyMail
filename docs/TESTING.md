# Testing and acceptance

The Maven suite uses JUnit 5, Spring Boot Test, Spring Security Test, MockMvc and Testcontainers PostgreSQL 16. There is no H2 dependency or alternate test migration tree. Production Flyway migrations are used for Spring integration tests.

## Harness and commands

`support/PostgresJourneyTest` starts a shared PostgreSQL container, creates OAuth fixtures, mocks external mail providers and offers CSRF/authentication helpers. `PostgresTestContextCustomizerFactory`, registered in `src/test/resources/META-INF/spring.factories`, routes Spring contexts to that container. Cleanup fixtures isolate database state. `SyntheticMailboxGenerator` creates deterministic mailbox-shaped data; its PII guard validates synthetic fixtures.

Use Java 21 and a running Docker engine. Run from the repository root:

```sh
mvn test
mvn -Dtest='MailboxFreshnessJourneyTest,MailboxActivityJourneyTest,OAuthAuthorizedClientRefreshTest,GmailSynchronizationContractTest' test
```

For Colima, if Docker is not already configured for Testcontainers:

```sh
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
mvn test
```

The harness owns its test database; it does not use the local Compose development database. Some focused tests are pure unit tests, but Spring integration and journey tests require Docker. Maven/Surefire reports appear in `target/surefire-reports/`; a skipped-tests package build is not validation.

## Coverage navigation

| Change area | Representative tests |
|---|---|
| Mailbox sync/OAuth | `GmailSynchronizationContractTest`, `MailboxSyncPersistenceTest`, `MailboxFreshnessConcurrencyTest`, `OAuthAuthorizedClientRefreshTest` |
| Activity/first run | `journey/MailboxFreshnessJourneyTest`, `journey/MailboxActivityJourneyTest` |
| Sender/unread decisions | `journey/SenderTriageJourneyTest`, `journey/UnreadReviewJourneyTest`, Phase 10/15/17 groups |
| Cleanup/Review/undo | `journey/BulkActionJourneyTest`, Phase 12 execution/state/undo groups |
| Unsubscribe | `journey/UnsubscribeJourneyTest`, Phase 11 parser/security/execution tests, `unsubscribe/Phase11UnsubscribeOnWireTlsTest` |
| Analytics | `journey/AnalyticsJourneyTest`, Phase 13 query/metric/repository groups |
| Ownership/privacy | `Phase12MultiAccountTest`, `Phase12PrivacyTest`, `security/LogoutSemanticsTest`, security configuration tests |
| Removal of export | `journey/ExportRemovedJourneyTest` |
| Performance | `MailboxSyncPerformanceTest`, Phase 13/15 performance tests; synthetic volumes, not live-provider throughput |
| UI structure | Phase 13 responsive and Phase 14/16/17 fidelity tests; not a substitute for browser judgment |

## Live acceptance still needed

Use a controlled mailbox and redacted evidence. Establish recent-page usefulness while older mail indexes, then observe new mail and label/read changes arriving without waiting for backfill. Compare unique Inbox versus All Mail unread counts, message-size estimates and attachment presence with Gmail. Verify managed refresh persists and reconnect errors retain indexed data.

For cleanup, confirm a known exact selection, approve it, compare the provider outcome and Review ledger, then undo an eligible action within its window. Check protection/conflicts and unsubscribe acceptance versus confirmation independently. Verify logout retains data and confirmed deletion removes every owned account's local data.

An authenticated browser pass should cover the six rail pages, paging/filter state, fragment updates, desktop/narrow layouts and keyboard focus. Existing MockMvc/template assertions do not establish absence of overflow in a live browser. Keep live evidence free of real addresses, subject text, provider IDs and OAuth tokens.

Historical results and the outstanding provider gate are summarized in [Status](STATUS.md). No fresh test run was performed for this documentation-only change.
