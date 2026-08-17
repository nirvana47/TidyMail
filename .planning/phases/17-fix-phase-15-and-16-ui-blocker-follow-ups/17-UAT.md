# Phase 17 Authenticated UAT

Generated: 2026-08-13T14:18:10Z
Status: complete — accepted 2026-08-13

The user accepted the current UI after the subsequent authenticated modal,
chart, table-density, help-icon, and responsive refinement walkthroughs.
Functionality and data-quality concerns are deferred to a new phase rather than
kept open as Phase 15/16 UI blockers.

Browser UAT used the existing authenticated localhost session at `http://localhost:8080`. No credentials were entered, no provider mutations were intentionally executed, and no private screenshots or raw provider identifiers are stored here.

## Results

| Area | Result | Observation | Next Action |
|---|---|---|---|
| Bulk Unsubscribe `View stats` | blocker | Follow-up browser pass clicked `View stats`, but observed an inline inspection-style panel with method status/source facts instead of a clearly classified stats modal. Escape did not dismiss the observed panel. Automated tests cover the intended stats modal, so this needs human visual confirmation against the live DOM. | Re-test manually on `/unsubscribe`: open row menu, click `View stats`, confirm stats modal content and Escape/Close behavior. |
| Bulk Unsubscribe primary `Unsubscribe` | pass | Primary click produced visible destination inspection/confirmation feedback and did not silently no-op. | None unless manual stats retest reveals control confusion. |
| Bulk Unsubscribe continuation | pass | Browser observed `Next` full-page pagination with preserved filters rather than duplicated headers in an appended content region. | None. |
| Bulk Archive/Delete empty metrics | pass | Empty/low-count categories rendered explicit count/metric values instead of hiding or stale pending copy. | None. |
| Bulk Archive/Delete Expand all | pass | `Expand all` was present and expanded visible category sections. | Manual 375px backstop still required. |
| Unread Only `Inbox unread` | pass | Authenticated rail/page state exposed `Unread Only, 8280 Inbox unread messages`; automated tests confirm page-level `Inbox unread` and Inbox-only semantics. | Manual reconciliation against current Gmail count remains recommended because provider state changes. |
| Unread Only `Has attachments` | blocked | Browser reached `/unread` and saw `Has attachments ... 0 senders 0 unread`, but no row selection path was available in the sampled data. Automated tests cover metadata-to-group evidence. | Re-test with account data that has Inbox-unread attachment senders or record true-zero evidence. |
| Unread Only `Delete selected` staging | blocked | No selectable rows were available in the browser snapshot, so `Delete selected` staging into Review could not be exercised. Automated `Phase17UnreadOnlyTest` covers exact-ID Review staging without provider mutation. | Re-test with selectable Unread rows. |
| Rail brand and utility menu | pass | Brand chrome had no visible underline; utility rows aligned; collapse control showed a left-pointing affordance. | Manual 375px backstop still required. |
| Review History compact details | blocked | History page rendered compact columns and history rows, but browser did not successfully observe an expanded row details state. After local shim fix, console target error should be resolved, but a final browser retest is still needed. | Re-test `/review?view=history` after refreshing assets; expand a `Details` row and verify audit/follow-up facts. |
| 200% zoom | pass | Browser evaluate set `document.body.style.zoom='200%'`; observed no horizontal overflow in the checked page. | Repeat manually across all target pages. |
| 375x812 viewport | blocked | Browser tool surface did not provide viewport resize in this run. | Manual browser/devtools check required at 375x812. |

## Closure

Automated evidence is sufficient for code-level safety and regression coverage.
Authenticated UI acceptance is complete by explicit user decision on
2026-08-13. Any remaining behavior or data-accuracy concern belongs to the next
functionality/data-quality phase.
