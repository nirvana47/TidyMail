---
phase: 13-analytics-export-and-integrated-product-verification
plan: "04"
subsystem: analytics-ui
tags: [java-21, spring-mvc, thymeleaf, htmx, accessibility, aes-gcm, privacy]

requires:
  - phase: 13-analytics-export-and-integrated-product-verification
    provides: exact analytics facts, source predicates, five exports, and sanitized snapshot
  - phase: 12-review-dual-execution-history-undo
    provides: durable History, exact action outcomes, CSRF, and shared workspace shell
provides:
  - Complete analytics evidence UI with definitions, comparisons, trend, outcomes, efficiency, distribution, and coverage
  - Owner/account/sender/range-bound opaque analytics sender drilldowns shared by HTML, CSV, and preview actions
  - Five current-filter export controls plus sanitized snapshot control
  - Shared 375px, 44-by-44px, reduced-motion, wrapping, and contained-overflow contracts
  - Resolved independent review WR-01 through WR-07 with exact source equality and route parity
affects: [13-05, 13-06, analytics, exports, responsive-uat, privacy]

actuals:
  tokens: 21715
  tasks: 3
  commits: 0

tech-stack:
  added: []
  patterns:
    - short-lived AES-256-GCM locator tokens are authorization inputs, never authorization
    - opaque sender scope is resolved once and intersected in shared HTML/CSV/materialization SQL
    - semantic trend tables are authoritative; visual series remain optional and non-color-only

key-files:
  created:
    - src/main/java/com/tidymail/dto/AnalyticsSenderScope.java
    - src/main/java/com/tidymail/service/AnalyticsDrilldownKeyService.java
    - src/main/java/com/tidymail/service/AnalyticsLinkService.java
    - src/test/java/com/tidymail/AnalyticsDrilldownKeyServiceTest.java
    - src/test/java/com/tidymail/Phase13WorkspaceResponsiveTest.java
  modified:
    - src/main/resources/templates/fragments/analytics-results-frag.html
    - src/main/resources/templates/analytics.html
    - src/main/resources/static/css/workspace.css
    - src/main/java/com/tidymail/repository/DecisionSurfaceSql.java
    - src/test/java/com/tidymail/Phase13AnalyticsWebTest.java

key-decisions:
  - "Analytics sender locators use random-nonce AES-256-GCM with owner fingerprint, one account, normalized sender, exact UTC range, and 15-minute expiry."
  - "The resolved analytics scope travels through Senders HTML, CSV, and selection preview so existing controls remain functional without broadening."
  - "History is linked only as a visibly related durable report; exact received/cleaned metric predicates use analytics source routes."

patterns-established:
  - "Opaque locator: canonical ciphertext is validated before ownership and exact predicate resolution."
  - "Responsive evidence UI: every visual value has adjacent semantic text/table data."
  - "JavaScript-independent Analytics filters use a coherent full-page GET baseline."

requirements-completed:
  - WORK-05
  - ANLY-01
  - ANLY-02
  - ANLY-03
  - ANLY-04
  - ANLY-05
  - ANLY-06
  - ANLY-07
  - ANLY-08
  - EXPT-01
  - EXPT-02
  - EXPT-03
  - QUAL-02

coverage:
  - id: D1
    description: "Complete truthful analytics evidence surface and exact source navigation"
    requirement: ANLY-04
    verification:
      - kind: integration
        ref: "Phase13AnalyticsWebTest"
        status: pass
    human_judgment: false
  - id: D2
    description: "Opaque owner/account/sender/range drilldown with HTML, CSV, and preview parity"
    requirement: ANLY-05
    verification:
      - kind: integration
        ref: "Phase13AnalyticsWebTest#topSenderUsesOpaqueOwnerBoundScopeAndCsvReusesTheExactPredicate"
        status: pass
      - kind: unit
        ref: "AnalyticsDrilldownKeyServiceTest"
        status: pass
    human_judgment: false
  - id: D3
    description: "Five filter-faithful export controls and sanitized snapshot control"
    requirement: EXPT-01
    verification:
      - kind: integration
        ref: "Phase13ExportTest and Phase13SnapshotPrivacyTest"
        status: pass
    human_judgment: false
  - id: D4
    description: "375px, 44-by-44px, reduced-motion, semantic chart, and non-inert control contracts"
    requirement: QUAL-02
    verification:
      - kind: automated_ui
        ref: "Phase13WorkspaceResponsiveTest"
        status: pass
    human_judgment: true
    rationale: "Real 375x812 layout, 200% zoom, focus visibility, and screen-reader naming require browser judgment in Plan 13-06 UAT."

duration: 25min
completed: 2026-08-10
status: complete
---

# Phase 13 Plan 04: Analytics Evidence UI Summary

**Complete analytics evidence presentation, encrypted sender drilldowns, real five-page downloads, and shared narrow accessibility contracts**

## Performance

- **Duration:** 25 min
- **Started:** 2026-08-10T09:32:00Z
- **Completed:** 2026-08-10T09:57:00Z
- **Tasks:** 3
- **Files modified:** 27

## Accomplishments

- Replaced the thin analytics result surface with six truthful headline metrics,
  exact comparison/availability copy, received-versus-cleaned semantic trend data,
  top senders, outcome arithmetic, efficiency, delay distribution, and coverage.
- Added short-lived authenticated-encrypted sender locators and reused the resolved
  exact predicate across Senders HTML, CSV export, and action preview materialization.
- Added all five current-filter CSV controls, the sanitized snapshot control, a
  JavaScript-independent full-page filter baseline, and shared
  375px/44-by-44px/responsive accessibility CSS.
- Closed independent review WR-01 through WR-07 with metric-unit source predicates,
  prior/efficiency/delay inspection links, unsubscribe union/linkage parity,
  centralized stale-locator validation, and aggregate/source equality coverage.

## Task Commits

No commits were created, as requested.

## Decisions Made

- Base64 token encoding is required to be canonical in addition to passing GCM
  authentication, preventing alternate textual encodings of the same token bytes.
- Unknown, expired, tampered, owner-swapped, account-mismatched, or empty sender
  locators fail with one bounded non-enumerating 404.
- Existing Phase 10 preview actions retain the analytics key and exact UTC predicate;
  the drilldown is a complete queue, not a read-only decorative report.
- Outcome and coverage numerators/denominators use dedicated target/cohort/source
  series; unsupported predicates never fall back to a broader History report.

## Deviations from Plan

### Auto-fixed Issues

**1. Scoped sender actions initially lost the resolved analytics predicate**
- **Found during:** final behavioral audit
- **Issue:** HTML and CSV were exact, but selection preview reparsed the opaque key
  without resolving it and would reject or broaden existing queue actions.
- **Fix:** Carried `AnalyticsSenderScope` through `SelectionRequest`,
  `SelectionPreviewController`, transaction preflight, and shared sender SQL.
- **Verification:** scoped queue preview integration plus full Phase 10 regression.

**2. Non-canonical Base64 variants**
- **Found during:** full regression
- **Issue:** Changing unused Base64 tail bits could preserve decoded authenticated bytes.
- **Fix:** Re-encode decoded bytes and reject any non-canonical token text.
- **Verification:** `AnalyticsDrilldownKeyServiceTest`.

**3. Superseded Phase 10 no-export assertion**
- **Found during:** full regression
- **Issue:** A Phase 10 test prohibited `Export`, conflicting with required Phase 13 controls.
- **Fix:** Removed only that obsolete assertion; action-safety assertions remain.
- **Verification:** complete 292-test suite.

## Verification

- Focused web/export/snapshot/privacy/responsive/drilldown suites: passed.
- PostgreSQL 16.14 clean V1-V10 and V9-to-V10 migration evidence: passed.
- Focused review regression suite: **49 tests, 0 failures, 0 errors, 0 skipped**.
- Full OpenJDK 21.0.12 suite through Colima: **294 tests, 0 failures, 0 errors,
  0 skipped**.
- Provider-free GET assertions, privacy canaries, opaque URL checks, and HTML/CSV
  predicate parity: passed.
- `git diff --check` and inert-control/template scan: passed.

## Issues Encountered

None remaining in automated verification.

## User Setup Required

None.

## Next Phase Readiness

- Plan 13-05 can run representative PostgreSQL query-plan and performance gates.
- Real 375px/200%/keyboard/screen-reader judgment and localhost Gmail Gates A-G remain
  blocking Plan 13-06 work. No public deployment or tunnel was used.

---
*Phase: 13-analytics-export-and-integrated-product-verification*
*Completed: 2026-08-10*
