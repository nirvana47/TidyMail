---
status: clean
phase: 16-reconcile-ui-with-mocks
review_date: 2026-08-12
review_depth: deep
scope: post-remediation review for plans 16-04 through 16-08
diff_range: 252a2bd..85a6182
files_reviewed:
  - src/main/java/com/tidymail/service/SenderDecisionQueryService.java
  - src/main/java/com/tidymail/service/DecisionClassifier.java
  - src/main/resources/static/css/workspace.css
  - src/main/resources/static/js/cleanup-categories.js
  - src/main/resources/templates/analytics.html
  - src/main/resources/templates/fragments/analytics-results-frag.html
  - src/main/resources/templates/fragments/review-history-frag.html
  - src/main/resources/templates/fragments/review-queue-frag.html
  - src/main/resources/templates/fragments/senders-results-frag.html
  - src/main/resources/templates/fragments/unread-results-frag.html
  - src/main/resources/templates/fragments/unsubscribe-results-frag.html
  - src/main/resources/templates/fragments/workspace-rail.html
  - src/main/resources/templates/review.html
  - src/main/resources/templates/senders.html
  - src/main/resources/templates/unread.html
  - src/main/resources/templates/unsubscribe.html
  - src/test/java/com/tidymail/BulkUnsubscribeContractAlignmentTest.java
  - src/test/java/com/tidymail/Phase10SenderUnreadWebTest.java
  - src/test/java/com/tidymail/Phase11UnsubscribeWebTest.java
  - src/test/java/com/tidymail/Phase12PrivacyTest.java
  - src/test/java/com/tidymail/Phase12ReviewWebTest.java
  - src/test/java/com/tidymail/Phase13AnalyticsWebTest.java
  - src/test/java/com/tidymail/Phase13IntegratedProductTest.java
  - src/test/java/com/tidymail/Phase13WorkspaceResponsiveTest.java
  - src/test/java/com/tidymail/Phase14InteractionFidelityTest.java
  - src/test/java/com/tidymail/Phase14WorkspaceFidelityTest.java
  - src/test/java/com/tidymail/Phase15WorkspaceWebTest.java
  - src/test/java/com/tidymail/Phase16WorkspaceFidelityTest.java
  - src/test/java/com/tidymail/Phase9WorkspaceShellTest.java
findings:
  critical: 0
  warning: 0
  info: 0
  total: 0
---

# Phase 16 Post-Remediation Code Review

## Current Review Result

No new bugs, regressions, security/safety issues, or executable UI contract violations were found in the scoped Phase 16 execution changes from plans 16-04 through 16-08 (`252a2bd..85a6182`).

Reviewed focus areas included shared rail/account controls, cleanup contextual staging, HTMX behavior, CSRF-bearing POST forms touched by the phase, exact-ID Review staging, destructive delete acknowledgement copy, protected sender disabling, no authenticated workspace export affordances, compact Review Queue/History hierarchy, Analytics visual contract changes, and the 16-08 stale assertion updates.

The previous two Phase 16 review findings remain preserved below as fixed prior context; neither reproduced in this post-remediation review.

## Verification Run

- Passed: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -Dtest='Phase16WorkspaceFidelityTest,Phase15StagingTest,Phase12ReviewWebTest' test`
- Result: 34 tests run, 0 failures, 0 errors, 0 skipped.

## Verified Clean Areas

- Left rail labels match page H1s and keep Unread/Review counts out of visible badge clutter while retaining accessible server-owned count facts.
- Bulk Archive/Delete and Unread Only contextual bars stay hidden at zero selection, post exact row keys through the staging form, keep CSRF tokens, preserve request tokens, and route Delete through the acknowledgement dialog before staging.
- Cleanup row rendering disables protected senders and reconciles `Staged` / `Already staged` state from the HTMX JSON response without executing mailbox mutations before Review.
- Category movement now copies selected sender addresses into the organize dialog payload; workspace pages remain single-account scoped, so the dialog does not cross account boundaries in the reviewed flow.
- Review Queue and History keep compact table hierarchy without the workspace-state panel while preserving exact counts, LIFO/order coverage, conflict checks, CSRF on queue actions, destructive warning coverage, and applied-ID-only undo tests.
- Analytics, Review, and cleanup templates in this scope did not reintroduce visible export, CSV, snapshot, download, or share affordances.
- `DecisionClassifier` / `SenderDecisionQueryService` manual override handling now consistently passes account-owned manual sender type into Java classification for list and category page rendering.

## Residual Risks

- Docker/Testcontainers-backed full `mvn test` remains blocked by Testcontainers Docker environment discovery, as recorded in `16-08-SUMMARY.md`.
- UX-15 authenticated browser UAT remains human-needed for desktop, 375x812, and 200% zoom walkthrough evidence.
- Prior broad codebase review finding C-5 in `16-CODEBASE-QUALITY-REVIEW.md` still records live backend export endpoints outside this 16-04..16-08 UI remediation scope. This post-remediation review found no new authenticated workspace export affordance in the scoped changed templates.

## Prior Fixed Findings Preserved

### Warning 1: `Move selected` category management submits no selected senders - FIXED

**Files:** `src/main/resources/templates/fragments/senders-results-frag.html`, `src/main/resources/templates/fragments/unread-results-frag.html`, `src/main/resources/templates/senders.html`, `src/main/resources/templates/unread.html`, `src/main/resources/static/js/cleanup-categories.js`

The Phase 16 category-management UI exposes `Move selected`, but the selected rows are never included in the organize form submission. The row checkboxes in the sender and unread fragments are wired to the staging forms via `form="sender-bulk-stage"` and `form="unread-bulk-stage"` (`senders-results-frag.html:78-82`, `unread-results-frag.html:71-77`). The category dialogs post to `/api/sender-categories/organize`, but their forms only include account/return/category fields and no `senderAddress` fields (`senders.html:146-163`, `unread.html:121-138`). The shared dialog code only copies `data-dialog-sender` for per-row correction buttons; the category buttons have no selected-row collection path (`cleanup-categories.js:33-47`).

`SenderCategoryController.organize` expects `senderAddress` values for assignment and otherwise treats a selected existing category with no senders as an error. As implemented, clicking `Move selected` after selecting senders cannot move those senders; it either creates an empty category when a new name is supplied or returns a bad request for an existing destination. This misses UX-14's functional category-management contract even though the visible copy is present.

**Recommended fix:** When opening the category dialog, copy selected row identities into the organize form as `senderAddress` hidden inputs, or change the controller to accept exact row keys and resolve them server-side. Add rendered/integration coverage that selects at least one sender, posts the same payload the dialog emits, and verifies the account-owned assignment is created without crossing account boundaries.

**Resolution:** `cleanup-categories.js` now populates the open category dialog with hidden `senderAddress` inputs from checked sender rows on the active page surface. Both `senders.html` and `unread.html` include a `data-selected-senders` target in the organize form, and Phase 16 fidelity tests assert the dialog plumbing.

### Warning 2: Unread cleanup page omits HTMX, so row-level staging and load-more controls degrade to redirects or no-ops - FIXED

**Files:** `src/main/resources/templates/unread.html`, `src/main/resources/templates/fragments/unread-results-frag.html`, `src/main/resources/static/js/cleanup-categories.js`, `src/test/java/com/tidymail/Phase16WorkspaceFidelityTest.java`

`unread.html` loads `cleanup-categories.js` but does not load HTMX (`unread.html:7-8`), while `senders.html` loads HTMX before the same cleanup script (`senders.html:7-9`). The Unread page still renders `hx-post` staging forms (`unread.html:32-35`, `unread-results-frag.html:109-112`) and `hx-get` load-more buttons (`unread-results-frag.html:143-152`). Without HTMX, form submissions are regular POSTs to `/api/cleanup-actions`; the controller only returns the row-level JSON for `HX-Request: true` and otherwise redirects back to `/unread`. That bypasses the Phase 16 row/button-level `Staged` / `Already staged` reconciliation in `cleanup-categories.js:63-83` and violates the no-redirect cleanup staging contract. The load-more buttons are `type="button"` with only `hx-get`, so after the script's initial fetch fallback inserts a chunk, subsequent Load more / Load all controls do nothing without HTMX.

The current tests assert that Unread markup contains `hx-*` and `data-cleanup-feedback`, but they do not assert that the page actually loads the HTMX runtime. That lets the Unread page pass source/rendered checks while its browser behavior diverges from Bulk Archive/Delete.

**Recommended fix:** Load HTMX on `unread.html` consistently with `senders.html`, or replace the remaining `hx-*` dependencies with explicit fetch handlers in `cleanup-categories.js`. Add a Phase 16 assertion that every template rendering `hx-post`/`hx-get` for cleanup staging also loads HTMX or has an equivalent local JS fallback for that interaction.

**Resolution:** `unread.html` now loads HTMX before the shared cleanup script, matching `senders.html`. Phase 16 fidelity tests assert the Unread template includes the HTMX runtime.

## Fix Verification

- Focused Phase 16 and inherited cleanup tests passed: `Phase16WorkspaceFidelityTest`, `Phase15WorkspaceWebTest`, `Phase10SenderUnreadWebTest`, `Phase15ClassificationTest`, and `Phase15StagingTest`.
- Result: 40 tests, 0 failures, 0 errors, 0 skipped.

## Verified Clean Areas

- The left rail renders exact text labels without visible Unread/Review numeric badges while preserving server-side count facts in accessible labels.
- Cleanup templates retain CSRF fields, exact row keys, protected-sender disabled controls, delete acknowledgement copy, and the Gmail Trash recovery warning.
- Unread-only backend semantics remain covered by the existing `INBOX` plus unread predicate tests.
- Account authorization for user-generated sender categories is scoped through owned account IDs; no cross-user category rows were visible in the reviewed rendered coverage.
- No reviewed workspace template reintroduced user-facing export, CSV, snapshot, download, or Selection Preview affordances.

## Residual Verification Gaps

The full Maven suite remains blocked by Docker/Testcontainers availability, and UX-15 still needs the authenticated localhost page-by-page walkthrough recorded in `16-WALKTHROUGH.md`. These are verification constraints from the phase artifacts, not source bugs found in this review.
