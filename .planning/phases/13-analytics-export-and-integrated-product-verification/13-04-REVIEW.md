---
status: resolved
phase: 13-analytics-export-and-integrated-product-verification
plan: "04"
depth: deep
files_reviewed: 42
finding_counts:
  critical: 0
  warning: 7
  info: 0
  total: 7
disposition_counts:
  resolved: 7
  open: 0
---

# Plan 13-04 Independent Deep Review

## Findings

### WR-01: Headline action drilldowns do not preserve the displayed metric predicate - RESOLVED

The dashboard computes Archive, Trash, Mark read, and Unsubscribe cards from
`snapshot(..., query.action())`, so a card outside the selected action is zero
(`src/main/java/com/tidymail/service/AnalyticsService.java:390-436`;
`src/main/java/com/tidymail/repository/AnalyticsLedgerRepository.java:51-103`).
`AnalyticsLinkService`, however, replaces the selected action with the card action
before creating the source URL
(`src/main/java/com/tidymail/service/AnalyticsLinkService.java:74-81,106-121`).
For example, an `action=trash` dashboard can display Archived `0` while its source
link changes to `action=archive` and returns archive rows.

Even for `action=all`, these links use the `cleaned` series. Headline archive/trash/
read values are successful target counts, while `cleaned_facts` ranks once per
account/message and is a distinct-message series
(`src/main/java/com/tidymail/repository/AnalyticsLedgerSql.java:82-106`). Repeated
successful targets can therefore make the card value differ from its source total.
Unsubscribe has the same target-versus-message mismatch.

Add source predicates whose unit and operation kind exactly match each headline
metric, and intersect rather than replace an active action filter. Test every card
under `all`, matching, and nonmatching action filters with duplicate target fixtures.

### WR-02: Efficiency, delay, and comparison values have no exact inspection path - RESOLVED

The plan requires links for every metric, distribution, and comparison, but
`detailUrls()` only supplies headline, outcome, and coverage links
(`src/main/java/com/tidymail/service/AnalyticsLinkService.java:70-103`). Inbox
reduction, cleanup rate, median delay, confidence retention, all five delay buckets,
and prior comparisons render values without source links
(`src/main/resources/templates/fragments/analytics-results-frag.html:137-180`).
This leaves represented analytics numbers that cannot satisfy the locked
aggregate/source equality or JavaScript-disabled inspection contract.

Define exact source series for each numerator, denominator, delay bucket/sample, and
prior predicate, then render keyboard-reachable links and equality tests.

### WR-03: The Unsubscribed card reports unrelated and double-counted pending work - RESOLVED

The card renders `outcomes.accepted + outcomes.verificationPending` as
`Accepted or verification pending`
(`src/main/resources/templates/fragments/analytics-results-frag.html:45-47`).
Those outcome counts cover the selected action predicate, which is all action types
when `action=all`, rather than unsubscribe attempts specifically. Accepted is also an
overlapping measure and may already include verification-pending or succeeded
targets (`src/main/java/com/tidymail/repository/AnalyticsLedgerRepository.java:106-150`).
The sum can therefore include non-unsubscribe work and double count the same target.

Use a dedicated unsubscribe accepted-or-pending union from linked unsubscribe facts,
with the same range/account predicate as the confirmed count.

### WR-04: Stale sender locators fail closed only on the HTML route - RESOLVED

The key is AES-256-GCM encrypted, owner/account/range bound, canonical, and expiring,
and the shared SQL correctly intersects account, sender, and half-open receipt range
(`src/main/java/com/tidymail/service/AnalyticsDrilldownKeyService.java:50-146`;
`src/main/java/com/tidymail/repository/DecisionSurfaceSql.java:37-59`). The HTML
controller additionally rejects a valid token whose sender no longer exists
(`src/main/java/com/tidymail/controller/WorkspaceController.java:289-296`).

The CSV route resolves the token and ownership but accepts a zero-row result as an
empty download (`src/main/java/com/tidymail/controller/ExportController.java:109-125`).
Direct selection preview likewise resolves account ownership but lets zero
materialization become an EMPTY preview
(`src/main/java/com/tidymail/controller/SelectionPreviewController.java:70-85`;
`src/main/java/com/tidymail/service/SelectionPreviewTransactionService.java:112-120`).
Thus the same stale locator has route-dependent non-enumerating behavior instead of
one bounded 404.

Centralize locator resolution, ownership, and sender-existence validation for HTML,
CSV, and preview. Extend tests beyond tamper/owner/expiry to stale sender, account
mismatch, canonical range binding, and all three consumers.

### WR-05: Unsubscribe source availability can overclaim complete evidence - RESOLVED

The dashboard marks unsubscribe metrics partial when linked-outcome gaps exist
(`src/main/java/com/tidymail/service/AnalyticsService.java:413-436`). Source
availability for `UNSUB_CONFIRMED` and `UNSUB_ELIGIBLE` takes the generic detail
branch and checks only ledger/legacy gaps
(`src/main/java/com/tidymail/service/AnalyticsService.java:207-232`), so the linked
source page and CSV can claim AVAILABLE for the same predicate that the originating
metric marks PARTIAL.

Apply unsubscribe-linkage availability to both unsubscribe detail series and verify
HTML/CSV availability parity with a linkage-gap fixture.

### WR-06: The declared HTMX focus and live-region behavior is not functional - RESOLVED

No workspace Analytics page loads a local HTMX script; the only HTMX script tags in
the repository are remote tags on older pages. The Analytics form's `hx-*`
attributes are therefore inert. If HTMX is supplied externally, the handler uses
`after-request`, focuses the old heading, and then `outerHTML` replacement removes
that focused node (`src/main/resources/templates/analytics.html:30-36`).

The entire interactive dashboard is also `role="status"` and `aria-live="polite"`
(`src/main/resources/templates/fragments/analytics-results-frag.html:4-6`), causing a
large replacement, including links and tables, to be treated as one status update.
Tests only scan for attribute strings and do not exercise focus after a swap
(`src/test/java/com/tidymail/Phase13WorkspaceResponsiveTest.java:59-71`).

Either keep the full-page baseline and remove the inert enhancement, or bundle the
local dependency, focus the new heading on `after-swap`, and use a small dedicated
polite announcement region outside the interactive result content.

### WR-07: Narrow-screen CSS enforces 44px height but not 44px target width - RESOLVED

At `max-width: 720px`, the broad interactive rule sets only `min-height: 44px`
(`src/main/resources/static/css/workspace.css:1041-1063`). Short links such as the
six outcome `Source` links have no padding or `min-width`, so they are not guaranteed
to meet the plan's 44-by-44 target requirement
(`src/main/resources/templates/fragments/analytics-results-frag.html:126-133`).
Several shared page links have the same issue. The responsive test merely searches
for any `min-height: 44px` and explicitly accepts global `overflow-x: hidden`; it
does not prove target boxes, body `scrollWidth`, clipping, focus visibility, or 200%
zoom (`src/test/java/com/tidymail/Phase13WorkspaceResponsiveTest.java:74-87`;
`src/main/resources/static/css/workspace.css:35-39`).

Give narrow interactive elements both minimum dimensions (or equivalent padding)
and add a browser-level 375px/200% test that measures bounding boxes, body overflow,
and focused-element visibility. Global overflow hiding should not substitute for
containment.

## Resolution

- **WR-01/WR-02:** Added typed target, inverse, retained-outcome, cleaned-triaged,
  delay-sample, delay-bucket, and prior-period source predicates. Links preserve the
  active action filter, and duplicate target fixtures prove aggregate/page/stream
  equality for matching and nonmatching filters.
- **WR-03:** Replaced outcome arithmetic with one distinct linked unsubscribe target
  union for `ACCEPTED` or `VERIFICATION_PENDING`.
- **WR-04:** Added `AnalyticsSenderScopeResolver` as the shared key, ownership,
  account, canonical range, and sender-existence gate for HTML, CSV, and preview.
  Stale locators now return the same bounded 404 on all three consumers.
- **WR-05:** Unsubscribe headline and completion source series now combine the same
  linkage-gap availability used by the dashboard. The linkage-gap fixture verifies
  source HTML and export gating share that state.
- **WR-06:** Kept the functional JavaScript-independent full-page GET baseline and
  removed inert HTMX attributes plus the oversized dashboard live region.
- **WR-07:** Narrow interactive elements now have `min-width` and `min-height` of
  44px. Global horizontal clipping was removed; tests require local scroll
  containment. Real 375px/200% visual judgment remains assigned to Plan 13-06.

Focused verification passed 49 tests. The complete OpenJDK 21.0.12 suite passed
294 tests with PostgreSQL 16.14 Testcontainers through Colima. No provider calls,
privacy regressions, migration changes, or commits were introduced.

## Verified

- AES-GCM uses a random 96-bit nonce, 128-bit tag, versioned AAD, bounded canonical
  binary payload, opaque URL-safe encoding, owner fingerprint, account ID, exact
  instants, expiry, and one safe 404 copy.
- Sender HTML, CSV, and selection materialization share `DecisionSurfaceSql`; the
  account, normalized sender, and half-open `received_at` predicates remain in
  `base_rows`, including action preview materialization.
- Ownership is re-resolved for HTML, CSV, and preview. Foreign account selectors and
  owner-swapped tokens fail closed.
- Source HTML and CSV use the same repository count/page/stream predicates and stable
  time/internal-ID ordering. Internal IDs and raw sender values are absent from
  analytics URLs.
- All five export controls are real authenticated GET routes and preserve typed
  current-filter state. Export GETs and analytics GETs do not call a mail provider or
  unsubscribe transport in the focused integration coverage.
- No `href="#"`, phase placeholder, remote Analytics dependency, unsupported Delete
  label, or click-only control was found in the reviewed workspace templates.
- Templates provide viewport metadata, skip links, one `main`/`h1`, labels, table
  captions/scoped headers, local table overflow regions, visible focus CSS,
  non-color series labels, and reduced-motion rules.
- `git diff --check` passed. Production, test, review, and summary files were updated;
  no commit was created.

## Verification

- Focused Maven suites passed: 49 tests, 0 failures, 0 errors, 0 skipped:
  `AnalyticsDrilldownKeyServiceTest`, `Phase13AnalyticsWebTest`,
  `Phase13AnalyticsRepositoryTest`, `Phase13WorkspaceResponsiveTest`,
  `Phase13ExportTest`, and `Phase10SenderUnreadWebTest`.
- Full Maven suite passed: 294 tests, 0 failures, 0 errors, 0 skipped under explicit
  Homebrew OpenJDK 21.0.12. Testcontainers used Colima and PostgreSQL 16.14.
- No browser-level 375px, 200% zoom, keyboard, or screen-reader test was run; those
  remain part of the blocking Plan 13-06 UAT.
