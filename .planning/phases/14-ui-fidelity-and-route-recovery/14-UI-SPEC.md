# Phase 14 - UI Design Contract

## Purpose

Converge the authenticated TidyMail workspace on the current Design Canvas contract
after restoring route availability. This contract supersedes the Phase 9 top-navigation
shell while retaining the safety and data-integrity rules added through Phase 13.

## Visual Source Of Truth

1. Use only `tidymail-ux-rehaul-draft/tidymail-ux-rehaul-draft.design`.
2. Its `Bulk Archive/Delete`, `Unread Only`, `Bulk Unsubscribe`, `Review`, and
   `Analytics` pages control composition, density, hierarchy, and interaction placement.
3. Its `Build Notes` page is the implementation contract for product behavior and
   conditional capability rules.
4. Do not use uploaded or exported HTML, older TidyMail mocks, other chats, the
   `tidymail-ux-rehaul` project, or another Design Canvas project.
5. Existing `workspace.css` tokens may supply base palette and spacing only where they
   do not conflict with the authoritative `.design` file or its Build Notes.

## Shared App Shell

### Desktop

- One 248px left rail owns brand, account selector, route navigation, queued-action status, logout menu, Feedback, and collapse control.
- Main content uses `minmax(0, 1fr)` and a route-specific maximum width between 1060px and 1180px.
- Do not render a second horizontal primary navigation or account label above page content.
- The account selector remains exclusively in the rail and truncates long addresses.
- Rail footer remains pinned to the bottom when viewport height allows.

### Compact And Narrow

- At 980px and below, the rail becomes static above the content without duplicating controls.
- At 720px and below, navigation remains keyboard reachable, controls meet 44px targets, and tables/cards stay within viewport width.
- At 375x812 and 320px stress widths, no document-level horizontal scrolling is allowed.
- At 200% zoom, route navigation, account switching, logout, and primary page actions remain available.

## Tokens

| Token | Value | Use |
|---|---|---|
| `--tm-bg` | `#f4f6fb` | App background |
| `--tm-surface` | `#ffffff` | Cards and controls |
| `--tm-primary` | `#3f5cff` | Active navigation and focus |
| `--tm-border` | `#d9e0ee` | Dividers and control borders |
| `--tm-attention` | `#ea580c` | High-attention row actions |
| `--tm-danger` | existing danger token | Trash and purge warnings |
| `--shell-rail` | `248px` | Desktop navigation rail |
| `--content-max` | `1180px` | General content maximum |

Use only `4, 8, 16, 24, 32, 48, 64px` for spacing. Shell widths, breakpoints,
chart dimensions, and 6/10/16px radii are dimensional constraints rather than
spacing tokens.

Color distribution is approximately 60% neutral background/surface, 30% text,
borders, and secondary structure, and 10% primary/attention/danger accents.

## Typography

- Page titles and primary metric values: 30px.
- Section titles and compact metric values: 24px.
- Category titles and emphasized controls: 16px.
- Body, metadata, helper copy, and controls: 14px.
- Use only regular and semibold weights.
- Use the existing Inter/system stack.

## Shared States

- Loading banner appears only while a real sync/index job is active and links to Analytics.
- Empty states retain the page shell and use route-specific copy:
  - Senders: `No sender activity is indexed yet.` / `View Analytics`
  - Unread: `No unread mail is indexed yet.` / `View Senders`
  - Unsubscribe: `No unsubscribe candidates match these settings.` / `Reset filters`
  - Review: `No actions are waiting for review.` / `Back to cleanup`
  - Analytics: `No activity is available for this period.` / `Choose another period`
- Data-access errors retain the shell and route heading, never expose SQL/provider details,
  and use `This data is temporarily unavailable.` plus a route-specific action:
  `Retry Senders`, `Retry Unread`, `Retry Unsubscribe`, `Retry Review`, or `Retry Analytics`.
- Partial-data states use `Showing partial indexed data. Results already loaded remain usable.`
- All HTMX interactions have a full-page or native HTML fallback.

## Senders

- Focal element: the closed category summary list below the two page controls.
- Exactly two primary controls: Settings and Expand all/Collapse all.
- Categories are closed by default.
- Category headers show title, description, sender count, and optional reliable/estimated size.
- Stable category keys map to accent stripe and soft gradient variants; unknown keys use a neutral fallback.
- Expanded rows preserve exact-scope preview before action execution.
- Other supports selection and centered move/create-category dialogs.

## Unread

- Focal element: the category summary list, with Direct mail volume emphasized.
- Uses the same quiet category structure and controls as Senders.
- Lists/automated, older-than-30-days, direct-mail, attachments, and Other have distinct accents.
- Direct-mail Change type carries the exact sender address into the organize request.
- Unavailable attachment classification is shown as an honest neutral state.
- Per-sender actions preserve exact materialization.

## Unsubscribe

- Focal element: the ranked sender candidate list after the compact filter row.
- Top controls are compact category, time, search, and Settings triggers.
- Candidate rows show sender/domain, email count, read percentage, primary action, and overflow menu.
- View stats is a real URL enhanced into a centered modal by HTMX.
- Inspection and execution remain separate. No success is claimed before an actual outcome.
- Trash wording is explicit; row and confirmation copy do not imply permanent immediate deletion.

## Review

- Focal element: the newest eligible queued action in the LIFO ledger.
- Queued/History views remain available, with queued as the primary ledger.
- Top Execute/Undo controls reflect selected count.
- Rows show high-attention Undo plus an ellipsis menu containing View Emails and Execute.
- View Emails opens an enhanced modal but returns a full shell on normal navigation.
- Delete confirmation separately states that Gmail Trash recovery is generally limited to 30 days.
- Internal LIFO fields remain hidden from users.

## Analytics

- Focal element: the selected metric trend chart immediately below the metric tabs.
- Header exposes CSV and sanitized snapshot downloads.
- Only two compact primary filters appear initially: Time period and Group By.
- Received, Sent, Read, and Archived/Deleted metrics act as chart tabs.
- The selected trend is represented by responsive CSS/HTML bars with exact values retained in an accessible table.
- Top Received and Top Sent are compact, maximum-10-row two-column tables.
- Size cards and TidyMail Archived vs Deleted bars respect the selected period/grouping.
- Existing exact source data remains accessible without making the full table the dominant visual.

## Copywriting

- Use `Move to trash` or clearly pair `Delete` with Trash/recovery copy.
- Required destructive warning: messages moved to Gmail Trash can generally be restored for up to 30 days before permanent deletion.
- Required purge confirmation: `Delete every connected account and all TidyMail data, then log out? This permanently removes provider tokens, indexed metadata, sender statistics, queued actions, history, analytics, logs, and settings.`
- Avoid implementation notes, fake percentages, unsupported totals, and guaranteed unsubscribe language.
- Keep metadata-only privacy claims precise.

## Accessibility

- Preserve skip links, semantic landmarks, visible focus, `aria-current`, labeled dialogs, and keyboard dismissal.
- Every ellipsis or icon-only control has an accessible name describing its row and purpose,
  such as `More actions for Example Sender`; visual glyphs remain `aria-hidden`.
- Visual charts have adjacent semantic values and do not use `role="img"` for plain unencoded numbers.
- Color accents never carry category meaning alone.
- Respect reduced motion and browser zoom.

## UI Considerations

| Surface | State coverage |
|---|---|
| Shared rail | Zero/one/many owned accounts, long address, active route, queue unavailable, narrow/zoom |
| Category groups | Empty, one, many, unknown key, unavailable metric, expanded/collapsed |
| Route data | Loading, empty, partial, unavailable, retry, populated |
| Dialog actions | Native fallback, HTMX enhancement, stale input, validation error, success |
| Analytics | Sparse/dense buckets, zero values, unavailable metric, exact-table fallback |
| Destructive actions | Standard confirmation, separate Trash warning, stale scope, protected target |

## Verification

- Automated tests assert the two production-data query defects and all represented fallback links.
- Template tests assert rail-only account selection and absence of horizontal route navigation.
- Browser verification covers all five authenticated routes at desktop, 980px, 720px, 375x812, 320px, keyboard-only, and 200% zoom.
- Exact-ID, LIFO, protected-sender, purge, and privacy tests remain green.
