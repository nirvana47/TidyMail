---
created: 2026-08-11T18:35:58.945Z
updated: 2026-08-13T12:43:38.453Z
title: Rebuild workspace UI using Inbox Zero patterns
area: ui
severity: blocker
status: completed
resolved_by: Phases 16 and 17
completed: 2026-08-13
files:
  - tidymail-ux-rehaul-draft/tidymail-ux-rehaul-draft.design
  - src/main/resources/templates/fragments/workspace-rail.html
  - src/main/resources/templates/fragments/senders-results-frag.html
  - src/main/resources/templates/fragments/unread-results-frag.html
  - src/main/resources/templates/fragments/unsubscribe-results-frag.html
  - src/main/resources/templates/fragments/selection-preview-frag.html
  - src/main/resources/templates/senders.html
  - src/main/resources/templates/selection-preview.html
  - src/main/resources/templates/unread.html
  - src/main/resources/templates/unsubscribe.html
  - src/main/resources/templates/fragments/review-history-frag.html
  - src/main/resources/templates/fragments/review-queue-frag.html
  - src/main/resources/static/js/cleanup-categories.js
  - tidymail-ux-rehaul-draft/pages/unsubscribe-compact-suite.html:2449-2505
  - src/main/resources/templates/review.html
  - src/main/resources/templates/analytics.html
  - src/main/resources/static/css/workspace.css
  - src/main/java/com/tidymail/service/WorkspaceModelService.java
  - src/main/java/com/tidymail/service/SenderDecisionQueryService.java
  - src/main/java/com/tidymail/service/SenderCategoryService.java
  - src/main/java/com/tidymail/service/DecisionClassifier.java
  - src/main/java/com/tidymail/service/DecisionRuleSet.java
  - src/main/java/com/tidymail/service/SelectionMaterializationService.java
  - src/main/java/com/tidymail/service/ActionCommandService.java
  - src/main/java/com/tidymail/service/UnreadDecisionQueryService.java
  - src/main/java/com/tidymail/service/UnsubscribeCandidateService.java
  - src/main/java/com/tidymail/repository/DecisionSurfaceQueryRepository.java
  - src/main/java/com/tidymail/repository/MessageMetadataRepository.java
  - src/main/java/com/tidymail/entity/SenderCategoryAssignment.java
  - src/main/java/com/tidymail/entity/MessageMetadata.java
---

## Current Blocker Feedback

Phase 15/16 authenticated UAT found that the shared workspace and Analytics
surfaces still need a coherent visual redesign. This feedback is the controlling
UI direction for this todo:

1. Vertically center row text with its checkbox across Bulk Archive/Delete,
   Unread Only, and related cleanup pages. Prevent sender names and other strong
   text from bleeding into adjacent columns at every supported width.
2. Put a checkbox beside every sender. When one or more senders are selected,
   reveal one contextual bulk-action bar between the filter controls and sender
   results. The bar must show the selected count, provide a clear-selection
   control, and expose the applicable actions such as Change type, Mark as Read,
   Archive, and Delete. Keep it hidden when nothing is selected.
3. Replace pill-shaped cleanup filters with a clean control group: a plain
   `Category` label above or beside a standard dropdown, with controls outside
   decorative pill containers.
4. Rework the rail account control to follow the Inbox Zero interaction:
   display one active account cleanly at the top and expose account switching
   through its toggle. Put logout, settings, and other utility actions behind a
   separate bottom utility toggle.
5. Strengthen sidebar typography so section headers are clearly distinct from
   navigation links and active page titles.
6. Simplify every workspace page header. Place the title and primary controls
   together and remove explanatory subtitles beneath headings, including copy
   such as `Open a category...`, `Tidy up your unread emails`, `Approve staged
   exact scopes...`, and `Approve selected or all eligible exact scopes...`.
   Use a compact help icon when backend semantics genuinely require explanation.
7. Give Analytics the same compact structure: title, two left-aligned filters,
   and one right-aligned action in a single control row.
8. Redesign Top Received and Top Sent as compact, matching panels. Their table
   content must begin at the same vertical position and columns must align
   horizontally across the pair.
9. Correct the Analytics chart by adding its x-axis label, showing counts on
   hover rather than permanently above bars, and using a softer blue gradient
   instead of solid bars.
10. Remove the unrequested exact-values table and unnecessary label-definition
    legend from Analytics.
11. Keep all four Analytics metric cards on one row at normal desktop widths.
    Responsive wrapping should occur only when the viewport genuinely requires
    it.
12. Apply this cleaner, lower-density visual system consistently across the
    workspace rather than patching each page independently.
13. Remove the Settings/default-action dependency from cleanup workflows. Users
    should choose the desired action from the contextual selection bar after
    selecting senders, rather than preconfiguring whether the page performs Mark
    as Read, Archive, or Delete.
14. Standardize action buttons across the application with the compact Inbox
    Zero treatment: medium corner radius, restrained borders and backgrounds,
    clear hover/focus/disabled states, and neither sharp nor pill-shaped corners.
15. Remove the large partial-indexed-data/workspace-state section from the Review
    Actions page. Its `Partial` banner, indexed/scanned/page/account summary, and
    prominent sync form interrupt the review workflow and are confusing in this
    location.
16. Preserve the Queue and History tabs on Review Actions, but present their
    records in the table format defined by the Review mock, with stable column
    alignment and compact row actions.
17. Keep the Review Actions hierarchy as clean as Inbox Zero: page header,
    immediately followed by Queue/History and any necessary compact filters,
    immediately followed by the corresponding data table. Do not insert status
    cards, explanatory panels, or duplicate descriptions between these layers.

The current UI is a blocker because these issues affect navigation hierarchy,
control comprehension, content alignment, and the primary Analytics workflow
across the authenticated product.

## Design Precedence

Inbox Zero examples referenced in the Phase 15/16 UAT are authoritative for the
account switcher, bottom utility menu, sidebar typography, page headers,
filters, contextual bulk-action bar, button styling, cleanup controls, metric
cards, ranked panels, chart treatment, and shared page-header density. These are
intentional deviations from the Phase 16 mock.

Use the current Design Canvas project in
`tidymail-ux-rehaul-draft/tidymail-ux-rehaul-draft.design` and its Build Notes
for product behavior, safety constraints, and visual decisions not superseded
by this feedback. The relevant pages are:

- Bulk Archive/Delete
- Unread Only
- Bulk Unsubscribe
- Review
- Analytics
- Build Notes

Do not use older or separate TidyMail mockups from other chats, exported HTML,
the `tidymail-ux-rehaul` project, or any other Design Canvas project.

## Prior Scope

The following earlier scope remains valid where it does not conflict with the
current blocker feedback or describe work already completed:

The authenticated workspace still differs from the intended navigation, naming, and
control hierarchy. Address these items together in a later remediation pass:

1. Make the TidyMail brand in the top-left rail a link to the workspace homepage.
   The current expected homepage is the Bulk Archive/Delete page.
2. Rename the left-rail `Senders` route to `Bulk Archive/Delete` so navigation matches
   the page title.
3. Rename `Unread` to `Unread Only`.
4. Rename `Unsubscribe` to `Bulk Unsubscribe`.
5. Remove the standalone `Queued actions: 0` rail row. Show the queued-action count
   beside the Review navigation label instead.
6. Show the total unread-email count beside the Unread Only navigation label.
7. Restyle the active-account selector so its padding, height, typography, and overall
   density match the rest of the rail. Display only the email address, without the
   provider suffix such as `(Gmail)`.
8. Remove the `Switch account` button. Selecting an existing account should immediately
   switch to it. Preserve `Add new account` as a dropdown option.
9. On Bulk Archive/Delete, place Settings and Expand all next to each other on one line,
   aligned to the right immediately above the category groupings.
10. Remove the white pill/container around the current action and page controls. The
    controls should not appear inside a separate toolbar card.
11. Remove all user-facing export functionality from every workspace page, not only
    Bulk Archive/Delete. Users must not be offered CSV or snapshot downloads.
12. Investigate why navigating to Bulk Unsubscribe is materially slow. Treat this as a
    backend/query-performance issue rather than masking it with UI behavior.
13. Correct the Bulk Action Settings copy:
    - Replace `Choose the action shown for every sender` with
      `Choose the default action`.
    - Replace `Bulk Archive`, `Bulk Delete`, and the incorrect `Mark as Red` copy with
      `Archive`, `Delete`, and `Mark as Read`.
14. Clicking Archive for a sender must not navigate to the separate Selection Preview
    page. For the MVP, the click should materialize the exact qualifying message IDs
    and stage that action directly in the Review queue. It must not execute the mailbox
    mutation immediately.
15. After staging succeeds, disable and visually mute that sender's Archive button so
    repeated clicks cannot enqueue the same action accidentally. Inline Undo may be
    considered later, but is explicitly out of scope for this MVP adjustment.
16. Standardize the sender-row column order as:
    sender identity, domain, message count, size, action. The message count must appear
    after the domain rather than before it.
17. Make the size column structurally consistent across every sender row. Individual
    values should show only the formatted size, without prefixing each value with
    `Estimated`. Put the qualification in the column heading as `Size (Estimated)`.
18. Do not render all senders in a category when its disclosure is opened. Initially
    load and show five sender rows per category.
19. After the first five rows, show a centered, subtle `Load more` control that does not
    overlap row content and does not use the blue primary-button treatment.
20. Each `Load more` request should fetch and reveal five additional rows. After it is
    used, expose both `Load more` and `Load all`; `Load all` may retrieve and show the
    complete remaining category. When the main section is collapsed, it should go back 
    to showing just 5 at a time with the `Load more` button UI. 
21. Category pagination must be backed by server-side/query-level bounded work, not
    merely hiding already-rendered rows in the browser. Opening a category should not
    precompute, transfer, or render all 178+ sender rows.
22. Show category-change controls and sender checkboxes in every Bulk Archive/Delete
    category, not only Other. Users need to correct misclassifications in Lists and
    newsletters, Automated notifications, Receipts and transactions, Personal senders,
    user-created categories, and future fallback categories.
23. Persist manual category corrections as user/account-scoped classification feedback.
    Subsequent indexing for the same user should apply those corrections to matching
    senders instead of repeatedly restoring the incorrect inferred category.
24. Investigate and correct the backend classification rules. Observed errors include:
    - personal one-to-one senders classified as Automated notifications;
    - personal senders classified as Receipts and transactions;
    - transactional senders classified as Lists and newsletters;
    - list/newsletter senders classified as transactional.
25. Use the cross-category sender checkboxes for bulk staging as well as recategorization.
    A user must be able to select several senders and stage Archive or Delete for all
    selected senders through a control at the top of the category/page.
26. Bulk staging must still materialize the exact qualifying provider message IDs for
    every selected sender before one or more Review items are created. Checkbox counts
    and category totals must never become execution scopes.
27. Verify the Unread Only backend predicate. The displayed total currently reports
    approximately 1,456 senders and 27,771 unread messages while Gmail's Inbox UI shows
    roughly 700. Determine whether TidyMail is counting unread messages from All Mail,
    including archived unread mail.
28. Unread Only must mean messages that are both in the provider Inbox and unread. It
    must not include archived unread messages from All Mail. Confirm the Gmail API
    supports this predicate and document a clear limitation if it does not.
29. Apply the Bulk Archive/Delete control contract to Unread Only:
    - no white toolbar pill;
    - Settings and Expand all aligned together at the right above categories;
    - matching Settings dialog copy and default-action labels;
    - the same five-row server-side pagination, Load more, Load all, and collapse reset;
    - sender checkboxes, bulk staging, consistent domain/count/size columns, and
      `Size (Estimated)` heading behavior.
30. Move the note `Unread groups are not mutually exclusive. A sender can appear in
    more than one group.` from the bottom to the top, before the unread category list.
31. Align Bulk Unsubscribe with the authoritative `Bulk Unsubscribe` page in
    `tidymail-ux-rehaul-draft/tidymail-ux-rehaul-draft.design` and the shared visual
    language of Bulk Archive/Delete and Unread Only. Restore that page's layout,
    controls, spacing, row hierarchy, and interaction placement rather than maintaining
    a third inconsistent cleanup-page pattern.

The feedback was gathered element-by-element from authenticated localhost. It covers
the rail brand, route labels, active-account select, Switch account button, queue
status, Bulk Archive/Delete toolbar, Settings/Expand all controls, export link,
Settings dialog, sender rows, Archive action, Selection Preview page, and category
disclosures. It now also covers manual classification correction, sender selection,
Unread Only semantics, and Bulk Unsubscribe mock fidelity.

## Solution

Implementation is intentionally deferred. In the later fix:

- Audit the available Inbox Zero codebase and referenced elements before
  implementation. Reuse its layout and interaction principles without copying
  unrelated product behavior or branding.
- Establish shared rail, page-header, filter-row, cleanup-row, pagination, and
  Analytics panel primitives, plus a shared contextual selection bar and button
  system, so alignment and responsive behavior remain consistent across pages.
- Remove subtitle paragraphs beneath workspace page titles. Put exceptional
  explanatory content behind an accessible help affordance rather than leaving
  persistent instructional prose in the main flow.
- Treat the Phase 15/16 UAT as authoritative wherever it intentionally differs
  from the `.design` mock.
- Verify the redesign through an authenticated desktop-width walkthrough and
  narrower responsive widths, including long sender names and all four
  Analytics metric cards.
- Treat the rail labels and page titles as one naming contract.
- Use `tidymail-ux-rehaul-draft/tidymail-ux-rehaul-draft.design` and Build Notes
  for decisions not superseded by the latest Inbox Zero-directed UAT.
- Keep account selection rail-only and make selection submit automatically.
- Remove export links, endpoints from discoverable UI, and related snapshot/download
  affordances across all five pages according to the global no-export requirement.
- Place Bulk Archive/Delete controls directly above categories without a card wrapper.
- Remove the Settings/default-action chooser. Keep the contextual action bar
  hidden until at least one sender is selected, then place it between filters
  and results with a selected-count summary and clear-selection control.
- Offer Change type, Mark as Read, Archive, and Delete in that bar only where
  each action is valid for the current cleanup page. Use concise action labels
  without misleading `Bulk` prefixes.
- Replace sender-row preview navigation with an exact-scope staged command and a
  disabled post-stage row state. Continue to materialize provider message IDs before
  creating the Review item; never stage or execute from an estimated count.
- Define one semantic sender table/grid contract with a `Size (Estimated)` heading,
  consistent cells for every row, and the requested domain/count ordering.
- Add per-category server-side pagination with a page size of five and separate
  bounded `Load more` and explicit `Load all` paths. Preserve stable ordering so rows
  cannot repeat or disappear between requests. Preserve the user-added requirement
  that collapsing a category resets it to the initial five-row view.
- Model manual classification corrections as durable, account-owned facts with an
  explicit precedence over inferred categories. Do not create a cross-user global
  learning rule from one user's correction.
- Add checkbox selection to every relevant category and reuse one exact-ID
  materialization path for individual and multi-sender staged actions. The
  contextual bar changes how actions are chosen, but must not execute directly
  from sender counts or weaken Review staging.
- Trace the Unread query from Gmail metadata ingestion through repository predicates.
  Require both provider Inbox membership and unread state, with tests distinguishing
  Inbox unread from archived unread.
- Reuse one cleanup-page component contract across Bulk Archive/Delete and Unread Only,
  then align Bulk Unsubscribe to the authoritative `.design` page without weakening
  its distinct unsubscribe safety and outcome requirements.
- Source Review and Unread rail counts from authorized server-side facts.
- Rebuild Review Actions around the retained Queue and History tabs and the
  mock-defined table. Remove the workspace-state panel from the primary Review
  flow and place compact controls directly before the table.
- Profile Bulk Unsubscribe navigation with production-shaped data to identify whether
  candidate aggregation, cleanup-history lookup, summary batching, or another query is
  responsible for the delay.
- Preserve exact-message-ID action safety, account authorization, LIFO review ordering,
  protected-sender handling, CSRF, privacy, and destructive-action warnings.

## Continuing Phase 15/16 Authenticated Feedback - 2026-08-13

The post-remediation UI is much better than before, but authenticated localhost
review still found functional blockers and UX polish gaps that must remain tracked
even if they are scheduled outside the already-executed Phase 16 remediation plans:

1. Bulk Unsubscribe sender stats must open as a modal, not navigate to a full
   page or `main` content route. The mock shows the desired modal pattern at
   `tidymail-ux-rehaul-draft/pages/unsubscribe-compact-suite.html:2449-2505`
   with `.stats-modal-overlay`, a dialog header, action row, bar chart, and
   email table. Inbox Zero also uses a pop-up pattern. The current stats view
   also takes a few seconds to render, so profile and fix the stats query/render
   path rather than only changing the presentation.
2. On Bulk Archive/Delete, the compact help `summary` control should sit beside
   the `Bulk Archive/Delete` page header itself, and the `Expand all` control
   should be on that same header line instead of separated lower in the page.
3. Remove the `Action` column heading on Bulk Archive/Delete and Unread Only if
   row actions are only exposed after checkbox selection through the contextual
   action bar. The grid should not advertise an always-visible action column
   that has no row content.
4. `Load more` must append additional sender rows as a continuation of the
   existing category data. It must not repeat the `Sender`, `Domain`, `Emails`,
   `Size (Estimated)`, and `Action` header row inside the loaded page fragment.
5. When the last sender is moved out of a category such as `Other`, the empty
   category should not show `Size pending sync`. Use a blank value or `0 KB`
   when there is nothing left to size.
6. Remove the category management helper block with `Select senders first, then
   choose where they belong.`, `Move selected`, and `Create category` when the
   actual action path is the contextual bar that appears at the top after
   selecting senders.
7. `Expand all` must expand every category. During review it did not expand
   `Automated notifications`, `Receipts and transactions`, or `Personal senders`.
   Test the global toggle against all category keys and against initially
   unloaded or zero-loaded categories.
8. Unread Only still appears to count unread messages from All Mail rather than
   Inbox unread. The user reported 7,930 unread messages in the actual Gmail
   Inbox at the time of feedback, but that number did not appear anywhere on
   the Unread Only page. Show the authoritative Inbox unread total either at the
   top of Unread Only below the category controls, or in the sidebar next to the
   `Unread Only` navigation label. Backend semantics must distinguish provider
   Inbox unread from archived unread.
9. Audit suspicious unread grouping data. `Has attachments` showed `0 senders`
   and `0 unread`, which is unlikely for the user's unread Inbox. Verify whether
   attachment metadata is ingested, indexed, and filtered correctly for unread
   groups.
10. The rail brand remains a logo/home affordance, but it should not look like
    a normal underlined hyperlink. Keep it clickable if it routes home, but style
    it as brand/navigation chrome rather than inline link text.

## Continuing Phase 15/16 Authenticated Feedback - 2026-08-13, Rail/Review/Actions

Further authenticated localhost inspection found additional layout and functional
blockers:

1. The utility rail menu panel has inconsistent left padding for `Delete my data
   and logout` compared with the adjacent `Feedback` and `Logout` options. Align
   the destructive details control visually with the other two menu choices while
   preserving the stronger destructive warning and confirmation flow.
2. The rail collapse control should use a left-pointing disclosure/arrow affordance
   rather than a down-pointing one. Collapsing the rail is a horizontal action,
   so the icon direction should communicate horizontal movement.
3. The Review History table formatting is materially ugly and inconsistent with
   the rest of the app. The current table exposes dense nested outcome counts,
   long ISO timestamps, timelines, and follow-up blocks in a way that overwhelms
   the Review page. Rework the history table into the same compact visual system
   as the rest of the workspace while preserving durable audit information,
   outcome reasons, undo eligibility, and provider follow-up links.
4. On Bulk Unsubscribe, clicking the primary `Unsubscribe` action for the selected
   sender row did not visibly do anything. Verify that the `hx-get`/target route
   and the inspection slot are wired, that failures are surfaced, and that the
   button either opens the expected preview/modal/staging UI or presents an honest
   unavailable state.
5. On Unread Only, the `Delete selected` button hover state changes white text to
   a blue shade that is hard to read. Redesign the hover/focus transition so the
   button remains high-contrast and readable.
6. Clicking `Delete selected` also did nothing visible: it did not delete and did
   not appear to stage the selected emails for Review. Verify the contextual bar
   wiring, exact-ID materialization, CSRF/account parameters, error reporting, and
   Review staging handoff for destructive selected actions.
