# Phase 14: UI Fidelity And Route Recovery - Context

**Gathered:** 2026-08-11
**Status:** Ready for planning
**Mode:** Autonomous, user delegated implementation decisions

<domain>
## Phase Boundary

Restore the five-page authenticated cleanup workspace documented by
`tidymail-ux-rehaul-draft/tidymail-ux-rehaul-draft.design`. Use only its
`Bulk Archive/Delete`, `Unread Only`, `Bulk Unsubscribe`, `Review`, `Analytics`, and
`Build Notes` pages. Fix real-data route failures first, then converge the shared shell
and page interactions while preserving all exact-ID, protection, confirmation, LIFO,
privacy, and purge constraints from Phases 9-13.

</domain>

<decisions>
## Implementation Decisions

### Route Availability
- Treat `/unread` and `/unsubscribe` HTTP 503 responses as release blockers.
- Fix the concrete PostgreSQL defects rather than adding fallback data or suppressing exceptions.
- Add focused PostgreSQL-compatible regression coverage for both repository paths.
- Improve safe data-access logging so future failures retain the root exception.

### Shared Shell
- The named `.design` file is the sole visual source of truth; its `Build Notes` page
  is the implementation contract.
- Older or exported HTML mocks, other chats, `tidymail-ux-rehaul`, and separate Design
  Canvas projects are not implementation references.
- Primary navigation, active account, account connection, logout choices, Feedback, and collapse behavior belong in one left rail.
- The account selector remains rail-only; page-level account selectors are forbidden.
- Preserve server-owned route/account state, CSRF-backed session actions, keyboard navigation, and narrow-screen stacking.

### Page Fidelity
- Category pages remain quiet and closed by default, with deterministic category accent styling and neutral fallback.
- Mock visuals may be adapted when required by exact materialization, protected-sender handling, truthful unsubscribe outcomes, or accessible semantic data.
- Semantic analytics tables remain available, but compact visual bars/charts become the primary hierarchy.
- Mock-implied actions must be functional or explicitly unavailable; fragment-only navigation is not acceptable.

### Safety And Privacy
- Archive, trash, mark-read, and unsubscribe continue to execute only from materialized provider message IDs.
- Review remains LIFO by `queued_at` and `sequence` descending.
- Deletion confirmation explicitly states the provider Trash recovery window, including Gmail's typical 30-day limit.
- No body storage, fabricated metrics, provider-ID exposure, or client-side ownership bypass.

### Claude's Discretion
- Exact CSS implementation, icon treatment, and responsive breakpoint details may follow existing local patterns where they preserve mock hierarchy.
- Accessible HTML/CSS bar encodings are preferred over adding a chart dependency.

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- `WorkspacePageModel` and `workspace-rail.html` already provide owned account options and session actions.
- Existing export and sanitized snapshot endpoints cover all required surfaces.
- Existing selection-preview and action-command services enforce materialized exact scopes.
- Existing semantic analytics tables provide exact accessible values.

### Established Patterns
- Thymeleaf SSR with HTMX enhancement and full-page fallback.
- One `workspace.css` token system and 4px spacing scale.
- PostgreSQL production migrations with H2-compatible test migrations.
- MockMvc plus PostgreSQL integration coverage for route contracts.

### Integration Points
- `DecisionSurfaceQueryRepository` and `UnsubscribeCandidateRepository` own the two failing SQL paths.
- `workspace-nav.html`, `workspace-rail.html`, and all workspace page templates own shell composition.
- `cleanup-categories.js` owns category expansion and dialog binding.
- Export, review-action email, unsubscribe stats, and analytics routes already exist and need discoverable/fallback UI.

</code_context>

<specifics>
## Specific Ideas

- Use only the named `.design` pages and their `Build Notes` implementation contract
  as the fidelity baseline.
- Remediate in this order: route availability, shared shell, represented interactions, visual polish, authenticated localhost verification.

</specifics>

<deferred>
## Deferred Ideas

- Public deployment and tunneling remain deferred until localhost validation passes.
- New provider capabilities, Gmail rule creation, and unsupported attachment/body features remain out of scope.

</deferred>
