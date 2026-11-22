# Testing TidyMail

Use Java 21, Maven, and the native PostgreSQL 16 service from [local setup](DEPLOY.md).
The test role owns `tidymail_test` (Spring integration and journeys) and
`tidymail_test_migrations` (disposable historical migration fixtures). Both use the
real Flyway migrations. Journeys use mocked mail providers or simulated loopback
HTTP responses. Docker is not needed.

If you still have the generated `TIDYMAIL_TEST_DB_PASSWORD` from setup, save it in
a private, Git-ignored file so future shells can load it:

```sh
(umask 077; printf 'export TIDYMAIL_TEST_DB_PASSWORD=%s\n' "$TIDYMAIL_TEST_DB_PASSWORD" > .env.test.local)
```

From the repository root:

```sh
. ./.env.test.local
mvn test                         # functional, safety and migration checks
TIDYMAIL_PERF_ROWS=100000 mvn -Prelease-performance test
mvn -Dtest='DashboardMigrationTest,DashboardJourneyTest,LocalGmailLabelsJourneyTest,PersonalOutlookJourneyTest,GmailBackfillRecoveryJourneyTest' test
```

The `release-performance` profile runs only the existing `performance`-tagged
checks: dashboard queries and confirmed cleanup during concurrent writes at
10k/100k messages, sync commit work at the configured volume, the 115k populated
label cache. These three workloads run separately from `mvn test`. The normal
suite also uses production MVP mode. Set `TIDYMAIL_PERF_ROWS=100000` as above to
exercise 100k sync rows;
the dashboard profile defaults to 100k independently and runs in production MVP
mode. Fixture loading uses bounded batches with production triggers enabled.
Absolute timing budgets are local release targets, not provider throughput
promises. A failed budget needs investigation; do not raise it merely to get a passing run.

For an alternate local service port, export `TIDYMAIL_TEST_DB_PORT` (default
`5433`). The harness fixes the loopback host, database names, and role, pins
Spring and Flyway to the test connections, and checks server identity, role
privileges, and denial of access to the live database before running.
The PostgreSQL permissions also deny the test role a connection to `tidymail`.
The test process does not load the app's `.env`.

Run one suite at a time against the same test databases. The Spring harness
truncates fixture data between tests; standalone migration tests clean their
scratch database to test fresh installs and upgrades at historical versions.
Keep both databases disposable. Functional reports are in `target/surefire-reports/`;
performance reports are in `target/performance-reports/`.
Capture verbose Maven output to an ignored or temporary log; inspect the summary
and failed cases before loading full traces into an agent conversation.
See the [acceptance notes](docs/TESTING.md) for product journeys and live checks.

## What the suite protects

| Risk | Main existing coverage |
|---|---|
| Exact selected mail, confirmation, cancellation and recovery | `DashboardJourneyTest`, `DashboardJobJourneyTest`, `DashboardResilienceTest`, `DashboardResponseCleanupJourneyTest`, `ManualFilingJourneyTest` |
| Sync cursors, partial commits and worker races | `MailboxSyncPersistenceTest`, `MailboxFreshnessConcurrencyTest`, `MailboxFreshnessJourneyTest`, `GmailBackfillRecoveryJourneyTest` |
| Provider requests, tokens and rule changes | Gmail/Outlook contract and journey tests, `OAuthAuthorizedClientRefreshTest`, `AutomationRulesJourneyTest` |
| Data retention, privacy, paid authorization and upgrades | Security, classification/labeling journeys, immutable migration checks and historical upgrade fixtures |

The stricter audit removes suites for the disabled Senders, Unread, Review,
custom-category and analytics experiences, along with their retired query/schema
contracts. Their old tests no longer get a special legacy-mode startup. The
current dashboard migration suite still verifies retained credentials, mail,
corrections, rules, history and unresolved cleanup across upgrades; published
migration checksums remain guarded once.

Shared recovery paths use representative actions for scheduling, reconnect and
rejection states. Action-specific success and ambiguous label verification still
cover Mark as Read, Archive and Trash. Cancellation and stale-worker fencing have
one owner in the existing job journey instead of duplicate matrices. Each actual
Gmail mutation payload is still checked, with rejection/replay coverage retained
for both cleanup and filing entry points.

Metadata privacy, configuration bounds and missing-refresh-token checks are
consolidated into their existing contracts. The old experimental model protocol
suite is removed; the current recommendation protocol and shared text privacy
boundary remain covered. No new test framework or suite was introduced.

Before adding or retaining a case, identify its current call path, concrete user
failure and whether an existing check already catches that failure. A passing
count is not evidence of unique value. After deleting nested suites, clear stale
generated test classes before verification; leave production classes alone if an
app is using them. Browser density checks use visible rows and overflow, not an
absolute pane position that changes when a saved-selection banner appears.

Browser checks validate interaction behavior; the Java journeys validate exact
stored/provider outcomes. Neither simulation proves real Gmail/Outlook consent,
provider throughput or model accuracy. Historical private pilots/probes remain
explicit-only tools, outside both Maven commands; do not bulk-run them or loosen
their replay/schema guards. See `tools/verification/README.md`.

The frontend selection journey runs in Chromium with fixture responses only:
`node tools/verification/dashboard-selection.cjs` (requires Playwright and its
Chromium browser, with Playwright available on Node's module path). It checks
address/message checkbox synchronization, selection within one parent group,
exact Trash preview IDs, unread scope, and delayed-response handling. It does
not connect to the live mailbox or perform cleanup.

The desktop polish journey also checks filter chips, settings/tool navigation,
pane dismissal and unsaved-rule protection. Set `TIDYMAIL_UI_SCREENSHOTS` to an
existing output directory to capture its fixture mailbox and cleanup dialog.
`PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` may point to an installed Chromium binary.

For screenshots and browser checks against actual server-rendered templates,
start the explicit synthetic harness in one terminal (with Java 21 and the test
password loaded as above):

```sh
mvn -Dtest=DashboardBrowserFixture -Ddashboard.browser.minutes=10 test
```

Use the loopback URL printed after `DASHBOARD_BROWSER_FIXTURE` in another terminal:

```sh
node tools/verification/ui-polish.cjs http://127.0.0.1:PORT/dashboard docs/verification/ui-polish
```

The script requires the synthetic-fixture marker before any form submission. It
checks the rendered mailbox, filters, selection, exact review, rule draft/preview,
rule-removal cancellation/confirmation, Rules/editor, sync, sign-in and desktop
layout/zoom, and captures PNGs. It never enables a provider rule or executes
mailbox cleanup. Stop the fixture early with `touch /tmp/tidymail-phase3-browser-done`.

Activity dashboard verification uses the same isolated browser fixture:

```sh
node tools/verification/activity-dashboard.cjs http://127.0.0.1:PORT/dashboard
```

It checks standalone navigation, sidebar highlighting, summary cards, the chart,
sync navigation and absence of a modal or desktop overflow. The focused Java
journeys are `MailboxActivityJourneyTest` and
`DashboardJobJourneyTest#activityDashboardPagesAllJobsAndCountsOnlyConfirmedChanges`;
they cover template rendering, populated pagination and account isolation.

Rule and manual-filing desktop journeys use the same synthetic fixture:
`node tools/verification/rule-filing.cjs http://127.0.0.1:PORT/dashboard /tmp/tidymail-filing-screenshots`.
It checks multiple phrases, explicit duplicate destination selection, retained
choices after a permission error, deferred creation, manual filing review and
confirmation, Activity names, both selection menus, and desktop layout. It saves a
draft and confirms one synthetic filing job; it never enables a native rule.

`AutomationRulesJourneyTest` also follows the Outlook sender picker, review and
update HTTP requests with simulated provider rejection, response loss and failed
read-back. It checks recovery without another mailbox mutation, retry after a
confirmed rejection, and checking an old pending update that left the original
rule unchanged. `RuleDestinationProviderTest` checks that comparing Outlook rule
behavior preserves the provider snapshot and that sender updates omit unused
predicates while explicitly clearing a predicate when switching matching modes.
