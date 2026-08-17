# Phase 15: Implement Pending Cleanup and Navigation - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md; this log preserves the alternatives considered.

**Date:** 2026-08-11
**Phase:** 15-implement-pending-cleanup-and-navigation
**Areas discussed:** Todo incorporation, authoritative-contract handling

---

## Todo Incorporation

| Option | Description | Selected |
|--------|-------------|----------|
| Fold into Phase 15 | Treat the complete cleanup/navigation/classification todo as Phase 15 scope. | ✓ |
| Leave deferred | Keep the todo separate from this phase. | |

**User's choice:** Fold into Phase 15
**Notes:** The folded todo contains the authenticated-localhost findings and 31
numbered remediation items for the rail, cleanup pages, classification, loading,
exact-ID staging, Inbox-only unread behavior, and unsubscribe performance.

---

## Authoritative-Contract Handling

The workflow offered classification overrides, category learning, bounded loading,
and rail navigation as possible gray areas. The selection prompt was skipped. No
additional preference was inferred from that skip.

Phase context therefore carries forward the already authoritative sources:

- `tidymail-ux-rehaul-draft/tidymail-ux-rehaul-draft.design`
- its Build Notes implementation contract
- the explicitly folded todo and its Solution section
- locked project constraints from prior phases

No alternate mock, new product behavior, or discretionary scope was introduced.

## Claude's Discretion

- Technical decomposition, cursor representation, HTMX fragment boundaries, CSS
  selectors, and test placement may follow established repository patterns.

## Deferred Ideas

- Inline undo after staging.
- Gmail filter/rule creation and Auto-archive.
- Public deployment before localhost verification.
