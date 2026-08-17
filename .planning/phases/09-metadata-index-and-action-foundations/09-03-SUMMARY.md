---
phase: 09-metadata-index-and-action-foundations
plan: "03"
subsystem: workspace-shell
tags: [spring-mvc, thymeleaf, htmx, accessibility, responsive-css, provider-isolation]
requires:
  - phase: 09-01
    provides: metadata index, sync progress, owned account scope, and manual sync endpoint
  - phase: 09-02
    provides: immutable action scopes and queued-action foundation
provides:
  - typed five-route workspace model and deterministic aggregate state vocabulary
  - database-only account, queue, metadata, and progress model builder
  - single MVC owner for five workspace pages and one state fragment
  - shared responsive Thymeleaf navigation, rail, state, and capability contracts
  - comprehensive provider-isolation, authorization, state, accessibility, and budget tests
affects: [phase-10, phase-11, phase-12, phase-13]
tech-stack:
  added: []
  patterns: [typed-route-model, database-only-page-rendering, truthful-disabled-capabilities]
key-files:
  created:
    - src/main/java/com/tidymail/service/WorkspaceModelService.java
    - src/main/java/com/tidymail/controller/WorkspaceController.java
    - src/main/resources/templates/fragments/workspace-state.html
    - src/main/resources/static/css/workspace.css
    - src/test/java/com/tidymail/Phase9WorkspaceShellTest.java
  modified:
    - src/main/java/com/tidymail/controller/LoginController.java
    - src/main/java/com/tidymail/controller/ReviewActionsController.java
    - src/test/java/com/tidymail/Phase7BulkActionsTest.java
    - src/test/java/com/tidymail/Phase8DeploymentTest.java
key-decisions:
  - Workspace GETs resolve owned Gmail scope once and aggregate only persisted metadata, progress, and queued-action rows.
  - Active polling is driven independently by syncActive so usable PARTIAL data still polls while terminal PARTIAL data does not.
  - Existing review POST mappings remain in ReviewActionsController while WorkspaceController exclusively owns GET /review.
requirements-completed: [DATA-07, WORK-01, WORK-03, WORK-04, QUAL-04]
completed: 2026-08-09
status: complete
---

# Phase 9 Plan 3: Workspace Shell Summary

The five workspace routes now share one typed, authorized, database-only model and a truthful responsive shell.

## Accomplishments

- Added immutable route, state, account option, and page-model contracts with exact one/all-account aggregation, queued counts, safe errors, freshness, and never-started/completed-empty distinctions.
- Established `WorkspaceController` as the sole GET owner for Senders, Unread, Unsubscribe, Review, and Analytics, plus a single-root state fragment; `/dashboard` now redirects to `/senders`.
- Added shared navigation, account rail, six-state fragment, real CSRF metadata-sync controls, disabled later-phase controls, and one local responsive stylesheet.
- Built five complete route templates with real navigation, privacy copy, account preservation, truthful capability copy, and no fabricated mailbox rows or metrics.
- Added full-context tests for authorization, route ownership, every state branch, mixed-account precedence, polling, provider isolation, static accessibility/responsive contracts, and 10,000-row performance budgets.

## Verification

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q -Dtest=Phase7BulkActionsTest,Phase8DeploymentTest,Phase9WorkspaceShellTest test`: passed.
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test`: 96 tests, 0 failures, 0 errors, 1 skipped.
- `git diff --check`: passed.
- No commit was created.

## Residual Risks

- PostgreSQL 16 Testcontainers coverage remains skipped because Docker/Colima is unavailable; H2 migration, rendering, and query coverage passed.
- Browser-only checks at 375px, 200% zoom, and keyboard navigation are represented by static contracts but were not manually exercised in a real browser.
- This host's Java 21 selector resolves to installed Java 26.0.1, consistent with Plans 09-01 and 09-02.

## Next Phase Readiness

Phases 10-13 can replace the disabled capability panels with indexed sender/unread surfaces, unsubscribe workflows, review execution, and analytics without changing route ownership or shared state contracts.
