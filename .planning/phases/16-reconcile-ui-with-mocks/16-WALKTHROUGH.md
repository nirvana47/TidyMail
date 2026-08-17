# Phase 16 Authenticated Walkthrough

**Contract:** Compare the authenticated running app against `tidymail-ux-rehaul-draft/tidymail-ux-rehaul-draft.design` and `tidymail-ux-rehaul-draft/pages/build-notes.html`.

**Current status:** `complete` — superseded by Phase 17 authenticated
walkthroughs and explicit user acceptance on 2026-08-13.

This matrix preserves the state observed during the original Phase 16 run.
Phase 17 supplied the later authenticated evidence and remediation; it is the
authoritative closure record.

## Evidence Boundary

- Performed: design/export/template read-through, source scans, focused Maven suites, full-suite attempt, and one test-only verification-support fix.
- Not performed: authenticated browser login, real localhost page-by-page visual comparison, 375x812 viewport inspection, 200% zoom inspection, keyboard traversal in browser, or safe staging through a live account.
- No cloud deployment, public tunnel, or shareable URL was created.
- No credentials, cookies, tokens, message bodies, or attachment content were captured.

## Walkthrough Matrix

| Page / Focus Area | Expected Mock / Build Note Behavior | Current Evidence | Severity | Remediation Target | Closure Evidence |
| --- | --- | --- | --- | --- | --- |
| Shared rail: labels | Rail labels are exact text-only labels: `Bulk Archive/Delete`, `Unread Only`, `Bulk Unsubscribe`, `Review`, `Analytics`; no visible count badges. | Source and tests show text-only labels, no rendered `.rail-nav-badge`; count facts remain available in aria labels/backend. | aligned_source | None from source evidence. | Automated: `Phase16WorkspaceFidelityTest`, `Phase14WorkspaceFidelityTest`. Human visual confirmation pending. |
| Shared rail: account selector overflow | Long account emails remain inside 248px rail via truncation/ellipsis; selector is rail-only. | Source keeps account selector in `workspace-rail.html`; CSS/read evidence includes rail truncation contract. | human_needed | Verify long email at desktop, 375x812, and 200% zoom. | Pending authenticated visual check. |
| Bulk Archive/Delete: shell and controls | Simple header, rail-only account selector, no search box, `Settings` plus `Expand all`/`Collapse all` right above categories, no broad toolbar/pill wrapper. | Source shows no page-level account selector/search; controls are direct `.cleanup-page-actions`. | aligned_source | Verify visual placement/density against mock. | Automated: focused suites passed. Human visual confirmation pending. |
| Bulk Archive/Delete: category headers | Closed dropdown categories with left title/description and right sender count plus size or `Size pending`; category size may be omitted/estimated. | `senders-results-frag.html` uses `.category-title`, `.category-meta`, `.category-sender-count`, and `.category-size-metric`. | aligned_source | Verify desktop right alignment and responsive wrapping without horizontal scroll. | Automated: focused suites passed. Human viewport check pending. |
| Bulk Archive/Delete: sender rows | Compact table-like rows: Select, Sender, Emails, Size, Domains, Action; strongest sender name; muted address/domain/size; protected senders disabled. | Source rows match column labels and preserve protected label/disabled controls. Test-only Phase 13 assertion was updated to current `Emails` row label. | aligned_source | Verify visual weight, spacing, row height, button radius. | Automated: focused suites passed. Human visual confirmation pending. |
| Bulk Archive/Delete: staging feedback | No persistent `Ready to stage for Review` panel. Staging updates rows/buttons in place with `Staged` or `Already staged`; Review remains approval destination. | Cleanup pages omit status panel includes and use `data-cleanup-feedback`; JS no longer depends on `#cleanup-action-status`. | aligned_source | Stage a safe non-delete sender in authenticated browser and verify Review exact item. | Pending authenticated live staging. |
| Bulk Archive/Delete: delete path | Delete staging requires acknowledgement and visible provider Trash recovery warning; no provider mutation before Review approval. | Sender delete dialog includes Gmail Trash 30-day warning and acknowledgement; `Phase15StagingTest` passed. | aligned_source | Dry-stage delete path in browser where suitable fixture data exists. | Pending authenticated live staging. |
| Unread Only: header/copy | Page title `Unread Only`; helper exactly `Tidy up your unread emails`; no stale explanatory notes above categories. | Source and tests show exact helper and removed stale notes. | aligned_source | Verify no old notes appear in browser after category loads. | Automated: focused suites passed. Human confirmation pending. |
| Unread Only: semantics | Unread Only means provider Inbox plus unread state only; archived unread All Mail is excluded. | `Phase15UnreadSemanticsTest` passed; source read confirms predicate remains guarded. | aligned_source | Human can spot-check account data, but source tests are primary semantic evidence. | Automated pass. |
| Unread Only: category and row layout | Same quiet cleanup structure as Bulk Archive/Delete; sender count plus unread total; rows stack at 375px with 44px controls and no page horizontal scroll. | Source exposes `.category-meta`, `category-unread-count`, compact row labels, and responsive CSS. | human_needed | Verify desktop, 375x812, and 200% zoom. | Pending authenticated viewport check. |
| Unread Only: staging feedback | Row/button-level staging only; no persistent cleanup panel; delete warning preserved. | Source mirrors sender cleanup staging form and delete dialog. | aligned_source | Stage safe unread action and delete dry path in browser. | Pending authenticated live staging. |
| Bulk Unsubscribe: shell | Individual sender workflow, not grouped categories; quiet shell; rail-only account selector; compact category/date/search/settings controls. | Source keeps top controls and table results; no grouped category cleanup model. | aligned_source | Verify compact controls remain separate pills/controls and do not become a full-width card. | Automated/source evidence; human visual confirmation pending. |
| Bulk Unsubscribe: rows and actions | Rows show sender/domain, email count, Read %, primary action, and more menu with View stats, Archive all, Delete all. UI must not claim unsubscribe success until execution result is known. | `unsubscribe-results-frag.html` renders sender/domain, email count, Read %, primary action, and more menu. | aligned_source | Verify stats modal and long text overflow visually. | Pending authenticated visual check. |
| Bulk Unsubscribe: stats modal overflow | Compact single-line email table with ellipsis/truncation; no weakening eligibility/safety/state/performance. | Build Notes and mock read-through confirm expected pattern; app source has stats modal slot but no live modal evidence captured. | human_needed | Open View stats in browser and inspect long email table at desktop, 375x812, and 200% zoom. | Pending authenticated visual check. |
| Review: queue behavior | Review is LIFO ledger; Execute/Undo buttons reflect selected count; delete warning shown for selected delete actions; exact counts/hashes preserved. | Automated staging/review tests passed in focused run; template read shows Review queue/history fragments remain in place. | aligned_source | Verify selected-count buttons and warnings in browser using staged actions from cleanup pages. | Pending authenticated staging + Review check. |
| Review: no export-like affordances | No visible export, CSV, snapshot, download controls. | Focused tests/source scans cover no workspace export affordances. | aligned_source | Confirm in authenticated browser. | Automated/source evidence; human confirmation pending. |
| Analytics: filters/progress | One-account analytics, Time period and Group By filters; loaded denominator/percent shown only when reliable; otherwise loaded-count wording. | Template shows `Mail is still loading` conditional on `syncActive`; Build Notes support denominator honesty. | aligned_source | Verify active/idle sync banner behavior and no placeholder whitespace. | Pending authenticated runtime state check. |
| Analytics: no export/snapshot/download | No visible export, CSV, snapshot, download affordances. | Source scans show no visible page controls; CSS may retain legacy download classes but templates do not render them. | aligned_source | Confirm page visually and DOM/source in browser. | Automated/source evidence; human confirmation pending. |
| Dialogs and keyboard | Centered dialogs, concise copy, validation errors near field/action, keyboard traversal/focus rings intact. | Source uses `<dialog>` and compact copy; tests do not prove real keyboard traversal. | human_needed | Tab through rail, controls, category rows, dialogs, Review actions, and Bulk Unsubscribe modal. | Pending authenticated browser check. |
| 200% zoom | All five workspace pages usable at 200% zoom without page-level horizontal scrolling. | Not automatable in current run. | human_needed | Browser zoom 200%, inspect all pages. | Pending authenticated browser check. |
| 375x812 viewport | All five workspace pages usable at 375x812 with 44px controls and no page-level horizontal scrolling. | Source/CSS has responsive rules and tests cover structural expectations; no real viewport pass in this run. | human_needed | Browser viewport 375x812, inspect all pages. | Pending authenticated browser check. |

## Manual Procedure To Close UX-15

1. Start local dependencies only, for example `docker compose up -d db` if Docker is available.
2. Start the app locally with Java 21 and existing local environment values.
3. Verify `curl -fsS http://localhost:8080/actuator/health` reports `"status":"UP"`.
4. Open `http://localhost:8080/login` and authenticate with an owned account.
5. Walk the shared rail and all five pages: Bulk Archive/Delete, Unread Only, Bulk Unsubscribe, Review, Analytics.
6. Repeat visual checks at desktop, 375x812, and 200% zoom.
7. Stage at least one safe non-delete cleanup action and one delete-path dry staging through acknowledgement when suitable fixture data exists.
8. Confirm row/button feedback, exact Review action, immutable count/hash, delete warning, protected sender behavior, CSRF/account-owned session, and no provider mutation before approval.
9. Search visible UI and DOM for export, CSV, snapshot, download, or equivalent export-like affordances.
10. Update the matrix above with observed current behavior and mark each row `aligned`, `polish`, `must-fix`, or `blocker`.

## Current Closure State

UX-09 through UX-15 are closed. Automated/source evidence covers UX-09 through
UX-14, while Phase 17 authenticated walkthroughs and the user's 2026-08-13
acceptance close UX-15.
