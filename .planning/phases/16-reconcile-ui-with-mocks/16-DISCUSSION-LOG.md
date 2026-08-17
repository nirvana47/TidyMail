# Phase 16: Reconcile UI with mocks - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-08-12T06:00:11Z
**Phase:** 16-reconcile-ui-with-mocks
**Areas discussed:** Folded todos, status feedback, Unread Only copy, fidelity depth

---

## Folded Todos

| Option | Description | Selected |
|--------|-------------|----------|
| Status clutter only | Fold only `remove-cleanup-page-status-clutter.md`; treat the older navigation/classification todo as prior Phase 15 background. | |
| Both todos | Fold both matching todos, so Phase 16 can revisit any remaining nav/classification items from the older UAT too. | ✓ |
| Neither | Do not fold pending todos; use only the roadmap requirements and existing context. | |

**User's choice:** Both todos.
**Notes:** Phase 16 should explicitly fold both matching UI todos. The older
navigation/classification todo is overlap and regression context, not permission to
undo correct Phase 15 behavior.

---

## Status Feedback

| Option | Description | Selected |
|--------|-------------|----------|
| Inline toast | Use a compact transient status near the action controls; no persistent panel before an action occurs. | |
| Row feedback | Only change affected rows/buttons to `Staged`/disabled and rely on the Review rail link for follow-up. | ✓ |
| Temporary panel | Render no panel initially, but insert a small result panel only after success or error. | |

**User's choice:** Row feedback.
**Notes:** Remove the persistent `Ready to stage for Review` panel. Do not replace it
with a post-action panel. Successful staging should be visible through affected
row/button state, while Review remains the inspection destination.

---

## Unread Only Copy

| Option | Description | Selected |
|--------|-------------|----------|
| Keep simple | Use: `Find Inbox-unread senders, then stage exact cleanup actions for Review.` | |
| Explain groups | Use: `Review Inbox-unread sender groups and stage exact actions without changing your mailbox yet.` | |
| You decide | Let implementation choose concise copy consistent with the mocks and Build Notes. | |
| Free text | User-provided replacement copy. | ✓ |

**User's choice:** `Tidy up your unread emails`.
**Notes:** Use this exact text for the Unread Only header copy. Preserve the backend
Inbox-only unread predicate and remove the stale explanatory notes from the category
area.

---

## Fidelity Depth

| Option | Description | Selected |
|--------|-------------|----------|
| Strict mock | Treat the design file and Build Notes as binding; adjust CSS/template structure until authenticated pages visually match. | |
| Pragmatic close | Fix the captured mismatches and obvious density issues, but avoid broad CSS restructuring unless necessary. | |
| Audit first | Make page-by-page walkthrough evidence the first deliverable, then decide remediation depth from the delta report. | ✓ |

**User's choice:** Audit first.
**Notes:** Phase 16 should start with a page-by-page authenticated walkthrough against
the `.design` file and Build Notes. The audit determines whether simple captured
mismatch fixes are enough or whether deeper template/CSS restructuring is required.

## Claude's Discretion

- Exact CSS selectors, fragment boundaries, and test locations may follow established
  local patterns.
- Row-level error presentation can be chosen during planning/implementation, provided
  there is no persistent cleanup status panel and accessibility is preserved.

## Deferred Ideas

None.
