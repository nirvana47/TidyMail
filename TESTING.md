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
mvn test
mvn -Dtest='DashboardMigrationTest,DashboardJourneyTest,LocalGmailLabelsJourneyTest,PersonalOutlookJourneyTest,GmailBackfillRecoveryJourneyTest' test
```

For an alternate local service port, export `TIDYMAIL_TEST_DB_PORT` (default
`5433`). The harness fixes the loopback host, database names, and role, pins
Spring and Flyway to the test connections, and checks server identity, role
privileges, and denial of access to the live database before running.
The PostgreSQL permissions also deny the test role a connection to `tidymail`.
The test process does not load the app's `.env`.

Run one suite at a time against the same test databases. The Spring harness
truncates fixture data between tests; standalone migration tests clean their
scratch database to test fresh installs and upgrades at historical versions.
Keep both databases disposable. Maven reports are in `target/surefire-reports/`.
See the [acceptance notes](docs/TESTING.md) for product journeys and live checks.

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
