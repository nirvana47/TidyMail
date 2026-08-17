---
phase: 13-analytics-export-and-integrated-product-verification
plan: "01"
subsystem: analytics
tags: [java-21, spring-mvc, jdbc, utc, csv, privacy]

requires:
  - phase: 12-review-dual-execution-history-undo
    provides: owned account scope, workspace shell, durable action/privacy contracts
provides:
  - Strict six-key AnalyticsQuery and exact AnalyticsSourceQuery contracts
  - Repository-owned Received predicate shared by aggregate, page, and stream
  - Real provider-free analytics, source, and temporary analytics CSV routes
  - Typed availability, prior comparison, UTC buckets, and source equality evidence
affects: [13-02, 13-03, 13-04, analytics, exports]

actuals:
  tokens: 22909
  tasks: 3
  commits: 0

tech-stack:
  added: []
  patterns:
    - strict typed canonical query before owned-account resolution
    - repository-owned half-open fact predicate reused across all projections
    - conservative coverage end enforced for dashboard, source, and CSV
    - one bounded grouped bucket query for the full accepted date range
    - allowlisted streaming CSV with RFC 4180 and formula defense

key-files:
  created:
    - src/main/java/com/tidymail/dto/AnalyticsQuery.java
    - src/main/java/com/tidymail/repository/AnalyticsReceivedRepository.java
    - src/main/java/com/tidymail/controller/AnalyticsController.java
    - src/test/java/com/tidymail/Phase13AnalyticsRepositoryTest.java
  modified:
    - src/main/java/com/tidymail/controller/WorkspaceController.java
    - src/main/java/com/tidymail/dto/AnalyticsAvailability.java
    - src/main/java/com/tidymail/dto/AnalyticsSourcePage.java
    - src/main/resources/templates/analytics.html
    - src/main/resources/static/css/workspace.css

key-decisions:
  - "Received remains action-independent and is always derived from owned message_metadata.received_at facts."
  - "Current metric availability remains separate from weaker prior-comparison availability."
  - "The least-recent successful sync is a hard coverage end: overlap is partial and wholly newer ranges are unavailable."
  - "Unavailable source CSV is rejected before attachment creation; unavailable or incomplete zero is never presented as established."
  - "Trend buckets are counted in one bounded account/time query while source page and stream retain the canonical fact predicate."
  - "The temporary /analytics.csv route supports bounded dashboard rows and exact full-source streaming without exposing account or provider IDs."

patterns-established:
  - "Canonical state: from, to, account, action, group, compare in stable order."
  - "Source equality: aggregate count, count endpoint, stable page union, and CSV stream share one ReceivedFactPredicate."

requirements-completed:
  - WORK-05
  - ANLY-01
  - ANLY-02
  - ANLY-03
  - ANLY-04
  - ANLY-08
  - EXPT-01
  - EXPT-02

coverage:
  - id: D1
    description: Strict canonical UTC analytics and exact source query contracts
    requirement: ANLY-02
    verification:
      - kind: unit
        ref: "Phase13AnalyticsQueryTest (10 tests)"
        status: pass
    human_judgment: false
  - id: D2
    description: Owned Received aggregate, stable pages, and stream use one half-open predicate
    requirement: ANLY-04
    verification:
      - kind: integration
        ref: "Phase13AnalyticsRepositoryTest (4 tests)"
        status: pass
    human_judgment: false
  - id: D3
    description: Provider-free analytics, source, and temporary CSV routes with privacy headers and canaries
    requirement: EXPT-02
    verification:
      - kind: integration
        ref: "Phase13AnalyticsWebTest (9 tests)"
        status: pass
    human_judgment: false
  - id: D4
    description: Real semantic analytics controls and exact source navigation replace the placeholder
    requirement: WORK-05
    verification:
      - kind: automated_ui
        ref: "Phase13AnalyticsWebTest#realAnalyticsPageHasCanonicalControlsUtcCoverageAndExactSourceLinks"
        status: pass
    human_judgment: false

duration: 35min
completed: 2026-08-10
status: complete
---

# Phase 13 Plan 01: Received Analytics Tracer Summary

**Strict UTC analytics now runs end to end from owned metadata facts through exact source pages and filter-identical CSV.**

## Performance

- **Duration:** 35 min
- **Started:** 2026-08-10T06:49:00Z
- **Completed:** 2026-08-10T07:24:00Z
- **Tasks:** 3
- **Files modified:** 25

## Accomplishments

- Established strict canonical UTC queries, equal-duration prior ranges, clipped day/week/month buckets, and bounded availability.
- Reused one authorized account/time Received predicate for aggregate counts, 25-row pages, deterministic streams, and CSV.
- Replaced the analytics placeholder with authenticated provider-free HTML/source/CSV routes and privacy-canary coverage.
- Enforced conservative coverage end across current-day, post-sync, future, mixed-account, source, comparison, and CSV paths.
- Replaced up to 366 sequential bucket counts with one bounded grouped query and added maximum-range query-shape coverage.
- Applied base 44px targets to Analytics clear, Back, and pagination links.

## Task Commits

No commits were created, as requested.

## Files Created/Modified

- `src/main/java/com/tidymail/dto/AnalyticsQuery.java` - Canonical time/filter state and bucket generation.
- `src/main/java/com/tidymail/repository/AnalyticsReceivedRepository.java` - Received fact count/page/stream authority.
- `src/main/java/com/tidymail/service/AnalyticsService.java` - Coverage-end availability, comparison, trend, source, and CSV orchestration.
- `src/main/java/com/tidymail/dto/AnalyticsSourcePage.java` - Source rows plus originating bucket availability.
- `src/main/java/com/tidymail/controller/AnalyticsController.java` - Real analytics, source, and temporary CSV endpoints.
- `src/main/resources/templates/analytics.html` - Canonical GET UI and evidence surface.
- `src/test/java/com/tidymail/Phase13AnalyticsQueryTest.java` - Pure UTC/query/arithmetic/CSV coverage.
- `src/test/java/com/tidymail/Phase13AnalyticsRepositoryTest.java` - Predicate and aggregate/source equality coverage.
- `src/test/java/com/tidymail/Phase13AnalyticsWebTest.java` - Auth, fail-closed, HTML/CSV privacy, and provider-free coverage.

## Decisions Made

- Kept provider links optional and absent when no existing safe builder output can be produced without projecting provider IDs.
- Kept current Received availability independent from comparison availability so an unavailable prior interval never hides a valid current count.
- Left shared requirement status pending because Plans 13-02 through 13-06 also declare these requirements.

## Deviations from Plan

### Auto-fixed Issues

**1. Testing environment: explicit Java 21 path**
- `/usr/libexec/java_home -v 21` resolved to Java 26 on this host.
- Verification used `/opt/homebrew/opt/openjdk@21` (OpenJDK 21.0.12) explicitly.

**2. Superseded shell assertion**
- Phase 9 expected the former analytics capability placeholder.
- The assertion now verifies real UTC filters, export, privacy copy, and absence of disabled controls.

**3. Independent review warnings WR-01 through WR-04**
- Added hard coverage-end handling and explicit post-sync reasons.
- Propagated source availability and rejected unavailable source exports.
- Replaced per-bucket counts with one bounded grouped query for up to 366 dates.
- Raised base Analytics navigation targets to at least 44px and added static assertions.

## Issues Encountered

- H2 rounds nanosecond boundary fixtures; the exclusive-end test uses a stable millisecond boundary.
- Incremental test compilation retained a stale class after a package mismatch; the package was aligned and a clean focused run passed.

## User Setup Required

None - no external service configuration required.

## Verification

- Focused Phase 13: 23 tests passed, 0 failed, 0 errors, 0 skipped.
- Relevant Phase 12 execution/Review/undo/privacy regressions: 40 tests passed,
  0 failed, 0 errors, 0 skipped.
- Runtime: OpenJDK 21.0.12.
- `git diff --check`: passed.

## Next Phase Readiness

- Plan 13-02 can add Phase 12 ledger-derived metrics and the cleaned series on the established query/availability vocabulary.
- Plan 13-03 must transfer the single `/analytics.csv` mapping to `ExportController` rather than registering a second handler.
- Real browser/Gmail localhost UAT and final responsive judgment remain deferred to the integrated Phase 13 verification plan.

---
*Phase: 13-analytics-export-and-integrated-product-verification*
*Completed: 2026-08-10*
