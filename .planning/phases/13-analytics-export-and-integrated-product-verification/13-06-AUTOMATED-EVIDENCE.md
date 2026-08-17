# Phase 13 Plan 06 Automated Evidence

Recorded at 2026-08-10T16:33:00Z. This file covers automated and non-browser work
and is complemented by the redacted real Gmail/browser record in `13-UAT.md`.

## Integrated Product Evidence

`Phase13IntegratedProductTest` adds the required named positive/fail-closed matrix:

- `work05_allRepresentedControlsExecute_andDecorativePatternsAreAbsent`
- `anly01_factTimeMetrics_includeProvenFacts_andExcludeMutableOrPendingFacts`
- `anly02_groupAndFilters_roundTrip_andRejectInvalidBroadening`
- `anly03_priorInterval_isEqualDuration_andSuppressesInvalidPercent`
- `anly04_eachTrendBucket_equalsCountPagesAndSourceCsv`
- `anly05_topSender_opensExactOwnedRange_andRejectsOpaqueTokenAbuse`
- `anly06_outcomesAndCoverage_equalExactIntegerPartitions`
- `anly07_efficiencyAndDelay_useLockedDenominatorsAndBoundaries`
- `anly08_zeroPartialAndUnavailable_remainDistinct`
- `expt01_allFiveCurrentFilteredCsvs_havePositiveDownloadEvidence`
- `expt02_htmlAndCsv_shareParserOwnershipPredicateAndOrder`
- `expt03_snapshotKeepsAggregates_andRejectsEveryPrivateCanary`
- `qual02_allFivePagesMeetResponsiveAccessibleControlContract`

The suite uses deterministic H2 facts and provider/transport mocks. It performs no
real mailbox mutation.

`Phase13IntegratedCycleTest` additionally drives one connected mocked-provider cycle:
immediate and staged exact actions, approval, durable History, Analytics, all five
CSV routes, sanitized snapshot, and applied-target-only undo.

`Phase13PostgresMigrationTest` now proves every cleaned bucket aggregate equals its
complete one-row-paginated source union on PostgreSQL and H2.

## Commands And Results

Environment used for Maven runs:

```bash
export JAVA_HOME="${JAVA_HOME_21:-$(brew --prefix openjdk@21)}"
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock"
```

Focused Phase 13:

```bash
mvn -q \
  -Dtest=Phase13AnalyticsQueryTest,Phase13MetricDefinitionTest,Phase13AnalyticsRepositoryTest,Phase13AnalyticsWebTest,Phase13ExportTest,Phase13SnapshotPrivacyTest,Phase13PostgresMigrationTest,Phase13PerformanceTest,Phase13WorkspaceResponsiveTest,Phase13IntegratedProductTest \
  test
```

Result: 80 tests, 0 failures, 0 errors, 0 skipped.

Full regression:

```bash
mvn -q test
```

Result: 322 tests, 0 failures, 0 errors, 0 skipped.

Clean package:

```bash
mvn -q clean package
git diff --check
```

Result: PASS. Final full-suite audit was 322/0/0/0.

## PostgreSQL And Package

- Java: OpenJDK 21.0.12
- Maven: 3.9.16
- Docker server: 29.2.1
- PostgreSQL: 16.14
- Production Flyway: V1-V10; local schema upgraded from V6 to V10 during smoke
- JAR: `target/tidymail-0.1.0.jar`
- Bytes: 154978935
- SHA-256: `77b057a8a4ee69e9a8a0e5ac0982b3db4962a7a40dd5c003a354e709cce29373`
- Launcher: `org.springframework.boot.loader.launch.JarLauncher`
- Start class: `com.tidymail.InboxCleanerApplication`
- Application version: 0.1.0
- Spring Boot version: 3.5.16

Verified JAR entries include the application class, build metadata, all five workspace
templates, and `static/css/workspace.css`.

## Loopback Smoke

The exact package was started, checked, stopped, and the closed port was proven. It
was then restarted for browser UAT.

Exact start command, using existing `.env` through the application's dotenv loader:

```bash
"${JAVA_HOME_21:-$(brew --prefix openjdk@21)}/bin/java" \
  -Xms256m -Xmx1g \
  -jar target/tidymail-0.1.0.jar \
  --server.address=127.0.0.1 \
  --server.port=8080 \
  --spring.task.scheduling.enabled=false \
  --spring.flyway.enabled=true
```

No `.env` contents or secret values are printed by this command.

Smoke results:

- `GET http://127.0.0.1:8080/login`: 200
- unauthenticated `GET http://127.0.0.1:8080/analytics`: 302 to local `/login`
- listener: `127.0.0.1:8080` only
- no cloudflared process
- temporary PID terminated and port closure proven
- UAT PID: 27344
- UAT command ID: `job-44f2922f22f94448b043d02f511099f5`

Required OAuth configuration remains:

- Origin: `http://localhost:8080`
- Redirect: `http://localhost:8080/login/oauth2/code/google`

## Privacy And Security

Automated checks passed for:

- provider-free analytics, source, export, snapshot, and five-page GET routes;
- foreign/tampered/duplicate query fail-closed behavior;
- exact account/filter ownership and source/export equality;
- recursive HTML/URL/CSV/snapshot/error/log privacy canaries;
- CSV formula safety and no partial output above cap;
- authentication and CSRF boundaries;
- inherited unsubscribe SSRF/DNS/redirect safety;
- immutable exact-scope execution and applied-target-only undo;
- no public application listener, tunnel, or deployment;
- `git diff --check`.

Local hardening note: existing Compose PostgreSQL publishes host port 5432 on all
interfaces. The application remains loopback-only; database binding should be
restricted separately without conflating that change with this verification plan.

## Remaining Gates

Gates B, D, E, and F passed. Gates A, C, and G are constrained by the exact mobile
viewport harness and real candidate/provider facts described in `13-UAT.md`.
Deployment remains BLOCKED and no tunnel or public deployment was attempted.
