# Phase 17: Fix Phase 15 and 16 UI blocker follow-ups - Context

**Gathered:** 2026-08-13T12:50:00Z
**Status:** Ready for planning
**Source:** Direct authenticated localhost feedback appended to `.planning/todos/completed/2026-08-11-refine-workspace-cleanup-navigation-and-classification.md`

<domain>
## Phase Boundary

Phase 17 closes the remaining authenticated Phase 15/16 UI blocker feedback after
Phase 16 remediation execution. The source of truth is the pending blocker todo
`.planning/todos/completed/2026-08-11-refine-workspace-cleanup-navigation-and-classification.md`,
with first priority on the two latest sections:

- `## Continuing Phase 15/16 Authenticated Feedback - 2026-08-13`
- `## Continuing Phase 15/16 Authenticated Feedback - 2026-08-13, Rail/Review/Actions`

This phase owns functional blocker fixes and targeted UI/UX polish for:

- Bulk Unsubscribe sender stats modal behavior and stats latency.
- Bulk Unsubscribe primary action wiring and visible feedback.
- Bulk Archive/Delete header/help/Expand all placement.
- Bulk Archive/Delete and Unread Only sender table/header semantics.
- Per-category `Load more` continuation behavior.
- Empty category size text after moving the last sender out.
- Removal of stale category management helper copy.
- Expand all coverage across all categories.
- Unread Only Inbox-vs-All-Mail unread totals and suspicious attachment grouping.
- Rail brand/link styling, utility menu padding, and rail collapse arrow direction.
- Review History table density and visual fit.
- Unread Only destructive selected-action hover contrast and exact-ID Review staging.

This phase must not add new cleanup surfaces, export/snapshot/download affordances,
direct destructive provider execution from cleanup pages, new provider capabilities,
AI, public deployment, or a new frontend framework.
</domain>

<decisions>
## Implementation Decisions

### Source Priority
- **D-01:** The latest 2026-08-13 continuation sections in the blocker todo have
  priority over older Phase 16 mock-remediation assumptions when there is a conflict.
- **D-02:** Phase 17 is a follow-up remediation phase, not a broad redesign. Keep
  edits tightly scoped to the authenticated feedback and preserve Phase 16 changes
  that already work.
- **D-03:** `tidymail-ux-rehaul-draft/pages/unsubscribe-compact-suite.html:2449-2505`
  is the concrete modal reference for Bulk Unsubscribe sender stats.

### Bulk Unsubscribe
- **D-04:** `View stats` must open an in-page modal/dialog, not navigate to a full
  page or replace the main route. The modal must include sender context, action row,
  distribution chart, and email table information comparable to the mock while
  preserving accessibility and keyboard dismissal.
- **D-05:** Sender stats must not take multiple seconds to render under
  production-shaped data. Planning must include profiling or bounded query evidence
  for the stats path.
- **D-06:** The primary `Unsubscribe` action in a sender row must be visibly wired.
  Clicking it must open the expected preview/modal/staging UI or show an honest,
  accessible unavailable/error state. Silent no-op behavior is forbidden.

### Bulk Archive/Delete And Unread Only Cleanup Pages
- **D-07:** On Bulk Archive/Delete, the compact help affordance belongs beside the
  `Bulk Archive/Delete` H1 and `Expand all` belongs on that same header line.
- **D-08:** Remove the `Action` column heading from Bulk Archive/Delete and Unread
  Only when row actions only appear through the contextual selection bar.
- **D-09:** `Load more` appends data rows only. It must not repeat the sender table
  header row inside the loaded fragment.
- **D-10:** Empty categories must not show `Size pending sync` after the final sender
  is moved out. A blank value or `0 KB` is acceptable.
- **D-11:** Remove the visible category helper block containing `Select senders first,
  then choose where they belong.`, `Move selected`, and `Create category` when the
  actual action path is the contextual selection bar.
- **D-12:** `Expand all` must expand every category, including `Automated
  notifications`, `Receipts and transactions`, `Personal senders`, initially unloaded
  categories, and zero-loaded categories.

### Unread Semantics And Destructive Selection
- **D-13:** Unread Only must show the authoritative provider Inbox unread total. It
  must not count archived unread All Mail messages as Inbox unread. The user reported
  7,930 Gmail Inbox unread messages during feedback; the page needs a server-owned
  total that can be reconciled with Gmail Inbox unread.
- **D-14:** `Has attachments` showing `0 senders` and `0 unread` is suspicious and
  must be audited through metadata ingestion, repository predicates, and UI grouping.
- **D-15:** `Delete selected` hover/focus states must remain high-contrast; white text
  must not turn into a hard-to-read blue shade.
- **D-16:** Clicking `Delete selected` must visibly stage selected exact message IDs
  for Review or surface an accessible failure. It must not directly delete provider
  mail from the cleanup page, and it must not silently no-op.

### Rail And Review
- **D-17:** The rail brand remains clickable as a home/navigation affordance, but it
  should be styled as brand chrome rather than an underlined normal hyperlink.
- **D-18:** The utility rail menu must align `Delete my data and logout` with
  Feedback and Logout left padding while preserving destructive warning copy,
  confirmation, CSRF, and POST semantics.
- **D-19:** The rail collapse affordance should use a left-pointing arrow/disclosure
  because collapse is a horizontal rail action, not a vertical disclosure.
- **D-20:** Review History must be visually compact and consistent with the workspace
  system while preserving durable audit data, outcome counts/reasons, undo
  eligibility, append-only timeline information where needed, and provider follow-up
  links.

### Safety Constraints
- **D-21:** All destructive actions still require exact immutable action scopes,
  CSRF, account authorization, protected-sender handling, and Review approval where
  required. Counts, totals, sender rows, and estimated sizes are never execution
  scopes.
- **D-22:** No user-facing export, CSV, snapshot, download, or share affordance may be
  reintroduced.
- **D-23:** Existing authenticated UAT blockers from Phase 16 remain visible:
  Docker/Testcontainers full suite and authenticated desktop/375x812/200% zoom
  browser walkthrough must be handled honestly.

### Claude's Discretion
- Exact file factoring, CSS class names, test class placement, and modal fragment
  boundaries may follow existing local patterns if the locked behavior above is
  covered by focused automated tests and authenticated UAT criteria.
</decisions>

<success_criteria>
- Bulk Unsubscribe `View stats` opens an accessible modal and the stats path has
  bounded performance evidence.
- Bulk Unsubscribe primary row actions provide visible preview/staging/error behavior
  and cannot silently no-op.
- Cleanup category pagination appends rows without repeated headers.
- Empty categories do not show stale pending size text.
- Expand all expands every category on Bulk Archive/Delete and Unread Only.
- Unread Only exposes a server-owned provider Inbox unread total and excludes archived
  unread All Mail from that total.
- Suspicious attachment grouping is audited and corrected or explicitly documented
  with evidence.
- Review History becomes compact enough to match the app's workspace visual system
  without losing audit semantics.
- Destructive selected actions remain high-contrast on hover/focus and stage exact IDs
  to Review with visible success/error feedback.
</success_criteria>

<risk_summary>
- Bulk Unsubscribe and Unread Only include functional blockers, not just visual polish;
  plans must include behavior tests, not only CSS assertions.
- Several fixes touch shared CSS/templates; wave planning must avoid same-wave edits
  to `workspace.css` and overlapping fidelity tests.
- The user is actively performing authenticated localhost UAT. Plans must preserve
  dirty-worktree safety and must not reset user changes.
</risk_summary>
