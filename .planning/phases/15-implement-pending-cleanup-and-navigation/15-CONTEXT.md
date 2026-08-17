# Phase 15: Implement Pending Cleanup and Navigation - Context

**Gathered:** 2026-08-11
**Status:** Ready for planning

<domain>
## Phase Boundary

Bring the authenticated cleanup workspace into exact agreement with
`tidymail-ux-rehaul-draft/tidymail-ux-rehaul-draft.design` and its Build Notes.
This phase covers the shared left rail, Bulk Archive/Delete, Unread Only, and Bulk
Unsubscribe, including account switching, authorized rail counts, classification
correction, bounded category loading, cross-category selection, exact-ID Review
staging, Inbox-only unread semantics, unsubscribe-page performance, and removal of
all user-facing export affordances.

The phase does not add new cleanup surfaces, immediate mailbox execution, inline
undo, AI classification, provider rules, permanent deletion, or public deployment.

</domain>

<decisions>
## Implementation Decisions

### Authoritative Contract And Navigation
- **D-01:** The sole design source is
  `tidymail-ux-rehaul-draft/tidymail-ux-rehaul-draft.design`; its Build Notes are
  the implementation contract. Older mocks, HTML exports, and other Design Canvas
  projects are excluded.
- **D-02:** The rail brand links to Bulk Archive/Delete, the workspace homepage.
  Rail labels exactly match page titles: `Bulk Archive/Delete`, `Unread Only`,
  `Bulk Unsubscribe`, `Review`, and `Analytics`.
- **D-03:** Remove the standalone queued-actions rail row. Show the authorized queue
  count beside Review and the authorized Inbox-unread count beside Unread Only.
- **D-04:** Account selection remains rail-only. Show only the email address, truncate
  long addresses within the rail, remove `Switch account`, auto-submit existing
  account choices, and retain `Add new account`.
- **D-05:** Account switching keeps the user on the current workspace route while the
  server rebuilds route state for the newly authorized account. Incompatible query,
  selection, and category-pagination state must reset rather than leak across accounts.
- **D-06:** No workspace page may render CSV, snapshot, download, or export controls.
  Existing backend export code may remain only if it is not user-facing or discoverable
  from the workspace.

### Shared Cleanup Page Contract
- **D-07:** Bulk Archive/Delete and Unread Only use one quiet page contract: simple
  header, optional loading banner, categories closed by default, and exactly two
  right-aligned adjacent controls directly above categories: `Settings` and
  `Expand all` / `Collapse all`. Do not wrap these controls in a toolbar pill/card.
- **D-08:** Settings copy is `Choose the default action`; labels are `Archive`,
  `Delete`, and `Mark as Read`. The high-attention action color stays consistent as
  the label changes.
- **D-09:** Bulk Archive/Delete and Unread Only have no page-level account selector,
  search box, filter strip, summary strip, export control, or bottom next-step mock.
- **D-10:** Sender rows use one column order: sender identity, domain, message count,
  size, action. The heading is `Size (Estimated)` and row values contain only the
  formatted size.
- **D-11:** The Unread non-mutual-exclusivity note appears before the category list,
  not below it.

### Bounded Category Loading
- **D-12:** Opening a category performs bounded server-side/query-level work and
  returns the first five rows in stable volume-first order. Full category counts and
  optional total size remain exact header aggregates and are not action scopes.
- **D-13:** `Load more` fetches five additional rows. After the first increment,
  show both subtle centered `Load more` and `Load all` controls; `Load all` explicitly
  requests the complete remaining category.
- **D-14:** Pagination state is independent per category and account. Collapsing a
  category resets that category to its initial five-row state. The browser must not
  receive or hide unrequested rows.

### Classification And Category Learning
- **D-15:** Inferred sender type remains deterministic and explainable. Manual
  correction is an explicit account-owned fact with precedence over inferred rules
  for the same normalized sender on subsequent queries and sync/index updates.
- **D-16:** Manual feedback never becomes a cross-account or cross-user rule.
  Correct the deterministic classifier for the observed personal/automated/
  transactional/list false positives and retain regression fixtures for each case.
- **D-17:** Checkboxes and category-change controls appear in every Bulk
  Archive/Delete category, including inferred, user-created, and fallback categories,
  not only Other.
- **D-18:** Unread categories remain overlapping. `Lists and automated mail`,
  `Older than 30 days`, `Direct mail`, `Has attachments`, and `Other` are computed
  independently, while a sender-level manual type override corrects the inferred
  list/automated/direct classification wherever that type is used.
- **D-19:** Other supports moving selected senders to an existing category or creating
  a new account-owned category through the centered modal workflow. User-created
  categories retain stable identity and classification feedback across future syncs.

### Action Safety And Unread Semantics
- **D-20:** Individual and multi-sender actions materialize exact qualifying provider
  message IDs before creating Review items. Sender checkbox counts, category totals,
  and estimated sizes never become action scopes.
- **D-21:** Archive/Delete/Mark as Read always stage for Review in this phase. They do
  not execute immediately, open a Selection Preview page, or redirect away from the
  cleanup page. For Bulk Archive/Delete and Unread Only this formally supersedes the
  ACTN-02 separate pre-queue preview-page interaction: the direct click is staging
  intent, the server returns the exact materialized count/staged result inline, and
  Review preserves the immutable exact scope count/hash for inspection before execution.
  Other surfaces retain their applicable preview requirements.
- **D-22:** After staging succeeds, disable and visually mute the affected row action
  in place to prevent duplicate enqueueing. Inline undo remains out of scope.
- **D-23:** Deletion staging and confirmation must state the provider Trash recovery
  limit, including Gmail's typical 30-day window.
- **D-24:** Unread Only means messages that are both unread and members of the
  provider Inbox label/folder. Archived unread mail from All Mail is excluded.
  Regression tests must distinguish Inbox unread from archived unread.

### Bulk Unsubscribe
- **D-25:** Bulk Unsubscribe remains an individual-sender workflow, not a grouping
  workflow. Preserve its category, exact date-range, sender/domain search, and
  Archive-or-Delete default-action controls from the Build Notes.
- **D-26:** Align Bulk Unsubscribe typography, spacing, shell, row hierarchy, modal
  placement, and high-attention action styling with the authoritative design without
  weakening unsubscribe eligibility, destination safety, or honest outcome states.
- **D-27:** Treat slow Bulk Unsubscribe navigation as a backend/query-performance
  defect. Profile production-shaped data and fix the responsible aggregation,
  history, batching, or query path rather than masking latency with UI behavior.

### Folded Todos
- **Refine workspace cleanup navigation and classification:** Fold the complete todo
  from `.planning/todos/completed/2026-08-11-refine-workspace-cleanup-navigation-and-classification.md`
  into Phase 15. Its 31 numbered problem statements and solution constraints are
  mandatory planning inputs.

### Claude's Discretion
- Exact repository/service decomposition, cursor representation, HTMX fragment
  boundaries, CSS selectors, and test class placement may follow established local
  patterns while preserving the locked behavior above.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Product And Design Contract
- `tidymail-ux-rehaul-draft/tidymail-ux-rehaul-draft.design` — sole visual and
  interaction source of truth.
- `tidymail-ux-rehaul-draft/pages/build-notes.html` — Build Notes implementation
  contract referenced by the design project.
- `.planning/todos/completed/2026-08-11-refine-workspace-cleanup-navigation-and-classification.md`
  — folded 31-item remediation scope and solution constraints.

### Planning And Prior Decisions
- `.planning/ROADMAP.md` — Phase 15 placement and dependency on Phase 14.
- `.planning/REQUIREMENTS.md` — existing workspace, action, safety, and design
  requirements; latest Phase 15 decisions govern the no-export cleanup UI.
- `.planning/STATE.md` — current milestone state and promoted todo history.
- `.planning/phases/14-ui-fidelity-and-route-recovery/14-CONTEXT.md` — shared shell,
  route, exact-ID, privacy, and design-source decisions carried forward.
- `docs/DECISIONS.md` — locked Java 21, Spring Boot, Thymeleaf/HTMX, Postgres,
  metadata-only, and non-AI architecture.

### Existing Implementation
- `src/main/resources/templates/fragments/workspace-rail.html` — rail navigation,
  account selector, queue status, and session controls.
- `src/main/resources/templates/senders.html` — Bulk Archive/Delete page shell.
- `src/main/resources/templates/unread.html` — Unread Only page shell.
- `src/main/resources/templates/unsubscribe.html` — Bulk Unsubscribe page shell.
- `src/main/resources/templates/fragments/senders-results-frag.html` — sender
  category rows and current exact-scope preview forms.
- `src/main/resources/templates/fragments/unread-results-frag.html` — overlapping
  unread category rows and current classification controls.
- `src/main/resources/templates/fragments/unsubscribe-results-frag.html` —
  unsubscribe row hierarchy and actions.
- `src/main/resources/static/js/cleanup-categories.js` — category expansion,
  collapse, and dialog behavior.
- `src/main/resources/static/css/workspace.css` — shared workspace tokens, rail,
  category, row, responsive, and action styling.
- `src/main/java/com/tidymail/service/WorkspaceModelService.java` — account options
  and authorized rail facts.
- `src/main/java/com/tidymail/service/SenderDecisionQueryService.java` — current
  sender category aggregation that streams all rows.
- `src/main/java/com/tidymail/service/UnreadDecisionQueryService.java` — current
  overlapping unread aggregation that streams all rows.
- `src/main/java/com/tidymail/service/SenderCategoryService.java` — account-owned
  category assignment integration point.
- `src/main/java/com/tidymail/service/DecisionClassifier.java` — deterministic
  classification integration point.
- `src/main/java/com/tidymail/service/SelectionMaterializationService.java` — exact
  provider-message-ID scope materialization.
- `src/main/java/com/tidymail/service/ActionCommandService.java` — Review staging
  integration point.
- `src/main/java/com/tidymail/repository/DecisionSurfaceQueryRepository.java` —
  bounded sender/unread query and Inbox predicate integration point.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `WorkspaceModelService` and `workspace-rail.html` already centralize authorized
  account options, selected route, queue count, and session controls.
- `SenderCategoryService`, `SenderCategoryAssignment`, and the category dialogs
  provide an account-owned persistence base for manual corrections.
- `SelectionMaterializationService` and `ActionCommandService` provide the exact-ID
  staging path that must replace Selection Preview navigation.
- `cleanup-categories.js` already owns expand/collapse and modal behavior and can be
  extended for collapse-reset and bounded HTMX loading.

### Established Patterns
- Thymeleaf SSR with HTMX enhancement and full-page fallback.
- Server-owned authorization, query state, CSRF, and exact materialized action scopes.
- Deterministic category accents and semantic `details` disclosures.
- PostgreSQL production behavior with H2-compatible tests and MockMvc route coverage.

### Integration Points
- The rail needs route-title labels, authorized Review/Unread counts, auto-submit
  account switching, and removal of the standalone queue row.
- Both category query services currently stream and classify complete result sets;
  they must move to bounded aggregate-plus-page queries.
- Sender and unread row fragments currently submit to Selection Preview and must
  instead stage exact scopes and return an in-place disabled state.
- `DecisionSurfaceQueryRepository` owns the unread predicate and must require both
  unread state and Inbox membership.
- Bulk Unsubscribe candidate/history queries need production-shaped profiling before
  UI latency work is accepted.

</code_context>

<specifics>
## Specific Ideas

- Preserve the requested compact, quiet hierarchy: rail-only account scope, closed
  categories, no toolbar card, and no bottom navigation mock.
- Use stable volume-first category ordering so five-row increments never repeat or
  reorder unexpectedly.
- Keep loading controls subtle and centered; they must not use the blue primary-button
  treatment or overlap sender-row content.

</specifics>

<deferred>
## Deferred Ideas

- Inline undo after staging is deferred.
- Gmail filter/rule creation and Auto-archive remain future capabilities.
- Public deployment remains blocked until localhost verification is complete.
- Outlook feature parity remains governed by the existing future requirement.

</deferred>

---

*Phase: 15-implement-pending-cleanup-and-navigation*
*Context gathered: 2026-08-11*
