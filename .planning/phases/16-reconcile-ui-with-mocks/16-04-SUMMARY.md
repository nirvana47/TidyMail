---
phase: 16
plan: "04"
name: Shared shell, page-header, menu, and button remediation
subsystem: workspace-ui
status: complete
completed: 2026-08-13
duration: continued fallback execution
requirements:
  - UX-09
  - UX-10
  - UX-13
  - UX-15
tags:
  - thymeleaf
  - htmx
  - workspace-shell
  - css
requires:
  - .planning/phases/16-reconcile-ui-with-mocks/16-04-PLAN.md
provides:
  - top account disclosure menu
  - bottom Settings utility disclosure menu
  - shared page-header structure
  - compact shared workspace button primitives
affects:
  - authenticated workspace shell
  - Bulk Archive/Delete
  - Unread Only
  - Bulk Unsubscribe
  - Review
  - Analytics
tech-stack:
  added: []
  patterns:
    - Thymeleaf disclosure menus for rail-owned account and utility controls
    - Compact page-header with help affordance instead of persistent subtitles
    - Medium-radius workspace button styling
key-files:
  created:
    - .planning/phases/16-reconcile-ui-with-mocks/16-04-SUMMARY.md
  modified:
    - src/main/resources/templates/fragments/workspace-rail.html
    - src/main/resources/templates/senders.html
    - src/main/resources/templates/unread.html
    - src/main/resources/templates/unsubscribe.html
    - src/main/resources/templates/review.html
    - src/main/resources/templates/analytics.html
    - src/main/resources/static/css/workspace.css
    - src/test/java/com/tidymail/Phase10SenderUnreadWebTest.java
    - src/test/java/com/tidymail/Phase11UnsubscribeWebTest.java
    - src/test/java/com/tidymail/Phase12PrivacyTest.java
    - src/test/java/com/tidymail/Phase13AnalyticsWebTest.java
    - src/test/java/com/tidymail/Phase14WorkspaceFidelityTest.java
    - src/test/java/com/tidymail/Phase15WorkspaceWebTest.java
    - src/test/java/com/tidymail/Phase16WorkspaceFidelityTest.java
    - src/test/java/com/tidymail/Phase9WorkspaceShellTest.java
key-decisions:
  - Kept server-owned unread and review count facts while removing visible rail badge clutter.
  - Kept account switching rail-owned and route-preserving through existing authorized GET forms.
  - Kept Feedback, Logout, and delete-data logout under a separate Settings utility menu with existing POST/CSRF behavior.
coverage:
  - deliverable: Separate account and utility menus
    human_judgment: false
    verification:
      - kind: test
        ref: "src/test/java/com/tidymail/Phase9WorkspaceShellTest"
        status: pass
      - kind: test
        ref: "src/test/java/com/tidymail/Phase16WorkspaceFidelityTest"
        status: pass
  - deliverable: Shared page headers without persistent subtitles
    human_judgment: false
    verification:
      - kind: command
        ref: "rg -n 'route-heading|Open a category|Tidy up your unread emails|Handle one sender|Approve staged exact scopes|Approve selected or all eligible exact scopes' src/main/resources/templates/{senders,unread,unsubscribe,review,analytics}.html"
        status: pass
      - kind: test
        ref: "src/test/java/com/tidymail/Phase16WorkspaceFidelityTest"
        status: pass
  - deliverable: Shared shell and responsive contract tests
    human_judgment: false
    verification:
      - kind: test
        ref: "src/test/java/com/tidymail/Phase13WorkspaceResponsiveTest"
        status: pass
      - kind: test
        ref: "src/test/java/com/tidymail/Phase9WorkspaceShellTest"
        status: pass
  - deliverable: Authenticated visual adequacy at desktop, 375px, and 200 percent zoom
    human_judgment: true
    rationale: "Plan 16-04 shipped source and automated shell contracts. Full authenticated page-by-page browser walkthrough remains a Phase 16 UX-15 human gate handled by later verification."
---

# Phase 16 Plan 04: Shared Shell, Page Header, Menu, And Button Remediation Summary

## Accomplishments

- Built the separate rail account and Settings utility menus. The top account toggle renders the selected account label, route-scoped account switching remains server-owned, and Feedback/Logout/delete-data logout stay under the bottom Settings menu with existing CSRF forms.
- Standardized the five workspace pages on the shared `.page-header` pattern. Persistent route subtitles were removed from Bulk Archive/Delete, Unread Only, Bulk Unsubscribe, Review, and Analytics, with compact help affordances used where backend semantics still need explanation.
- Consolidated the shared workspace button/menu styling around medium radii, restrained hover/focus/disabled states, and responsive touch target behavior.
- Added and updated shell contract tests for exact route labels, account ownership, long HTML-special account labels, unavailable count states, one-main/one-H1 structure, no visible rail numeric badge clutter, CSRF preservation, and no export-like authenticated workspace UI.

## Commits

- `2c77f7a` `feat(16-04): split rail account and utility menus`
- `778e733` `feat(16-04): standardize workspace headers and buttons`
- `9d4e153` `test(16-04): lock shared shell contracts`

## Verification

- Passed: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -Dtest='Phase16WorkspaceFidelityTest,Phase9WorkspaceShellTest,Phase13WorkspaceResponsiveTest' test`
- Result: 23 tests run, 0 failures, 0 errors, 0 skipped.
- Passed scoped source scan: the five plan-owned workspace templates contain no `.route-heading` usage and none of the removed persistent subtitle strings.
- Passed scoped source scan: the five workspace templates and rail fragment contain no `Export`, `CSV`, `snapshot`, or `download` affordance.
- Passed scoped rail scan: `workspace-rail.html` contains no visible `.rail-nav-badge` output.

## Deviations From Plan

None - plan executed as written. Existing server-owned account authorization, CSRF handling, exact-ID Review staging, protected sender handling, no-export constraints, and destructive data-delete confirmation were preserved.

## Issues Encountered

- The current worktree still contains dirty changes outside this summary commit, including later Phase 16 remediation in Bulk Unsubscribe, classification services, CSS, tests, and Phase 16 UAT/verification documents. Those changes were not reverted or staged for this 16-04 closeout.
- A broad repository scan still finds legacy `.route-heading` CSS and non-plan helper templates such as `selection-preview.html`, `analytics-source.html`, and `unsubscribe-stats.html`. The 16-04 acceptance scope was the five authenticated workspace route templates, which passed the scoped scan.
- A broad CSS scan in the current dirty worktree finds `border-radius: 999px` on a Bulk Unsubscribe read-progress bar introduced outside the committed 16-04 task scope. This summary did not rewrite that unrelated dirty-state change.
- Full Docker/Testcontainers-backed `mvn test` was not run for this 16-04 closeout. Existing Phase 16 verification notes record full-suite Docker/Testcontainers environment failures separately.

## Next Phase Readiness

Ready for `16-05` or later orchestration. This executor did not start 16-05 work.

## Self-Check: PASSED
