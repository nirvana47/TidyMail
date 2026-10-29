# Testing and acceptance

**MVP scope update — 2026-10-28:** Test TidyMail on desktop. Do not run mobile,
narrow-screen or responsive-mobile checks unless the user explicitly requests
them later. Earlier mobile references below are historical; desktop keyboard and
accessibility checks remain required.

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

The [dashboard MVP plan](MVP-PLAN.md#preserve-first-implementation-and-testing)
adds acceptance requirements for retained-data upgrades, scope/grouping,
500+ target background jobs, throttling, partial failures, refresh/restart
recovery, and live progress. Prefer preserving the existing connected account
and downloaded metadata; use isolated fixtures for destructive tests. A necessary
fresh local-index start is permitted after a recoverable backup and a recorded
reason, and is separate from deleting Gmail messages. The current-surface checks
below describe the existing app; replace removed-surface UI checks as the MVP is
implemented rather than treating six-page navigation or unsubscribe as new MVP
requirements.

Use a controlled mailbox and redacted evidence. Establish recent-page usefulness while older mail indexes, then observe new mail and label/read changes arriving without waiting for backfill. Compare unique Inbox versus All Mail unread counts, message-size estimates and attachment presence with Gmail. Verify managed refresh persists and reconnect errors retain indexed data.

For cleanup, confirm a known exact selection, approve it, compare the provider outcome and Review ledger, then undo an eligible action within its window. Check protection/conflicts and unsubscribe acceptance versus confirmation independently. Verify logout retains data and confirmed deletion removes every owned account's local data.

An authenticated browser pass should cover the six rail pages, paging/filter state, fragment updates, desktop/narrow layouts and keyboard focus. Existing MockMvc/template assertions do not establish absence of overflow in a live browser. Keep live evidence free of real addresses, subject text, provider IDs and OAuth tokens.

Historical results and the outstanding provider gate are summarized in [Status](STATUS.md). No fresh test run was performed for this documentation-only change.

## Gmail throttling and backfill recovery

`GmailBackfillRecoveryJourneyTest` uses the real Gmail SDK against a local HTTP
server and the PostgreSQL journey harness. It reproduces a successful 200-message
bootstrap followed by a rate-limited page, then checks scheduled recovery, page
continuation, retries of only failed metadata IDs, permanent-error handling,
metadata-batch spacing and lane lease renewal. Google responses are simulated;
this is separate from live Gmail acceptance.

Sync reads retry rate-limit responses (`403 rateLimitExceeded` /
`userRateLimitExceeded`, `429`) and temporary server errors at most five times,
with exponential delay, jitter and `Retry-After`. Successful metadata stays in
memory while failed IDs are retried. The page cursor advances only after the
complete page commits. If retries are exhausted, the next scheduled sync also
queues unfinished backfill. Active work renews its lease while waiting.

## Dashboard Phase 2 baseline

Run the isolated synthetic benchmark with the Docker environment above:

```sh
mvn -o -Dtest=DashboardBaselinePerformanceTest test
```

It migrates a disposable PostgreSQL database through the production migrations,
seeds 10,000 then 50,000 messages across 500 addresses, and measures current sender
summary/page, search/date, later paging and a message-expansion SQL prototype.
Each operation has one warmup and ten timed observations, idle and with a separate
writer committing 200-row metadata updates. It asserts result counts and surfaces
writer errors; timings are evidence, not hardcoded machine-dependent passes.
No real OAuth account or provider is used. The prototype does not establish the
future dashboard's received/grouping semantics or browser rendering performance.
Results, targets and the full-suite baseline are recorded in the
[Phase 2 report](PHASE-2-BASELINE-AND-PERSISTENCE.md).

## Implemented dashboard — Phase 3

```sh
mvn -o -Dtest='DashboardJourneyTest,DashboardIdentityTest,DashboardMigrationTest,DashboardPerformanceTest' test
```

These cover the shared received/date/search contract, identity/grouping corrections,
exact preview snapshots, cross-owner/CSRF boundaries, fresh and 10k-row retained
upgrades, ten-row paging, and real dashboard query/response budgets with concurrent
metadata writes. `SyntheticMailboxGenerator` assigns deterministic opaque IDs only
to synthetic fixtures; production IDs remain random UUIDs.

For manual browser acceptance, run `mvn -o -Dtest=DashboardBrowserFixture test`.
It prints a temporary loopback URL, serves the actual Thymeleaf/MockMvc routes
with a synthetic authenticated account/session and mocked providers, and stops
after 15 minutes or when `/tmp/tidymail-phase3-browser-done` exists. Startup removes
an earlier completion marker. This explicitly selected harness is excluded from
the normal `*Test` suite. It does not log in to or mutate a real mailbox.
See the [Phase 3 report](PHASE-3-INDEXED-DASHBOARD.md) for evidence and limitations.

## Dashboard background Mark as Read — Phase 4

```sh
mvn -o -Dtest='DashboardJobJourneyTest,DashboardGmailDispatchTest,DashboardJourneyTest,DashboardMigrationTest,Phase15PostgresMigrationTest,LogoutSemanticsTest' test
```

Use Java 21 and the Docker environment above. Coverage includes 501 fixed targets,
50-target batches, duplicate confirmations, overlap/ownership/CSRF boundaries,
queued and in-flight cancellation, read failures, ambiguous sends, lease loss,
stale-worker fencing, revision-aware reconciliation and deletion barriers. The
Gmail adapter test uses its actual SDK against loopback HTTP for success/401/503
and verifies one exact mutation attempt without automatic replay.

`DashboardBrowserFixture` now also provides simulated label reads/mutations and
a timer driving the real worker tick. It supports desktop confirmation, progress,
reload, cancellation, history and reconciliation checks. See the
[Phase 4 report](PHASE-4-BACKGROUND-MARK-READ.md) for evidence and limitations.

## Dashboard actions and resilience — Phase 5

```sh
mvn -o -Dtest='DashboardResilienceTest,DashboardJobJourneyTest,DashboardGmailDispatchTest,DashboardJourneyTest,DashboardMigrationTest,Phase15PostgresMigrationTest,LogoutSemanticsTest,GmailSynchronizationContractTest,GmailBackfillRecoveryJourneyTest' test
```

Use Java 21 and the Docker environment above. Each resilience scenario uses 501
fixed targets for Mark as Read, Archive and Trash: multi-batch success, partial
ambiguous outcomes, read throttling/Retry-After, reconnect, read exhaustion,
worker replacement and cancellation races. Additional checks exercise post-dispatch
read failures without mutation replay, exact Gmail SDK requests, background/freshness
capacity sharing and V17 in-flight upgrade preservation. The explicit browser
fixture supports all three simulated actions. See the
[Phase 5 report](PHASE-5-ACTIONS-AND-RESILIENCE.md) for recorded results and limits.
