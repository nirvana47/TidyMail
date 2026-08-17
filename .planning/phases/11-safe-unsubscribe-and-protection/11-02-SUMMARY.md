---
phase: 11-safe-unsubscribe-and-protection
plan: "02"
subsystem: unsubscribe-candidate-review-ui
tags: [spring-mvc, thymeleaf, h2, postgres, canonical-query, accessibility]
requires:
  - phase: 11-safe-unsubscribe-and-protection
    plan: "01"
    provides: frozen V8 schema, bounded parsers, candidate tracer, and redacted inspection
  - phase: 11-safe-unsubscribe-and-protection
    plan: "04"
    provides: provider-neutral transport boundary kept disconnected from browse and inspection
provides:
  - complete strict canonical unsubscribe query across every Phase 11 browse dimension
  - bounded deterministic candidate classification, filtering, ranking, metrics, and paging
  - privacy-safe full and fragment candidate/inspection UI
  - explicit validated mailto and Gmail source-message navigation with no server-side I/O
affects: [11-03, 11-04-integration, 11-05, phase-12]
actuals:
  tokens: 41698
  tasks: 4
  commits: 0
tech-stack:
  added: []
  patterns:
    - classify metadata in bounded batches before filtering and retain only the requested sorted page
    - expose manual destinations through candidate-bound local redirects so secrets stay out of inspection HTML
    - derive exact metrics and outcome filters from the same normalized candidate predicate
key-files:
  created:
    - .planning/phases/11-safe-unsubscribe-and-protection/11-02-SUMMARY.md
  modified:
    - src/main/java/com/tidymail/dto/UnsubscribeQuery.java
    - src/main/java/com/tidymail/repository/UnsubscribeCandidateRepository.java
    - src/main/java/com/tidymail/service/UnsubscribeCandidateService.java
    - src/main/java/com/tidymail/controller/WorkspaceController.java
    - src/main/resources/templates/fragments/unsubscribe-results-frag.html
    - src/main/resources/templates/fragments/unsubscribe-inspection-frag.html
    - src/test/java/com/tidymail/Phase11CandidateQueryTest.java
    - src/test/java/com/tidymail/Phase11UnsubscribeWebTest.java
key-decisions:
  - "Manual mailto and Gmail alternatives use explicit local GET redirects; inspection HTML contains no URI query values or provider message/thread IDs."
  - "Protected candidates retain all inspection facts but manual links are absent and automated controls remain natively disabled."
  - "Recent destination disagreement is computed once by grouped source signatures, avoiding correlated quadratic scans."
requirements-completed: [UNSB-01, UNSB-02, UNSB-03, UNSB-06]
coverage:
  - id: D1
    description: "Every candidate URL dimension is typed, canonical, bounded, duplicate-safe, and round-trippable."
    requirement: UNSB-02
    verification:
      - kind: unit
        ref: "Phase11CandidateQueryTest#canonicalQueryRoundTripsEveryTypedDimensionAndFailsClosed"
        status: pass
    human_judgment: false
  - id: D2
    description: "Candidates are classified before filtering and ranked into stable fixed pages with exact metrics and outcomes."
    requirement: UNSB-01
    verification:
      - kind: integration
        ref: "Phase11CandidateQueryTest"
        status: pass
      - kind: integration
        ref: "Phase11PostgresMigrationTest"
        status: pass
    human_judgment: false
  - id: D3
    description: "Full, fragment, and inspection UI render real privacy-safe facts and accessible responsive controls."
    requirement: UNSB-03
    verification:
      - kind: automated_ui
        ref: "Phase11UnsubscribeWebTest"
        status: pass
    human_judgment: false
  - id: D4
    description: "Mailto and Gmail source-message alternatives require explicit navigation and create no provider, transport, or attempt side effect."
    requirement: UNSB-06
    verification:
      - kind: integration
        ref: "Phase11UnsubscribeWebTest#explicitMailtoAndProviderNavigationRedirectOnlyAfterUserChoice"
        status: pass
    human_judgment: false
duration: 76 min
completed: 2026-08-10
status: complete
---

# Phase 11 Plan 02: Candidate Review And Manual Alternatives Summary

**Deterministic metadata-only unsubscribe filtering, ranking, inspection, and explicit
manual navigation with protected and privacy boundaries**

## Performance

- **Duration:** 76 min
- **Completed:** 2026-08-10T01:56:08Z
- **Tasks:** 4
- **Commits:** 0, as requested

## Accomplishments

- Added all method, volume, confidence, review, age, protection, outcome, search, sort,
  account, and page dimensions to one ordered fail-closed canonical query.
- Added bounded 200-row classification batches, exact filtered metrics/counts, all
  stable sorts, fixed 25-row pages, recent-conflict review, and latest-outcome joins.
- Replaced the tracer with complete real filter controls, denominator-qualified
  metrics, nine-column result table, empty/stale states, and detailed method cards.
- Added explicit mailto and Gmail source-message redirects that run only after a user
  click and make no provider, unsubscribe transport, attempt, event, or policy change.

## Verification

- Focused Phase 11 H2, parser, MockMvc, security, and PostgreSQL suites passed.
- PostgreSQL 16.14 Testcontainers ran with the active Colima socket and zero skips.
- Full Maven regression: **160 tests, 0 failures, 0 errors, 0 skipped**.
- Phase 9 10,000-row warm-shell budget passed after the query optimization.
- `git diff --check` passed.
- Frozen V8 SHA-256 remained:
  - production: `190c0d9cf10ca687bafc0604325aaa47380ae91593b1dbc2d669e9ba27256461`
  - H2: `fd9fd85cdb5c3abae103ec8b82f30e96f67ff332934b9d26666a51ea4839724d`

## Deviations From Plan

### Auto-fixed Issues

1. The first recent-conflict implementation used a correlated metadata scan and made
   the inherited 10,000-row shell benchmark take about 35 seconds. It was replaced by
   one grouped recent-signature CTE; the under-one-second benchmark passes again.
2. Manual links initially risked placing provider IDs or mailto query values in
   inspection HTML. Candidate-bound local redirects now reveal the validated target
   only in the explicit navigation response.
3. Plan 01 PostgreSQL expectations assumed only the preferred tracer method was
   visible. They now assert Plan 02's separate ineligible and manual alternatives.

## Phase Boundaries

- No outbound unsubscribe transport is invoked.
- No execution endpoint, attempt, outcome event, nonce, or manual-completion mutation
  is created.
- No Phase 12 selection, Review routing, staged/immediate policy, or action scope is
  implemented.
- Automated POST/legacy GET controls remain disabled pending Plans 11-03/11-04.

## User Setup Required

None.

## Next Phase Readiness

Plan 11-03 can bind signed explicit intents and append-only outcomes to the stable
candidate fingerprint and current inspection facts. The early Plan 11-04 transport
remains isolated until that lifecycle exists.

---
*Phase: 11-safe-unsubscribe-and-protection*
*Plan: 02*
