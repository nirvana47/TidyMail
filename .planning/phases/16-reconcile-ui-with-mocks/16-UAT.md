---
status: testing
phase: 16-reconcile-ui-with-mocks
source:
  - 16-VERIFICATION.md
started: 2026-08-12T07:20:00Z
updated: 2026-08-13T02:58:00Z
---

# Phase 16 UAT

## Current Test

number: 1
name: Authenticated localhost page-by-page walkthrough
expected: |
  User signs in locally, compares the authenticated workspace against
  `tidymail-ux-rehaul-draft/tidymail-ux-rehaul-draft.design` and
  `tidymail-ux-rehaul-draft/pages/build-notes.html`, records observations in
  `16-WALKTHROUGH.md`, and confirms UX-15 without open blocker deltas.
awaiting: user response

## Tests

### 1. Authenticated localhost page-by-page walkthrough

expected: Complete Bulk Archive/Delete, Unread Only, Bulk Unsubscribe, Review, Analytics, and shared rail walkthrough at desktop, 375x812, and 200% zoom without public tunnel or cloud deployment.
result: issue
reported: "Cleanup category rows are still too tall, narrow screens need padding between columns, domain/size text bleeds into the action button, Delete/Change type actions should sit bottom-right while Load more is centered, selected checkboxes plus Change type should open category management with existing/create-new options, sections need tinted shading matching border colors, size calculations remain pending without clear permission guidance, size should render plainly without estimated/pill styling, category labels in the modal do not match window labels, saving category/type changes leaves the modal open with ambiguous feedback and no visible row move, refresh after cancel led to a blank/Whitelabel Error Page and the user is stuck."
severity: blocker
follow_up_reported: "Bulk Unsubscribe still renders the large workspace-state partial indexed data panel, does not match the authored Bulk Unsubscribe mock, and its primary/fallback action buttons plus table/list treatment look wrong."

### 2. Docker/Testcontainers-backed full Maven suite

expected: Re-run `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn test` in an environment with Docker/Testcontainers available and record zero failures/errors or concrete remaining gaps.
result: blocked
reported: "`docker info` succeeds against Colima, but `mvn test` exits 1 with 397 tests run, 0 failures, 8 errors, 0 skipped. All errors are Testcontainers environment startup errors in PostgreSQL migration/performance classes: `Could not find a valid Docker environment` / `Previous attempts to find a Docker environment failed` from `DockerClientProviderStrategy`."
severity: blocker

## Summary

total: 2
passed: 0
issues: 1
pending: 0
skipped: 0
blocked: 1

## Gaps

- gap_id: G-16-1
  truth: "Complete Bulk Archive/Delete, Unread Only, Bulk Unsubscribe, Review, Analytics, and shared rail walkthrough at desktop, 375x812, and 200% zoom without public tunnel or cloud deployment."
  status: failed
  reason: "User reported: Cleanup category rows are still too tall, narrow screens need padding between columns, domain/size text bleeds into the action button, Delete/Change type actions should sit bottom-right while Load more is centered, selected checkboxes plus Change type should open category management with existing/create-new options, sections need tinted shading matching border colors, size calculations remain pending without clear permission guidance, size should render plainly without estimated/pill styling, category labels in the modal do not match window labels, saving category/type changes leaves the modal open with ambiguous feedback and no visible row move, refresh after cancel led to a blank/Whitelabel Error Page and the user is stuck."
  severity: blocker
  test: 1
  root_cause: "Phase 16 compact rows kept Change type inside the sender column, fixed grid columns allowed long domain/size text to collide with actions, category modals used inconsistent semantic labels and a status-fragment-only save response, and category organize posts still depended on full-page redirects. Size values are pending because local metadata rows have null size_estimate values; Gmail METADATA includes sizeEstimate under existing access, so this is sync/data freshness rather than a new Google Console permission."
  artifacts:
    - src/main/resources/templates/fragments/senders-results-frag.html
    - src/main/resources/templates/fragments/unread-results-frag.html
    - src/main/resources/templates/senders.html
    - src/main/resources/templates/unread.html
    - src/main/resources/static/js/cleanup-categories.js
    - src/main/resources/static/css/workspace.css
    - src/main/java/com/tidymail/controller/SenderCategoryController.java
    - src/main/java/com/tidymail/dto/SenderDecisionRow.java
    - src/test/java/com/tidymail/Phase15WorkspaceWebTest.java
    - src/test/java/com/tidymail/Phase16WorkspaceFidelityTest.java
    - src/test/java/com/tidymail/Phase10SenderUnreadWebTest.java
  missing:
    - Authenticated localhost browser retest of Test 1 after remediation

- gap_id: G-16-2
  truth: "Bulk Unsubscribe matches `unsubscribe-compact-suite.html`: quiet shell, rail-only account selector, compact controls, individual sender row list, mock-aligned buttons, and no persistent partial-indexed workspace-state panel."
  status: failed
  reason: "User reported the selected partial indexed data section should not exist on Bulk Unsubscribe, the page does not match the Bulk Unsubscribe mock, and the action buttons/table treatment look weird."
  severity: blocker
  test: 1
  root_cause: "Bulk Unsubscribe still included the shared workspace-state fragment and used a generic HTML table plus metric strip instead of the mock's compact controls and individual sender row-list pattern."
  artifacts:
    - src/main/resources/templates/unsubscribe.html
    - src/main/resources/templates/fragments/unsubscribe-results-frag.html
    - src/main/resources/static/css/workspace.css
    - src/test/java/com/tidymail/BulkUnsubscribeContractAlignmentTest.java
    - src/test/java/com/tidymail/Phase9WorkspaceShellTest.java
    - src/test/java/com/tidymail/Phase16WorkspaceFidelityTest.java
  missing:
    - Authenticated localhost browser retest of Bulk Unsubscribe after remediation

- gap_id: G-16-3
  truth: "Docker/Testcontainers-backed full Maven suite passes with PostgreSQL migration and performance classes enabled."
  status: blocked
  reason: "`docker info` reports Docker reachable through context `colima`, but Testcontainers fails before PostgreSQL containers start. Full `mvn test` exits 1 with 397 tests run, 0 failures, 8 errors, 0 skipped."
  severity: blocker
  test: 2
  root_cause: "Local Testcontainers Docker discovery cannot resolve a valid Docker environment from this shell despite a reachable Docker daemon."
  artifacts:
    - target/surefire-reports/com.tidymail.Phase9PostgresMigrationTest.txt
    - target/surefire-reports/com.tidymail.Phase10PostgresMigrationTest.txt
    - target/surefire-reports/com.tidymail.Phase11PostgresMigrationTest.txt
    - target/surefire-reports/com.tidymail.Phase12PostgresMigrationTest.txt
    - target/surefire-reports/com.tidymail.Phase13PostgresMigrationTest.txt
    - target/surefire-reports/com.tidymail.Phase13PerformanceTest.txt
    - target/surefire-reports/com.tidymail.Phase15PostgresMigrationTest.txt
    - target/surefire-reports/com.tidymail.Phase15UnsubscribePerformanceTest.txt
  missing:
    - Re-run full `mvn test` in an environment where Testcontainers can start PostgreSQL 16 containers.
