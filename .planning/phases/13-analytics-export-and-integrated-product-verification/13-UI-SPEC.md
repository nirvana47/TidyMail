---
phase: 13-analytics-export-and-integrated-product-verification
status: approved-for-planning
reference: tidymail-ux-rehaul/pages/analytics-compact-suite.html
---

# Phase 13: Analytics, Export, And Integrated Verification - UI Specification

## Design Intent

Analytics is an evidence surface, not a promotional dashboard. It must answer:

- Which owned accounts and UTC dates are represented?
- What was received, decided, cleaned, confirmed, failed, or left uncertain?
- What integer numerator and denominator produced each rate?
- Can the user inspect exactly the rows behind a bucket or metric?
- Is history complete enough to support the number?
- Can the current view be exported without leaking private or credential data?

The compact analytics mock guides composition, density, and hierarchy. Its sample
numbers, remote dependencies, inert controls, `href="#"`, and unsupported wording are
not implementation truth.

## Visual System

Reuse the local Phase 9-12 `workspace.css`, system font, shell, spacing, borders, and
status tokens. Add no remote font, icon, chart, CSS, or JavaScript dependency.

| Meaning | Treatment |
|---|---|
| Received/baseline | info token plus visible `Received` label |
| Cleaned/succeeded | success token plus visible `Cleaned` label |
| Partial/pending | warning token plus explicit text |
| Failed | danger token plus explicit text |
| Unavailable/legacy | neutral patterned/outlined treatment plus reason |

Color is never the only distinction. Visual bars have adjacent exact text.

Type scale remains compact:

- route `h1`: 22px;
- section heading: 14px;
- metric value: 20px;
- body/table: 12px;
- metadata/help: 11px;
- eyebrow/column labels: 10px.

## Page Composition

1. Shared workspace navigation with Analytics `aria-current="page"`.
2. Shared account/queue/sync shell state.
3. Route heading and real Export/Snapshot actions.
4. Canonical filter panel.
5. coverage/availability notice.
6. headline metric grid.
7. received-versus-cleaned trend and semantic data table.
8. top senders and action outcomes.
9. efficiency metrics and triage-time distribution.
10. review/cleanup/unsubscribe coverage.
11. source-row result region when a drilldown is active.
12. privacy note and route footer.

Exactly one `main` and one `h1` are rendered.

## Header Contract

- Eyebrow: `Mailbox evidence`
- `h1`: `Cleanup analytics`
- Purpose: `Measure indexed mail and durable cleanup outcomes without inferring
  missing history.`

Actions are real anchor downloads:

- `Export current analytics as CSV`
- `Download sanitized snapshot`

Both preserve the complete normalized filter state. They are absent only when the
server cannot authorize a source; they are never disabled placeholders. Help text:

`Exports use the same server filters shown below. The snapshot omits account and
sender identities.`

## Filter Contract

Use a GET form with visible labels:

- `From date`
- `To date`
- `Account`
- `Action`
- `Group trend by`
- `Comparison`

Options:

```text
Action: All actions, Archive, Trash, Mark read, Unsubscribe, Keep, Protect, Review
Grouping: Day, Week (Monday start), Month
Comparison: Previous equivalent period, No comparison
```

The panel includes:

- `Apply analytics filters`
- real `Clear filters` link to canonical defaults;
- persistent text `Dates use UTC`;
- selected range summary, for example `Aug 1-30, 2026 UTC (30 dates)`;
- received-baseline notice when an action filter is active.

At narrow widths the panel becomes a `<details>` element labeled `Analytics filters`
and retains the current state in its summary. Applying filters uses full-page GET as
the baseline; HTMX may enhance only the analytics results region.

Invalid/duplicate/foreign/overlarge state renders an alert with no broad fallback:

`These analytics filters are invalid. Review the dates and owned account selection.`

## Historical Coverage Banner

Render one banner before metrics:

- Available: `Indexed mail is reliable from {date}; action detail is reliable from
  {date}.`
- Partial: `Some values are partial. {N} legacy actions do not have per-target
  outcome detail.`
- Running/stale: `Sync is {state}; received and cohort values may change.`
- Unavailable: `This range predates indexed history. Missing values are not estimated.`

For all-account scope, state the conservative common boundary. The banner links to
safe details, not raw sync errors.

## Headline Metrics

Use a responsive metric grid. Required cards:

1. Received
2. Triaged
3. Archived
4. Trashed
5. Marked read
6. Unsubscribed

Each card contains:

- metric label;
- value, `Partial`, or `Unavailable`;
- exact definition in help text;
- prior comparison when requested and available;
- source/history drilldown;
- denominator/sample text where applicable.

Example:

```text
Triaged
2,031
Distinct messages with a first durable decision in this range.
2,031 messages; up 8.2% vs the previous 30 dates.
View source rows
```

Unsubscribed copy is strict:

`Confirmed: {N}. Accepted or verification pending: {P}.`

Do not use the mock phrase `fully removed` unless a linked `CONFIRMED` event supports
it. If prior is zero:

`{delta} more than prior period; percent unavailable because prior was zero.`

## Received Versus Cleaned Trend

Heading: `Received vs cleaned volume`

Subheading:

`Received uses message receipt time. Cleaned uses affirmative cleanup outcome time.`

The visual may use paired CSS bars/lines, but it must have an adjacent semantic table:

| Period | Received | Cleaned | Availability | Source |
|---|---:|---:|---|---|

Every row and visual bucket links to the exact received or cleaned source predicate.
Zero buckets render `0` only when available. Partial buckets show a visible marker and
reason. The legend names both series in text.

On 375px, default to the semantic list/table and keep any visual in a locally
contained region. No page-level overflow. Bucket links are at least 44px high.

## Top Senders

Heading: `Senders by received volume`

Rows contain:

- rank;
- safe sender display/address or `Unknown sender`;
- owned account label when `account=all`;
- received count;
- percentage of received baseline with `N / D`;
- `Open sender queue` link when resolvable.

Ranking is per account; equal addresses in different accounts are separate. The link
uses an opaque server-owned drilldown key. Unknown sender has explanatory text and no
fake link.

`View all sender volume` opens a bounded analytics report, not a decorative control.

## Action Outcomes

Heading: `Cleanup actions by outcome`

Display a table/list by selected action type:

- Requested
- Accepted
- Succeeded
- Failed
- Verification pending
- Not dispatched

Visible arithmetic:

`{requested} requested = {succeeded} succeeded + {failed} failed +
{pending} verification pending + {notDispatched} not dispatched.
Accepted ({accepted}) may overlap succeeded or pending.`

Each outcome count links to exact analytics source rows or an exactly expressible
Review History filter. Do not link to a broader generic report.

## Efficiency Summary

Required cards:

### Inbox Reduction

```text
{net removals} / {received} = {percent}
Successful archive and trash outcomes minus confirmed inverse undo outcomes.
This event-window rate may exceed 100% when older mail is cleaned.
```

### Cleanup Rate

```text
{successfully cleaned distinct messages} / {triaged distinct messages} = {percent}
```

### Median Triage Delay

```text
{duration}
Median from receipt to first durable decision; sample n={eligible}.
{excluded} rows lacked a valid pair.
```

### Confidence Retention

```text
{non-pending requested targets} / {requested targets} = {percent}
Targets left verification-pending reduce this value.
```

Unavailable denominator cards show `Unavailable - no eligible rows` or the exact
coverage reason. They never display `0%`.

## Triage-Time Distribution

Heading: `Time to first decision`

Fixed buckets:

- Under 15 minutes
- 15 minutes to under 1 hour
- 1 hour to under 24 hours
- 1 day to under 7 days
- 7 days or more

Each row shows count, denominator, percentage, and a source link. Bucket counts must
sum to the displayed eligible sample. Excluded null/negative pairs are shown below.

## Coverage Section

Heading: `Review and cleanup coverage`

Three rows:

```text
Review coverage: staged executed targets / all executed targets
Cleanup coverage: received-cohort messages with a decision / received cohort
Unsubscribe completion: confirmed initiated targets / eligible initiated targets
```

Each row displays `N / D`, one-decimal percentage, definition, availability, and exact
drilldown. `D=0` is unavailable. Manual-required-only unsubscribe candidates are shown
as an excluded count.

## Source Rows

`/analytics/source` is a full server-rendered region/page with:

- heading naming metric/series and exact UTC bucket/range;
- normalized filter summary;
- source total equal to the originating aggregate;
- fixed 25-row pages with stable links;
- CSV export for this analytics source predicate through `analytics.csv`;
- `Back to analytics` preserving state and focus target.

Safe row fields:

- received or event time;
- account label;
- safe sender/target label;
- action and outcome where applicable;
- bounded safe reason;
- safe provider deep link only when already authorized and constructible.

Never render provider IDs, scope/action database IDs, fingerprints, raw
unsubscribe/header values, subject/body/attachment content, tokens, or raw errors.

Empty source state:

`No source rows match this metric and filter. The aggregate should be zero; refresh
analytics if it is not.`

An aggregate/source mismatch is an error, not an empty success.

## Export UX

Each required page has one plainly named anchor:

- Senders: `Export filtered senders as CSV`
- Unread: `Export filtered unread as CSV`
- Unsubscribe: `Export filtered unsubscribe candidates as CSV`
- Review History: `Export filtered history as CSV`
- Analytics: `Export current analytics as CSV`

The link preserves all current server filters and account scope. Export is not added
to Review Queued because EXPT-01 specifically requires Review history.

Over-cap response remains in the workspace shell:

`This export has {N} rows and exceeds the {cap}-row limit. Narrow the filters and try
again. No partial file was generated.`

Snapshot action copy:

`Download a sanitized JSON summary without account names, sender identities, source
rows, message content, headers, or credentials.`

## Shared Five-Page Completion Audit

The implementation must audit Senders, Unread, Unsubscribe, Review, and Analytics:

- every visible filter changes a server predicate;
- every action/link has a real destination or POST fallback;
- every export uses current server state;
- no disabled `available in Phase...` placeholder remains;
- no `href="#"`, click-only element, fake sample metric, or unsupported control;
- desktop labels remain available to narrow/card layouts;
- all touch targets are 44 by 44 CSS pixels;
- no page-level horizontal scroll at 375px or 200% zoom.

## State Matrix

| State | Required presentation |
|---|---|
| Loading | Shared sync state; no fake metric skeleton values |
| Empty indexed mailbox | Available zero received; action metrics based on ledger; sync guidance |
| Zero filtered result | `0` with active-filter summary and Clear filters |
| Partial metadata | Partial banner; affected received/cohort cards and buckets marked |
| Legacy action detail | Action metrics partial with excluded legacy count |
| Stale metadata | Stale banner and sync action; existing durable outcomes remain |
| Unavailable history | `Unavailable`, reason, coverage boundary; no zero |
| Error | Alert, safe retry, no raw provider/database text |
| Export over cap | 422 workspace error; no attachment/partial output |
| Snapshot available | Attachment plus explicit sanitization copy |
| Comparison unavailable | Current value remains; prior percentage omitted with reason |

## Accessibility Contract

- Skip link lands on `main`.
- Form controls have programmatic labels; related action/group/compare controls use
  fieldsets where appropriate.
- Metric definitions use visible text or `aria-describedby`, not title-only tooltips.
- Chart data is fully represented in semantic text/table form.
- Table headers use scope/captions; card conversions retain field labels.
- Current nav/view uses `aria-current`; status updates use `role=status`; errors use
  `role=alert`.
- Dynamic source results move focus to the result heading or announce completion.
- Keyboard order follows visual order; no hover-only affordance.
- Focus ring meets contrast; reduced-motion is honored.
- Status, series, and comparison direction never rely on color or icon alone.
- Download links identify file type and current-filter behavior.

## Responsive Contract

At 375px:

- top navigation follows the established responsive shell;
- filter panel is collapsible with state summary;
- metric cards use one column or two columns only where 44px targets and readable
  values remain;
- side-by-side analytics panels stack;
- charts prioritize text/list representation;
- tables become labeled cards or an explicitly labeled local scroll region;
- long sender/account labels wrap without widening the page;
- download actions stack full width;
- sticky elements do not cover focused controls.

Manual testing must also cover 320px stress inspection, 200% zoom, and desktop widths,
but QUAL-02 gates on 375px.

## Copywriting Contract

| Situation | Canonical copy |
|---|---|
| UTC | `Dates use UTC.` |
| Partial | `Some values are partial; missing history is not estimated.` |
| Unavailable | `Unavailable - this fact is not recorded for the selected range.` |
| Prior zero | `Percent comparison unavailable because the prior value was zero.` |
| Accepted unsubscribe | `Accepted; future mail is not independently confirmed.` |
| Confirmed unsubscribe | `Confirmed by a recorded completion event.` |
| Empty denominator | `Unavailable - no eligible rows.` |
| Action-filter baseline | `Received remains the all-mail baseline for the selected account and dates.` |
| Privacy | `Bodies and attachments are never indexed. Exports use approved metadata only.` |
| Snapshot privacy | `Account and sender identities are omitted from this snapshot.` |

## UI Considerations

| Surface | Consideration | Resolution |
|---|---|---|
| Filter form | Invalid, duplicate, foreign, zero/one/many accounts | Covered by typed fail-closed state and canonical summary |
| Metric cards | loading/zero/partial/unavailable/error/prior-zero | Covered by state matrix and canonical copy |
| Trend | zero/partial buckets, keyboard, color, narrow width | Semantic table/list is authoritative and responsive |
| Sender list | missing sender, duplicate across accounts, long text | Unknown state, per-account rows, wrapping |
| Outcome table | accepted overlap and partition arithmetic | Explicit equation and separate accepted explanation |
| Source rows | empty/mismatch/pagination/privacy | Exact count gate, stable pages, safe allowlist |
| Downloads | over cap, failure, private values | 422 no partial file and output allowlists |
| Five-page shell | 375px/zoom/touch/focus | Automated contract plus blocking manual UAT |

## Acceptance Summary

The UI contract is complete only when every mock-implied control is functional or
removed, every metric exposes its provenance and denominator, source rows equal their
aggregate, unavailable history is explicit, all outputs are privacy allowlisted, and
all five pages pass the localhost responsive/accessibility UAT before deployment.
