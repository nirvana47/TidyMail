---
status: passed
phase: 13-analytics-export-and-integrated-product-verification
plan: "01"
depth: deep
files_reviewed: 22
finding_counts:
  critical: 0
  warning: 0
  info: 0
  total: 0
resolved_finding_counts:
  warning: 4
  total: 4
---

# Plan 13-01 Independent Deep Review

## Findings

### WR-01: Ranges beyond the latest successful sync are reported as available - RESOLVED

`coverage()` records `workspace.lastSuccessfulAt()` as the conservative coverage end
(`src/main/java/com/tidymail/service/AnalyticsService.java:147-164`), but
`availability()` only checks the range against the coverage start and workspace
state; it never compares `range.endExclusive()` with `coverage.end()`
(`src/main/java/com/tidymail/service/AnalyticsService.java:167-190`). Consequently, a
READY account queried for dates after its last successful sync, including an entirely
future range, receives `AVAILABLE` metrics and explicit zero buckets. The same false
availability reaches dashboard CSV through each metric/bucket availability field
(`src/main/java/com/tidymail/controller/AnalyticsController.java:140-171`).

This violates the fail-closed coverage contract: zero may be emitted only when source
completeness is established. Enforce the conservative end boundary per selected
account scope, making a wholly post-coverage range unavailable and an overlapping
range partial. Add current-day, post-sync, future-range, and mixed-account boundary
tests.

**Disposition:** `AnalyticsService.availability()` now enforces the conservative
coverage end, including explicit no-completed-sync and post-sync reasons. Overlapping
current/post-sync ranges are partial, wholly post-sync ranges are unavailable, and
the least-recent successful end remains authoritative for mixed-account scope.
Unavailable/partial zero values and unavailable comparison values are blanked or
labeled rather than emitted as established zeroes in HTML and dashboard CSV.

### WR-02: Source navigation converts unavailable history into a claimed zero - RESOLVED

Every trend bucket receives a source link even when its availability is UNAVAILABLE
(`src/main/java/com/tidymail/service/AnalyticsService.java:53-61`). The source path
then discards availability: `AnalyticsSourcePage` has no availability field
(`src/main/java/com/tidymail/dto/AnalyticsSourcePage.java:7-14`), and `source()` only
loads the count and rows (`src/main/java/com/tidymail/service/AnalyticsService.java:83-106`).
For an unavailable pre-index bucket, the source template therefore says both that
zero rows match and that "The originating aggregate is zero"
(`src/main/resources/templates/fragments/analytics-source-frag.html:7-10`), although
the originating trend cell correctly displayed `Unavailable`
(`src/main/resources/templates/fragments/analytics-results-frag.html:56-61`).

This breaks the required distinction between known zero and missing history and makes
the HTML/source interpretation inconsistent despite predicate equality. Carry the
bucket availability into the source model and render unavailable/partial copy rather
than claiming zero; source CSV should preserve the same state or reject unavailable
source export explicitly.

**Disposition:** `AnalyticsSourcePage` now carries the exact bucket availability.
Unavailable source pages do not execute/count source facts, do not display zero, and
do not offer export. Unavailable source CSV requests fail before attachment creation;
partial empty source pages state that no complete zero is claimed. Available source
count/page/stream projections continue to share the canonical received predicate.

### WR-03: Bucket generation causes up to 366 sequential count queries per request - RESOLVED

`dashboard()` executes one count for the current interval, one for the prior interval,
and another `COUNT(DISTINCT m.id)` inside the loop for every generated bucket
(`src/main/java/com/tidymail/service/AnalyticsService.java:37-61`;
`src/main/java/com/tidymail/repository/AnalyticsReceivedRepository.java:87-91`).
Because the accepted range permits 366 day buckets, one request can issue at least
368 fact-count queries plus coverage queries. Dashboard CSV repeats the same work
before streaming (`src/main/java/com/tidymail/controller/AnalyticsController.java:110-120`).

This query amplification is not bounded independently of grouping and is unlikely to
meet the Phase 13 indexed-query budget on the representative dataset. Fetch bucket
counts in one account/time-bounded grouped query, or one bounded fact pass, while
retaining the shared `ReceivedFactPredicate`. Add a query-count assertion for the
366-day case.

**Disposition:** Bucket counts now use one bounded, account/time-constrained grouped
query built from the shared `ReceivedFactPredicate`. It accepts at most 366 contiguous
buckets and returns zero-filled ordered results without per-bucket round trips.
Repository equality and web-level one-call assertions execute the full 366-day shape.

### WR-04: New Analytics links do not meet the 44px target contract at desktop widths - RESOLVED

The Analytics filter clear link and source-page Back link use `.clear-link`, whose
default minimum height is 34px
(`src/main/resources/static/css/workspace.css:365-369`;
`src/main/resources/templates/analytics.html:68-70`;
`src/main/resources/templates/fragments/analytics-source-frag.html:46`). Pagination
links similarly have padding but no 44px minimum
(`src/main/resources/static/css/workspace.css:508-518`;
`src/main/resources/templates/fragments/analytics-source-frag.html:41-45`). They are
raised to 44px only inside the max-720px media query
(`src/main/resources/static/css/workspace.css:703-713`).

The Phase 13 contract requires every control and link to provide at least a 44 by 44
CSS pixel target, not only mobile controls. Apply the minimum target size at the base
Analytics styles and add static assertions covering the clear, Back, and pagination
links.

**Disposition:** Base `.clear-link` and `.pagination a` styles now provide a 44px
minimum height at all viewport widths; pagination retains a 72px minimum width.
Static assertions cover Analytics clear, source Back, Previous, and Next links.

## Verification

- Java 21 Phase 13 focused suites: 23 tests, 0 failures, 0 errors, 0 skipped.
- Java 21 Phase 12 execution/Review/undo/privacy regressions: 40 tests, 0 failures,
  0 errors, 0 skipped.
- `git diff --check`: passed.
- Canonical source equality, provider-free GETs, privacy canaries, and V1-V9 remained
  covered by the passing focused/regression suites.
- Production code, tests, and review artifacts were updated; no commit was created.
