---
created: 2026-08-12T01:40:02.718Z
title: Remove cleanup page status clutter
area: ui
severity: major
resolves_phase: 16
status: completed
completed: 2026-08-13
files:
  - tidymail-ux-rehaul-draft/tidymail-ux-rehaul-draft.design
  - tidymail-ux-rehaul-draft/pages/build-notes.html:512
  - tidymail-ux-rehaul-draft/pages/by-sender-compact-suite.html:296
  - tidymail-ux-rehaul-draft/pages/by-sender-compact-suite.html:329
  - tidymail-ux-rehaul-draft/pages/by-sender-compact-suite.html:382
  - tidymail-ux-rehaul-draft/pages/unread-compact-suite.html:295
  - tidymail-ux-rehaul-draft/pages/unread-compact-suite.html:328
  - tidymail-ux-rehaul-draft/pages/unsubscribe-compact-suite.html:448
  - src/main/resources/templates/fragments/workspace-rail.html:35
  - src/main/resources/templates/fragments/workspace-rail.html:50
  - src/main/resources/templates/senders.html:25
  - src/main/resources/templates/unread.html:19
  - src/main/resources/templates/fragments/unread-results-frag.html:6
  - src/main/resources/templates/fragments/senders-results-frag.html:21
  - src/main/resources/templates/fragments/senders-results-frag.html:61
  - src/main/resources/templates/fragments/cleanup-action-result-frag.html:4
  - src/main/resources/static/js/cleanup-categories.js:149
  - src/main/resources/static/css/workspace.css:982
  - src/main/resources/static/css/workspace.css:1087
  - src/main/resources/static/css/workspace.css:1101
---

## Problem

Phase 15 authenticated manual UAT found several elements that add clutter or imply
an interaction model the product should not use:

1. The left-rail `Unread Only` and `Review` links append numeric badges to their
   labels. Remove these displayed counts so the navigation labels remain text-only.
   Review remains the destination where users inspect staged actions.
2. Delete both explanatory notes currently shown above the Unread category list:
   - `Counts and rows reflect the last completed metadata sync; provider changes
     appear after the next sync.`
   - `Unread groups are not mutually exclusive. A sender can appear in more than one
     group.`
3. Rewrite `Open a group of Inbox-unread mail and correct sender classification when
   needed.` in clearer, more user-friendly language. Final copy is still to be
   decided during remediation.
4. Remove the generic `Ready to stage for Review` status section from Bulk
   Archive/Delete and Unread Only, and audit the remaining cleanup pages for the same
   component. These pages should focus on their cleanup task rather than permanently
   presenting a staging-status panel.
5. Actions must continue to stage exact message IDs into Review. Users discover and
   inspect staged actions through the `Review` item in the left rail; removing the
   panel must not weaken exact-scope staging, authorization, or Review behavior.
6. Make cleanup page buttons slightly more rounded so they match the mock button
   treatment more closely. This applies to Settings and related cleanup controls.
7. Category sections in the implementation are enclosed in a broad pill/card wrapper,
   while the mocks show each category toggle as its own compact row. Remove the
   surrounding pill-style treatment and bring the implemented category hierarchy back
   to the mock structure.
8. Category headers currently show sender counts but do not consistently show the
   section size or unread/volume metric visible in the mocks. Match the mock pattern:
   category name and description on the left, sender count plus category size/unread
   total on the right.
9. The implemented sender rows are visually too tall and heavy compared with the
   mocks. Rework the predefined category row layout to be more compact, cleaner, and
   more professional, matching the mock spacing, hierarchy, and density.
10. The current implementation places a `Correct classification` button underneath
    each sender. The mocks instead show category-management affordances for changing
    a sender's category and creating a new category, with user-generated categories
    receiving a visible `User generated` tag. Align the correction flow and
    user-generated category display with that mock behavior.
11. Perform a second page-by-page walkthrough of the running app against
    `tidymail-ux-rehaul-draft/tidymail-ux-rehaul-draft.design` and its Build Notes.
    Capture UI/UX differences before remediation, because the current implementation
    appears not to have fully used the mocks as the final layout contract.

This is an incremental Phase 15 UAT capture. More manual review findings will follow.
Do not begin remediation until the user finishes the review and explicitly requests
execution.

## Solution

TBD after the remaining Phase 15 manual UAT feedback is captured. The remediation
should remove the rail badges and generic cleanup status panel, delete the two
specified Unread notes, replace the remaining Unread instruction with approved
user-facing copy, round cleanup buttons to match the mocks, remove the broad
pill/card enclosure around category sections, restore category header metrics and
compact sender-row density from the mocks, replace per-row `Correct classification`
clutter with the mock category-change/create-category model, preserve visible
`User generated` tags for user-created categories, run a page-by-page app-versus-mock
walkthrough, and preserve the existing exact-ID-to-Review workflow.
