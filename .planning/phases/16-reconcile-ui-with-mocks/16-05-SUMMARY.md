---
phase: 16
plan: "05"
name: Contextual cleanup actions and Bulk Unsubscribe remediation
subsystem: workspace-ui
status: complete
completed: 2026-08-12
duration: continued fallback execution
requirements:
  - UX-10
  - UX-11
  - UX-12
  - UX-13
  - UX-14
  - UX-15
tags:
  - thymeleaf
  - htmx
  - cleanup-actions
  - bulk-unsubscribe
requires:
  - .planning/phases/16-reconcile-ui-with-mocks/16-05-PLAN.md
provides:
  - contextual sender selection action bars
  - authoritative cleanup sender column contract
  - mock-aligned Bulk Unsubscribe sender list
affects:
  - Bulk Archive/Delete
  - Unread Only
  - Bulk Unsubscribe
key-files:
  created:
    - .planning/phases/16-reconcile-ui-with-mocks/16-05-SUMMARY.md
  modified:
    - src/main/resources/templates/senders.html
    - src/main/resources/templates/unread.html
    - src/main/resources/templates/unsubscribe.html
    - src/main/resources/templates/fragments/senders-results-frag.html
    - src/main/resources/templates/fragments/unread-results-frag.html
    - src/main/resources/templates/fragments/unsubscribe-results-frag.html
    - src/main/resources/static/js/cleanup-categories.js
    - src/main/resources/static/css/workspace.css
    - src/main/java/com/tidymail/service/DecisionClassifier.java
    - src/main/java/com/tidymail/service/SenderDecisionQueryService.java
    - src/test/java/com/tidymail/BulkUnsubscribeContractAlignmentTest.java
    - src/test/java/com/tidymail/Phase10SenderUnreadWebTest.java
    - src/test/java/com/tidymail/Phase14InteractionFidelityTest.java
    - src/test/java/com/tidymail/Phase15WorkspaceWebTest.java
    - src/test/java/com/tidymail/Phase16WorkspaceFidelityTest.java
    - src/test/java/com/tidymail/Phase9WorkspaceShellTest.java
commits:
  - 9fe337d feat(16-05): add contextual cleanup actions and unsubscribe rows
---

# Phase 16 Plan 05: Contextual Cleanup Actions And Bulk Unsubscribe Remediation Summary

## Accomplishments

- Replaced Bulk Archive/Delete and Unread Only Settings/default-action workflows with hidden-by-default contextual selection bars. The bars show singular/plural selected sender counts, clear only enabled selected rows, and expose explicit Change type, Mark as Read, Archive, and Delete actions after selection.
- Kept exact-ID Review staging intact: selected row keys still post to `/api/cleanup-actions` with CSRF, canonical query inputs, request tokens, and delete acknowledgement for Gmail Trash recovery.
- Enforced the authoritative cleanup row contract on Bulk Archive/Delete and Unread Only: `Sender`, `Domain`, page count (`Emails` or `Unread`), `Size (Estimated)`, `Action`. Individual size cells render only formatted values or approved pending/unavailable states, without an `Estimated` prefix.
- Updated cleanup grid CSS for vertical centering, independent truncation, overflow-safe domain/count/size/action columns, stacked responsive labels, and no page-level horizontal overflow hooks.
- Converted Bulk Unsubscribe away from the workspace-state panel, metric strip, generic table, visible Settings/defaultAction selector, and pill-style controls. The page now uses compact Category/date/search/Apply controls, a mock-aligned individual sender list, explicit `Archive all` and `Delete all` alternatives in each row menu, and a neutral disabled `Use menu` primary state when no executable unsubscribe action exists.
- Preserved the dirty manual-override classifier/query remediation so account-local category corrections remain compatible with sender-row rendering.

## Commits

- `9fe337d` `feat(16-05): add contextual cleanup actions and unsubscribe rows`

The resumed worktree already contained interleaved task 16-05 changes across shared templates, CSS, JavaScript, services, and focused tests. To avoid reverting or losing prior-agent work, this fallback executor committed the production/test outcome as one combined 16-05 commit rather than splitting previously interwoven hunks into per-task commits.

## Verification

- Passed source gate: `rg -n 'Size \(Estimated\)|cleanupSenderColumnsFollowAuthoritativeOrderAndSizeContract|unreadSenderColumnsFollowAuthoritativeOrderAndSizeContract' src/test/java/com/tidymail/Phase16WorkspaceFidelityTest.java src/test/java/com/tidymail/Phase15WorkspaceWebTest.java && ! rg -n 'doesNotContain.*Size \(Estimated\)|rejectsSizeEstimatedHeading' src/test/java/com/tidymail/Phase16WorkspaceFidelityTest.java src/test/java/com/tidymail/Phase15WorkspaceWebTest.java`
- Passed source gate: no stale sender/unread Settings dialogs, default-action copy, persistent cleanup status panel, Bulk Unsubscribe visible Settings/defaultAction selector, metric strip, unsubscribe table, combined `Archive/Delete` label, premature unsubscribe-success copy, or `Estimated size` cell prefix in the 16-05 templates.
- Passed source gate: no `border-radius: 999px`, `border-radius: 9999px`, or `.unsubscribe-settings` matches in `workspace.css`, `unsubscribe.html`, or `unsubscribe-results-frag.html`.
- Passed: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -Dtest='Phase16WorkspaceFidelityTest,Phase15WorkspaceWebTest,Phase15StagingTest,Phase15UnreadSemanticsTest' test`
  - Result: 30 tests run, 0 failures, 0 errors.
- Passed: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -Dtest='Phase16WorkspaceFidelityTest,Phase15WorkspaceWebTest,Phase15CategoryPagingTest,Phase15ClassificationTest,Phase15UnreadSemanticsTest' test`
  - Result: 36 tests run, 0 failures, 0 errors.
- Partial environment-blocked gate: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -Dtest='BulkUnsubscribeContractAlignmentTest,Phase14InteractionFidelityTest,Phase15UnsubscribePerformanceTest,Phase16WorkspaceFidelityTest' test`
  - `BulkUnsubscribeContractAlignmentTest`, `Phase14InteractionFidelityTest`, and `Phase16WorkspaceFidelityTest` passed before the failure.
  - `Phase15UnsubscribePerformanceTest` errored during Testcontainers startup because no valid Docker environment was available: missing `/var/run/docker.sock` and no Docker Desktop socket path.

## Deviations From Plan

- The production/test work was committed as one combined outcome commit because this fallback resumed an already-dirty worktree with interleaved 16-05 changes. No unrelated files were reverted or reset.
- Docker-backed performance verification could not complete in the current environment. The failure occurred before test logic because Testcontainers could not find Docker.

## Safety Notes

- Exact provider-message-ID materialization, immutable Review scope creation/reuse, CSRF, account ownership, protected sender exclusion, and no provider mutation before Review approval were preserved and covered by focused tests.
- Delete actions continue to route through the Gmail Trash recovery acknowledgement before staging.
- No export, CSV, snapshot, or download affordances were introduced in this plan.

## Issues Encountered

- `.planning/phases/16-reconcile-ui-with-mocks/16-UAT.md` and `.planning/phases/16-reconcile-ui-with-mocks/16-VERIFICATION.md` were already dirty before closeout and remain outside the 16-05 production/test commit. They were not reverted.
- Full Docker/Testcontainers-backed verification remains pending until Docker is available locally.
- Authenticated browser walkthrough remains a later Phase 16 UX-15 verification gate; this plan did not start 16-06 or later work.

## Next Phase Readiness

Plan 16-05 implementation is complete from code/source-test coverage. Continue with the next orchestrated plan only after accepting the Docker/Testcontainers environment limitation or rerunning the blocked performance test with Docker available.

## Self-Check: PASSED
