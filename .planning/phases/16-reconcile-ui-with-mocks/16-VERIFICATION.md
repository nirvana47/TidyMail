---
phase: 16-reconcile-ui-with-mocks
status: complete
verified: 2026-08-12
requirements_verified: 7
requirements_pending: 0
requirements_total: 7
closure:
  - Docker/Testcontainers full Maven suite passed during Phase 17 verification.
  - Authenticated UI was accepted by the user on 2026-08-13.
---

# Phase 16 Verification

**Scope:** Phase 16 plan 16-03 verification/documentation closure for UX-09 through UX-15.

**Evidence date:** 2026-08-12

## Command Evidence

| Gate | Command | Exit | Result |
| --- | --- | ---: | --- |
| Focused Phase 16 and inherited Phase 15/14/10 suites | `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -Dtest='Phase16WorkspaceFidelityTest,Phase14WorkspaceFidelityTest,Phase15WorkspaceWebTest,Phase10SenderUnreadWebTest,Phase15UnreadSemanticsTest,Phase15StagingTest,Phase15CategoryPagingTest,Phase15ClassificationTest' test` | 0 | Passed, 54 tests |
| Verification-support regression for Phase 13 integrated sender row label | `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -Dtest='Phase13IntegratedProductTest#expt02_htmlAndCsv_shareParserOwnershipPredicateAndOrder' test` | 0 | Passed, 1 test |
| Focused suite after verification-support fix | `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -Dtest='Phase16WorkspaceFidelityTest,Phase14WorkspaceFidelityTest,Phase15WorkspaceWebTest,Phase10SenderUnreadWebTest,Phase15UnreadSemanticsTest,Phase15StagingTest,Phase15CategoryPagingTest,Phase15ClassificationTest' test` | 0 | Passed, 54 tests |
| Focused suite after code-review remediation | `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -Dtest='Phase16WorkspaceFidelityTest,Phase15WorkspaceWebTest,Phase10SenderUnreadWebTest,Phase15ClassificationTest,Phase15StagingTest' test` | 0 | Passed, 40 tests |
| Focused suite after UAT row/category remediation | `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -Dtest='Phase16WorkspaceFidelityTest,Phase15WorkspaceWebTest,Phase10SenderUnreadWebTest,Phase15ClassificationTest,Phase15StagingTest' test` | 0 | Passed, 41 tests |
| Focused suite after Bulk Unsubscribe UAT remediation | `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -Dtest='Phase16WorkspaceFidelityTest,BulkUnsubscribeContractAlignmentTest,Phase9WorkspaceShellTest,Phase15WorkspaceWebTest,Phase14InteractionFidelityTest' test` | 0 | Passed, 28 tests |
| 16-08 required focused suite | `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -Dtest='Phase16WorkspaceFidelityTest,Phase9WorkspaceShellTest,Phase13WorkspaceResponsiveTest,Phase15WorkspaceWebTest,Phase15UnreadSemanticsTest,Phase15StagingTest,Phase15CategoryPagingTest,Phase15ClassificationTest,BulkUnsubscribeContractAlignmentTest,Phase14InteractionFidelityTest,Phase13IntegratedProductTest,Phase13AnalyticsRepositoryTest,Phase12ReviewWebTest' test` | 0 | Passed, 105 tests, 0 failures, 0 errors, 0 skipped. |
| Docker daemon check | `docker info` | 0 | Docker reachable via context `colima`; client 29.5.3, server 29.2.1, Linux aarch64. |
| Docker/Testcontainers full Maven suite | `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn test` | 1 | 397 tests run, 0 failures, 8 errors, 0 skipped. All errors were Testcontainers environment startup failures in PostgreSQL/performance classes. |
| Non-Docker regression suite | `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -Dtest='!*PostgresMigrationTest,!Phase13PerformanceTest,!Phase15UnsubscribePerformanceTest' test` | 0 | Passed, 389 tests, 0 failures, 0 errors, 0 skipped. |

## Tooling Notes

- The plan specified `./mvnw`, but this checkout has no executable Maven wrapper. Verification used direct `mvn` with the required Java 21 `JAVA_HOME` and `PATH`, matching the established Phase 16 summaries.
- The full suite could not reach exit 0. Docker itself is reachable via Colima, but Testcontainers cannot resolve a valid Docker environment in this shell. The failing classes were:
  - `Phase9PostgresMigrationTest`
  - `Phase10PostgresMigrationTest`
  - `Phase11PostgresMigrationTest`
  - `Phase12PostgresMigrationTest`
  - `Phase13PostgresMigrationTest`
  - `Phase13PerformanceTest`
  - `Phase15PostgresMigrationTest`
  - `Phase15UnsubscribePerformanceTest`
- Surefire reports show `IllegalStateException: Could not find a valid Docker environment` / `Previous attempts to find a Docker environment failed` for those classes.
- One stale non-Docker assertion in `Phase13IntegratedProductTest` expected the pre-Phase-16 sender row label `Message count`; it was updated to assert the current `data-label="Emails"` label. This was a verification-support fix only.
- The post-execution code review found two warning-level issues. Both were fixed before handoff: `Move selected` now submits selected `senderAddress` values to category management, and `unread.html` loads HTMX for `hx-*` cleanup controls. `16-REVIEW.md` records the fixed findings.
- The first authenticated UAT walkthrough reported a blocker in cleanup row density, column spacing, category modal behavior, size copy, and a Whitelabel recovery path after category correction. Remediation moved row category controls into the action column, consolidated Change type and Move selected into one HTMX category modal, made successful saves close/reload, centered Load more, tinted category sections, and clarified size pending state as sync/data freshness rather than a new Google permission.
- Follow-up authenticated UAT reported Bulk Unsubscribe still had a large partial-indexed workspace-state panel and generic table/action styling that did not match the mock. Remediation removed the workspace-state panel and metric strip from Bulk Unsubscribe, replaced the table with the mock-aligned individual sender row list, and gave unsubscribe actions/menu controls page-specific styling.
- The current full-suite blocker is not an application assertion failure. `docker info` succeeds, but Testcontainers reports `Could not find a valid Docker environment` / `Previous attempts to find a Docker environment failed` from `DockerClientProviderStrategy` for the PostgreSQL migration and performance classes listed below.
- A 16-08 source scan found no visible export/CSV/snapshot/download affordance in the primary authenticated workspace templates. It did find residual CSS selectors for `.workspace-download` / `.analytics-download`, and `workspace-state` inclusions on secondary detail pages `analytics-source.html` and `unsubscribe-stats.html`; those were recorded as verification findings rather than changed in this verification-only plan.

## Requirement Evidence

| Requirement | Status | Evidence |
| --- | --- | --- |
| UX-09 text-only left rail labels with no visible Unread/Review badges | automated_pass | `Phase16WorkspaceFidelityTest`, `Phase14WorkspaceFidelityTest`; source scan of `workspace-rail.html` shows exact labels and no visible `.rail-nav-badge` output. |
| UX-10 Unread Only concise copy and stale note removal | automated_pass | `Phase16WorkspaceFidelityTest`, `Phase10SenderUnreadWebTest`, `Phase15UnreadSemanticsTest`; `/unread` helper is `Tidy up your unread emails`; stale explanatory notes are absent from `unread-results-frag.html`. |
| UX-11 no persistent cleanup status panel; row/button-level staging remains | automated_pass | `Phase16WorkspaceFidelityTest`, `Phase15WorkspaceWebTest`, `Phase15StagingTest`; cleanup pages omit `cleanup-action-status`, use `data-cleanup-feedback`, and keep row-level `Staged` / `Already staged` reconciliation. |
| UX-12 compact mock-aligned cleanup categories with split metrics | automated_pass | `Phase16WorkspaceFidelityTest`, `Phase15WorkspaceWebTest`, `Phase10SenderUnreadWebTest`, `Phase15CategoryPagingTest`; fragments expose `.category-title`, `.category-meta`, sender count, size/unread metrics, bounded lazy category loading, and collapse reset coverage. |
| UX-13 mock-aligned compact sender rows and controls | automated_pass | `Phase16WorkspaceFidelityTest`, `Phase15WorkspaceWebTest`, `Phase15StagingTest`; sender/unread rows expose table-like labels, protected sender states, hidden exact row keys, and no Selection Preview staging route. |
| UX-14 category-management affordances and user-generated tags | automated_pass | `Phase16WorkspaceFidelityTest`, `Phase15WorkspaceWebTest`, `Phase10SenderUnreadWebTest`, `Phase15ClassificationTest`; rendered controls include `Move selected`, `Create category`, `Change type`, and visible `User generated` for custom categories. |
| UX-15 authenticated page-by-page app-versus-mock walkthrough | accepted | Phase 17 supplied subsequent authenticated walkthrough and remediation evidence; the user accepted the current UI on 2026-08-13. |

## Safety Regression Evidence

| Constraint | Evidence |
| --- | --- |
| No export-like affordances on authenticated workspace pages | Focused tests and source scans cover no visible `Export`, `CSV`, `snapshot`, or `download` affordances on the primary workspace templates. Historical backend CSV/snapshot code remains undiscoverable UI, per Phase 15/16 contract. Residual `.workspace-download` / `.analytics-download` selectors exist only in CSS in the current scan. |
| Inbox-only unread semantics | `Phase15UnreadSemanticsTest` passed in focused runs; `DecisionSurfaceSql` still requires unread plus provider `INBOX` label for Unread Only. |
| Exact-ID staging, immutable scope hash/count, duplicate reuse, no provider mutation before Review | `Phase15StagingTest` passed in focused runs; cleanup forms retain hidden row keys, CSRF, request tokens, and `/api/cleanup-actions` posting. |
| Delete acknowledgement and provider Trash warning | Focused tests passed; sender/unread delete dialogs still render `Messages moved to Gmail Trash can typically be restored for up to 30 days.` and require acknowledgement. |
| Protected senders | Focused tests passed; sender/unread row templates keep protected labels and disabled staging controls. |
| Bulk Unsubscribe safety/state/performance expectations | Source/read evidence preserves the individual sender workflow, explicit action states, destination-safe behavior inherited from Phase 11/12, and no grouped cleanup categories. Docker-backed performance rerun remains blocked by local Docker availability. |

## Human Needed

Authenticated browser walkthrough is still required before UX-15 can be closed:

1. Start local dependencies and app without a public tunnel.
2. Authenticate at `http://localhost:8080/login` with an owned account.
3. Walk Bulk Archive/Delete, Unread Only, Bulk Unsubscribe, Review, Analytics, and the shared rail at desktop, 375x812, and 200% zoom.
4. Record actual observed behavior in `16-WALKTHROUGH.md`.
5. Confirm no blocker or must-fix deltas remain open before final Phase 16 closure.

## Result

Phase 16 is complete. Its previously blocked Docker/Testcontainers suite passed
during Phase 17 verification with 423 tests and no failures or errors.
Subsequent authenticated walkthroughs closed UX-15, and the user accepted the
current UI on 2026-08-13. Functionality and data-quality follow-ups are deferred
to a new phase.
