# Phase 13 - Cross-Phase UI Review

**Audited:** 2026-08-11  
**Implementation span:** Phases 9-13  
**Baseline:** Approved Phase 9-13 UI specifications, approved uploaded mocks, Build Notes, and the 2026-08-11 latest-design alignment  
**Screenshots:** Captured from all six uploaded HTML mock pages and the authenticated localhost Senders, Review, and Analytics routes at a 1592x1224 viewport. Unread and Unsubscribe returned only the safe data-access error response.
**Release context:** The recorded browser UAT predates the 2026-08-11 mock-alignment work and still marks authenticated 375x812 at 200% zoom as constrained (`13-UAT.md:163-179`; `13-DRAFT-CONTRACT-ALIGNMENT.md:41-43`).

---

## Authenticated Localhost Evidence

| Route | 2026-08-11 observation | Result |
|---|---|---|
| `/senders` | Loaded 290 per-sender Archive buttons because every category body was expanded on first render. The shell measured 58px top navigation + 226px control rail. | Major mock-fidelity failure |
| `/unread` | Response body contained only `The requested data is temporarily unavailable.` from `SafeDataAccessErrorHandler`. | Blocker |
| `/unsubscribe` | Response body contained only `The requested data is temporarily unavailable.` from `SafeDataAccessErrorHandler`. | Blocker |
| `/review` | Loaded the current top-navigation shell and empty queued-action state. | Reviewable, materially different from mock hierarchy |
| `/analytics` | Loaded 119,601 indexed messages and 154 table rows; the primary trend and actions remained data tables/plain values rather than the approved chart-led presentation. | Major mock-fidelity failure |

Two Java builds were initially listening on port 8080: an Aug 10 packaged JAR on IPv4 and the Aug 11 classes on the wildcard listener. The stale JAR was stopped and the observations above were repeated against the remaining current process; the Unread and Unsubscribe failures persisted.

---

## Pillar Scores

| Pillar | Score | Key Finding |
|---|---:|---|
| Copywriting | 2/4 | Latest mock labels are mostly present, but destructive language conflicts with the approved safety vocabulary and the confirmation omits the required 30-day trash-window warning. |
| Visuals | 2/4 | The quiet category concept is present, but the approved left-rail shell, category accents, charts, and modal treatments are materially flattened or absent. |
| Color | 2/4 | Core tokens are coherent, but approved category-specific accents and gradients are not implemented and warning colors are partly hardcoded outside the token system. |
| Typography | 2/4 | The implementation follows the older 22/12px spec scale, not the latest mocks' approximately 28-30/14px compact-polished scale. |
| Spacing | 3/4 | The 4px scale and responsive stacking are consistent; shell width and content density diverge from the latest approved mocks. |
| Experience Design | 1/4 | Required exports are not discoverable on three pages, the snapshot is undiscoverable, and several represented interactions are broken or fragment-only. |

**Overall: 12/24**

---

## Contract Precedence

The 2026-08-11 alignment explicitly says the latest draft mocks were implemented, including category cleanup, one-account mailbox analytics, LIFO Review, and exact safety controls (`13-DRAFT-CONTRACT-ALIGNMENT.md:1-13`). Those product-direction changes explain why production no longer resembles parts of the older Phase 10 and Phase 13 evidence-dashboard specifications.

This audit therefore classifies:

- Exact materialization, protected-sender blocks, staged/immediate confirmation, honest unsubscribe outcomes, and omission of fake mock data as intentional safety-driven deviations.
- The new category-based Senders/Unread information architecture and mailbox-activity Analytics as intentional product-direction deviations.
- Missing capabilities that remain required by Phase 13 and were not explicitly superseded, such as five-page export affordances, the sanitized snapshot affordance, active-sync status, responsive UAT, and functional represented controls, as defects.

The repository still labels `13-UI-SPEC.md` approved and `13-04-PLAN.md:52-55` requires complete evidence definitions, source links, exports, and exact N/D values. A formal re-baseline is required if the August 11 mock contract is intended to supersede those requirements.

---

## Severity-Ranked Findings

### Cross-Page

#### BLOCKER - Two of the five primary workspace routes fail in the authenticated app

Authenticated navigation to `/unread` and `/unsubscribe` returns HTTP 503 content containing only `The requested data is temporarily unavailable.` The response comes from the global `DataAccessException` handler (`SafeDataAccessErrorHandler.java:18-26`), so users cannot reach either the mock-implied UI or its functionality.

This is more severe than visual drift: two primary navigation destinations are unusable with the real indexed mailbox. Automated rendering tests did not exercise the current production-data query failure.

#### BLOCKER - Three required CSV affordances and the snapshot affordance disappeared

Phase 13 requires visible links on Senders, Unread, Unsubscribe, Review History, and Analytics (`13-UI-SPEC.md:329-337`) plus `Download sanitized snapshot` (`13-UI-SPEC.md:65-75`). Production exposes only Unsubscribe and Review History (`unsubscribe.html:21-23`; `review-history-frag.html:7-9`). Senders, Unread, and Analytics contain no export link, and Analytics contains no snapshot link (`senders.html:15-40`; `unread.html:15-40`; `analytics.html:15-69`).

The backend routes still exist, so this is a discoverability/task-completion regression rather than an unavailable feature.

#### BLOCKER - The approved Analytics contracts are unresolved, not merged

The latest mock requires mailbox metrics, Top Received/Top Sent, size cards, and Archived/Deleted (`Build Notes.html:419-432`; `Analytics.html:293-461`). Production implements that structure (`analytics-results-frag.html:6-131`).

The still-approved Phase 13 spec instead requires cleanup-evidence metrics, Received-vs-Cleaned, outcomes, efficiency, coverage, availability, definitions, N/D values, and exact source links (`13-UI-SPEC.md:132-268`). Those sections and source links are absent from the current page. The old source route remains, but users cannot reach it from current Analytics.

This is an intentional direction change, but Phase 13 cannot be considered contract-complete until the older spec is formally superseded or its evidence features are restored alongside the new mailbox view.

#### WARNING - Latest mock shell fidelity is incomplete

All approved mocks use a 248px navigation rail containing brand, account, Cleanup/Manage navigation, logout, feedback, and rail collapse (`Senders.html:213-256`; `Analytics.html:212-259`). Production retains the older Phase 9 58px horizontal navigation and a 226px controls-only rail (`workspace-nav.html:4-25`; `workspace-rail.html:4-18`; `workspace.css:28-30,68-144`).

This matches the older Phase 9 shell contract (`09-UI-SPEC.md:123-127`) but not the latest mock composition. It is a fidelity defect, not a safety requirement.

#### WARNING - Active-sync feedback is missing on Senders and Unread

The latest Build Notes require a conditional active-sync banner with an Analytics link (`Build Notes.html:460-469`), and the older shared-page contract requires workspace sync state (`10-UI-SPEC.md:51-61`). Unsubscribe and Review render the state fragment, and Analytics renders loading copy (`unsubscribe.html:25`; `review.html:45`; `analytics.html:23-26`). Senders and Unread render neither state nor active-sync banner (`senders.html:15-40`; `unread.html:15-40`).

### Senders

#### BLOCKER - Category disclosure defaults make the page effectively unscannable

The latest Build Notes require categories to be closed by default (`Build Notes.html:504-506`). Authenticated localhost rendered every category body open, exposing 290 Archive buttons in one page. The mock intentionally shows only selected example categories expanded (`Senders.html:295-407`).

This is the largest immediate Senders usability defect: the intended quiet category overview becomes a full mailbox-length action list before the user chooses a category.

#### WARNING - Approved category hierarchy lost its identifying visual language

The mock uses a colored leading stripe and soft category-specific gradient for Marketing, Newsletters, Notifications, Receipts, user categories, and Other (`Senders.html:294-440,679-691`). Production renders every category as the same white bordered card (`senders-results-frag.html:4-73`; `workspace.css:888-925`).

The category structure is correct, but scanability and user-category distinction are materially weaker.

#### WARNING - Latest title scale and content width are not implemented

The mock uses a 28px page title, 14px body, 248px rail, and 1060px content maximum (`Senders.html:162-163,481-484,592-601`). Production uses 22px page titles, 12px body, 226px rail, and a 1480px shell (`workspace.css:28-30,40-45,126-132,241-246`).

This follows the older Phase 9 typography contract (`09-UI-SPEC.md:129-138`) but not the later approved visual direction.

#### WARNING - Destructive action wording is ambiguous

The page title and Settings option use `Bulk Archive/Delete` and `Bulk delete` semantics (`senders.html:6,18,47-59`). The approved safety vocabulary says `Move to trash`, never permanent delete (`10-UI-SPEC.md:292-303`; `12-UI-SPEC.md:470-481`).

The latest mock intentionally uses Delete, but the UI needs adjacent Trash/recovery copy so the shorter label does not imply permanent deletion.

### Unread

#### BLOCKER - `Change type` cannot identify the sender being changed

Each Direct Mail row opens `unread-category-dialog` without carrying a row key or sender value (`unread-results-frag.html:28-55`). The dialog posts only account, return route, category, and optional category name (`unread.html:64-87`). The JavaScript only opens/closes dialogs and does not bind row data (`cleanup-categories.js:17-28`).

The latest contract requires a working override when Direct Mail classification is wrong (`Build Notes.html:518-529`). As implemented, the represented control cannot complete that task.

#### WARNING - Approved unread category accents are absent

The mock assigns distinct accent treatments to lists, old mail, direct mail, attachments, and Other (`Unread Emails.html:293-451,656-667`). Production renders uniform category cards (`unread-results-frag.html:4-83`; `workspace.css:888-925`).

#### WARNING - Attachment unavailability is honest but visually under-explained

The implementation correctly says attachment grouping is unavailable until approved metadata exists (`unread-results-frag.html:21-26`). This is a valid safety/data deviation from the populated mock, but a neutral availability badge would make the state easier to scan than body copy alone.

### Unsubscribe

#### WARNING - Compact pill controls became a full form with an extra Apply step

The approved mock and Build Notes require compact category/date/search controls and a right-aligned Settings pill (`Unsubscribe.html:282-316`; `Build Notes.html:533-553`). Production exposes labels, selects, a details popover, and an `Apply` button as one form (`unsubscribe.html:27-80`).

The controls are functional and safer than inert mock buttons, but their visual density and interaction model do not match the approved compact toolbar.

#### WARNING - `Delete all` conflicts with safety vocabulary

The overflow menu renders `Delete all` (`unsubscribe-results-frag.html:93-120`) although the cross-phase UI specs require `Move to trash`/`Trash`. This is not justified by exact-scope safety because the wording can change without changing behavior.

#### WARNING - View Stats has no non-HTMX fallback

`View stats` is a plain button with only `hx-get` and no link/form fallback (`unsubscribe-results-frag.html:93-104`). The approved mock expects a centered modal (`Unsubscribe.html:369-423`), while the older contracts require JavaScript enhancement rather than JavaScript-only task access. Without HTMX, the represented action is inert.

### Review

#### BLOCKER - `View Emails` returns a fragment as a standalone page

The row link is a normal anchor with no HTMX target or modal container (`review-queue-frag.html:118-120`). Its controller returns only `email-stats-modal-frag :: modal(emailStats)` (`ReviewActionsController.java:69-77`). Normal navigation therefore replaces the workspace with a fragment-only response.

The approved latest alignment explicitly claims a reusable View Emails modal (`13-DRAFT-CONTRACT-ALIGNMENT.md:11`), and the mock requires that modal interaction (`Build Notes.html:483-497`).

#### WARNING - Bulk controls are in the wrong hierarchy

The mock places Execute/Undo in the top toolbar (`Review.html:263-274`); production places disabled bulk controls in a sticky summary after the table (`review-queue-frag.html:143-151`). The production placement follows the older Phase 12 side-summary contract, but it misses the latest approved primary-action hierarchy.

#### WARNING - Row actions duplicate Execute

Production renders direct Execute, direct Undo, and an ellipsis containing another Execute (`review-queue-frag.html:98-134`). The latest mock requires the row to show only high-attention Undo plus an ellipsis with View Emails and Execute (`Build Notes.html:483-494`).

#### WARNING - Delete confirmation does not state the approved 30-day window

The confirmation has a distinct destructive warning (`review-confirmation-frag.html:13-17`), but its service copy says only that the provider controls the recovery window (`ActionApprovalService.java:273-278`). The latest approved Build Notes require an explicit 30-day trash-window warning (`Build Notes.html:491-493`).

#### WARNING - Reused email modal omits the required snippet column

The mock requires Sender, Subject, snippet, and Date received (`Build Notes.html:494-496`). The production modal shows Sender, Subject, Received, and State (`email-stats-modal-frag.html:58-80`). This is not explained by the latest metadata alignment, which explicitly added snippet metadata.

### Analytics

#### WARNING - Real mailbox volume overwhelms the table-only presentation

Authenticated localhost rendered 154 table rows for the current Analytics selection. The mock's chart-first hierarchy and max-10 Top Received/Top Sent tables are specifically designed to summarize this volume (`Build Notes.html:424-432`). Semantic tables should remain available, but they cannot be the only primary encoding at this scale.

#### BLOCKER - Current page has no export, snapshot, or source-row actions

The page header contains no actions (`analytics.html:15-21`), metric cards are tab buttons only (`analytics-results-frag.html:6-29`), and trend rows have no source links (`analytics-results-frag.html:31-65`). This breaks the still-approved Phase 13 completion contract even though the underlying routes exist.

#### WARNING - The primary trend is data-only, not the approved visual

The latest mock specifies a responsive column chart (`Analytics.html:316-355`), while production renders only a semantic table (`analytics-results-frag.html:31-65`). Keeping semantic data is correct accessibility behavior; omitting the adjacent visual is a fidelity defect.

#### WARNING - `TidyMail actions` lacks the approved two-bar treatment

Production presents two plain number/label blocks under `role="img"` (`analytics-results-frag.html:124-131`). The mock uses visibly scaled Archived and Deleted columns with a legend (`Analytics.html:444-461`). The current `role="img"` describes a chart that is not visually present.

#### WARNING - Analytics filters are structurally close but visually under-resolved

The two collapsed details controls preserve the mock's hidden-option behavior (`analytics.html:28-67`), which is good. They lack the mock's compact button styling and selected-range hierarchy (`Analytics.html:280-291`), and the stylesheet's `.analytics-filters > summary` rule does not match summaries nested inside `.analytics-filter-menu` (`workspace.css:1096-1113`).

---

## Pillar Detail

### 1. Copywriting (2/4)

- Exact-scope and unsubscribe outcome copy remains appropriately cautious (`unsubscribe-inspection-frag.html:73-117`; `review-outcome-frag.html:9-23`).
- `Delete`, `Delete all`, and `Archived/Deleted` conflict with the approved Trash vocabulary (`unsubscribe.html:70-77`; `unsubscribe-results-frag.html:113-120`; `analytics-results-frag.html:23-27`).
- Destructive confirmation lacks the latest approved explicit 30-day window.
- `Current action ... attention style stays consistent` is implementation-oriented copy exposed to users on Senders and Unread (`senders.html:22-37`; `unread.html:22-37`).

### 2. Visuals (2/4)

- The production shell and cards are coherent, but the latest mock's left navigation rail, category accents, charts, and centered modal overlays are not reproduced.
- Review's hierarchy is diluted by views, metrics, filters, direct row Execute, sticky bulk controls, and duplicate actions before the user reaches the approved simple ledger interaction.
- Analytics labels a pair of plain values as a chart without rendering a meaningful graphical encoding.

### 3. Color (2/4)

- Core background, surface, primary, status, border, and text tokens match the established palette (`workspace.css:1-30`).
- The approved category accent system is absent; all category cards use the same surface (`workspace.css:888-925`).
- Cleanup action orange and Review undo orange are hardcoded (`workspace.css:971-981`; `review.html:21-23`) instead of using named attention tokens, making future contrast/theme changes inconsistent.

### 4. Typography (2/4)

- The implementation consistently applies the older 22/14/12/10px scale.
- The latest mocks and Build Notes call for approximately 28-30px page titles and a 14px compact body (`Senders.html:162-163,601`; `Build Notes.html:533-537`).
- Analytics metric values use 24px rather than the older approved 20px evidence scale (`workspace.css:1250-1253`; `13-UI-SPEC.md:41-46`), reflecting the unresolved contract split.

### 5. Spacing (3/4)

- The 4/8/12/16/24px spacing scale is used consistently (`workspace.css:19-30`).
- Responsive stacking, 44px narrow targets, local table overflow, and reduced-motion rules are present (`workspace.css:653-824,1269-1365`).
- The latest mocks use a 248px rail and 1060/1180px content maximum, while production keeps the older 226px/1480px shell. This makes category pages looser and less focused on wide screens.

### 6. Experience Design (1/4)

- Exact-scope preview, protection, conflict, truthful outcome, and undo boundaries are strong and intentionally exceed the mocks.
- Missing exports/snapshot make completed Phase 13 capabilities undiscoverable.
- Unread `Change type` cannot bind a target.
- Review `View Emails` breaks the shell instead of opening the claimed modal.
- View Stats is HTMX-only, and the latest authenticated 375x812 plus 200% zoom gate remains unverified.

---

## Intentional Safety Deviations

These should not be "fixed" back to mock behavior:

1. Per-sender Archive/Delete/Mark Read controls create an exact server preview instead of mutating from displayed or estimated counts (`senders-results-frag.html:45-61`; `unread-results-frag.html:56-71`).
2. Protected senders remain visibly marked and non-executable (`senders-results-frag.html:23-37,55-59`).
3. Unsubscribe requires method inspection, fresh confirmation, staged/immediate choice, and honest accepted-versus-confirmed outcomes (`unsubscribe-inspection-frag.html:73-140`).
4. Review retains conflicts, exact target counts, independent outcomes, and applied-target-only undo even though the latest mock is visually simpler.
5. Sample metrics, fake rows, raw provider IDs, decorative `href="#"` controls, and unsupported success claims are correctly absent.

---

## Remediation Sequence

1. **Restore route availability.** Diagnose and fix the production-data query failures on Unread and Unsubscribe before further visual validation.
2. **Resolve the Analytics contract.** Formally supersede the older Phase 13 evidence UI or restore its evidence sections alongside the latest mailbox analytics. In either case, restore Analytics CSV, sanitized snapshot, and appropriate source-row access.
3. **Fix Senders disclosure defaults.** Render categories closed by default and expose rows only after deliberate expansion.
4. **Restore all required download affordances.** Add filter-faithful Senders and Unread CSV links and verify current-state preservation; keep existing Unsubscribe and Review History links.
5. **Repair represented interactions.** Bind Direct Mail `Change type` to the selected sender; make Review View Emails and Unsubscribe View Stats true accessible dialogs with full-page fallbacks.
6. **Close destructive-copy safety.** Use Trash/Move to trash where space permits and state the 30-day recovery window in the separate destructive confirmation.
7. **Converge on the latest visual shell.** Move navigation/account/session treatment into the approved left rail or explicitly reapprove the older top-navigation shell; then implement the approved 248px/content-width hierarchy.
8. **Restore mock visual hierarchy.** Add category accent tokens, 28-30px page titles, 14px desktop body copy, compact unsubscribe pills, and real accessible Analytics bar visuals adjacent to semantic tables.
9. **Rerun visual gates.** Capture authenticated desktop, 980px, 720px, 375x812, 320px stress, keyboard, and 200% zoom evidence after the above changes.

---

## Files Audited

- All relevant `PLAN.md`, `SUMMARY.md`, `CONTEXT.md`, and `UI-SPEC.md` files under `.planning/phases/09-*` through `.planning/phases/13-*`
- `.planning/phases/13-analytics-export-and-integrated-product-verification/13-DRAFT-CONTRACT-ALIGNMENT.md`
- `.planning/phases/13-analytics-export-and-integrated-product-verification/13-UAT.md`
- Approved mocks under `/tmp/tidymail-ui-mocks/pages/`: `Senders.html`, `Unread Emails.html`, `Unsubscribe.html`, `Review.html`, `Analytics.html`, and `Build Notes.html`
- Production workspace pages under `src/main/resources/templates/`
- Production workspace fragments under `src/main/resources/templates/fragments/`
- `src/main/resources/static/css/workspace.css`
- `src/main/resources/static/js/cleanup-categories.js`
- Narrow controller/service evidence for represented UI behavior and destructive warning copy

Registry audit skipped: `components.json` is absent and the UI uses no third-party component registry.
