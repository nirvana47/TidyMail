---
phase: 17
plan: "04"
subsystem: rail-chrome
tags:
  - rail
  - utility-menu
  - destructive-controls
  - accessibility
key-files:
  - src/main/resources/templates/fragments/workspace-rail.html
  - src/main/resources/static/css/workspace.css
  - src/test/java/com/tidymail/Phase17RailChromeTest.java
metrics:
  tests: 27
  failures: 0
  errors: 0
---

# 17-04 Summary - Rail Brand, Utility Menu, And Collapse Affordance Polish

**Status:** Complete.

## Implemented

- Styled the clickable `TidyMail` rail brand as navigation chrome rather than a normal underlined hyperlink, including explicit hover and focus-visible states.
- Added horizontal rail collapse affordance markup:
  - expanded state shows a left-pointing `‹` collapse cue beside `Collapse rail`;
  - collapsed state mirrors to `›` beside `Expand rail`;
  - browser default summary markers are suppressed for the rail collapse control.
- Aligned `Delete my data and logout` with `Feedback` and `Logout` by moving the destructive summary into the shared rail menu-row selector.
- Preserved destructive account-data deletion safety:
  - POST form remains `/logout/delete-data`;
  - CSRF hidden input remains present;
  - `confirmation=DELETE` remains present;
  - `window.confirm(...)` warning copy remains present;
  - no GET delete link was added.
- Preserved exact visible rail labels and no visible numeric rail badges. Existing dynamic unread/review counts remain accessible-label-only semantics and are now documented by `Phase17RailChromeTest`.

## Verification

Passed:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -Dtest='Phase17RailChromeTest,Phase16WorkspaceFidelityTest,Phase14WorkspaceFidelityTest,Phase13WorkspaceResponsiveTest' test
```

Result: 27 tests, 0 failures, 0 errors, 0 skipped.

## Deviations

- No commits were created because the user explicitly requested `Do not commit`.
- No implementation deviation from 17-04 scope.

## Blockers

- None for automated focused verification.
- Authenticated visual UAT for collapsed/narrow rail alignment remains part of the later Phase 17 closure gate.

## Files Changed By 17-04

- `src/main/resources/templates/fragments/workspace-rail.html`
- `src/main/resources/static/css/workspace.css`
- `src/test/java/com/tidymail/Phase17RailChromeTest.java`
- `.planning/phases/17-fix-phase-15-and-16-ui-blocker-follow-ups/17-04-SUMMARY.md`

## Self-Check: PASSED

- Rail brand remains a clickable `/senders` home affordance with accessible label/text and explicit non-underlined chrome styling.
- Utility menu rows share the same padding/left edge selector, including the destructive delete-data summary.
- Delete-data flow still requires POST, CSRF, confirmation hidden input, and browser confirmation warning.
- Collapse control uses a horizontal left/right affordance and hides default details markers.
- Visible rail labels remain text-only: `Bulk Archive/Delete`, `Unread Only`, `Bulk Unsubscribe`, `Review`, and `Analytics`.
