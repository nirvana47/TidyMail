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
