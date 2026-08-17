---
phase: 17
plan: "03"
subsystem: unread-only-cleanup
tags:
  - unread
  - cleanup-staging
  - attachment-metadata
  - ui-feedback
key-files:
  - src/main/resources/templates/unread.html
  - src/main/resources/static/js/cleanup-categories.js
  - src/main/resources/static/css/workspace.css
  - src/test/java/com/tidymail/Phase17UnreadOnlyTest.java
metrics:
  tests: 43
  failures: 0
  errors: 0
---

# 17-03 Summary - Unread Only Totals, Attachment Evidence, And Delete Staging

**Status:** Complete.

## Implemented

- Added a visible `Inbox unread` panel to `/unread`, sourced from the existing server-owned `WorkspacePageModel.workspace.inboxUnreadCount` path. The rendered count is the existing `DecisionSurfaceQueryRepository.countUnread(...)` result, which uses the default `UnreadQuery` and the backend `unread + INBOX` predicate.
- Kept the rail visually text-only: no visible numeric rail badges were added for Unread Only or Review.
- Audited attachment grouping through the current metadata path:
  - `GmailMailService.toMailMessage(...)` derives `hasAttachments` from Gmail METADATA part filename / attachment-id metadata while rejecting body data.
  - `MailMessage.hasAttachments()` is persisted by `MessageMetadataMapper` into `MessageMetadata.hasAttachments`.
  - `DecisionSurfaceSql.unread(...)` exposes `m.has_attachments`, and `UnreadDecisionQueryService` accumulates the `attachments` group from the same Inbox-unread base query.
- Made Unread Only delete staging failure paths visible:
  - missing selected rows already reported through the cleanup feedback region;
  - missing dialog/source/target now reports an actionable visible error;
  - non-2xx HTMX cleanup staging responses now report a visible contextual error.
- Added scoped destructive selected-action CSS for normal, hover, focus-visible, active, disabled, HTMX loading, and staged states so text remains high contrast and does not inherit generic blue hover text.
- Added focused `Phase17UnreadOnlyTest` coverage for visible Inbox unread rendering, stale Unread note removal, text-only rail constraints, Gmail metadata-only attachment detection, exact Unread `TRASH` staging to Review, no provider mutation before Review approval, and destructive CSS/JS feedback contracts.

## Safety Notes

- No provider mutation path was added or changed for Unread Only cleanup staging.
- Existing CSRF, account ownership, request token, delete acknowledgement, protected sender handling, exact immutable scopes, duplicate reuse, 1,000-ID guard, metadata-only privacy, and no-export constraints were preserved.
- `CleanupActionService` locking/transaction behavior was not changed; the known in-JVM lock dependency was not deepened.

## Verification

Passed:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -Dtest='Phase17UnreadOnlyTest,Phase15UnreadSemanticsTest,Phase15StagingTest,Phase9MetadataIndexTest,Phase16WorkspaceFidelityTest' test
```

Result: 43 tests, 0 failures, 0 errors, 0 skipped.

## Deviations

- No backend count path was added because `WorkspaceModelService.buildResolved(...)` already computed the authoritative Inbox-unread count through the correct repository predicate.
- No attachment ingestion code change was required; focused evidence was added around the existing metadata-only path.

## Blockers

- None for 17-03 automated verification.
- Authenticated live Gmail reconciliation of the visible `Inbox unread` total remains a later Phase 17 UAT closure gate because it depends on current provider state and sync freshness.

## Files Changed By 17-03

- `src/main/resources/templates/unread.html`
- `src/main/resources/static/js/cleanup-categories.js`
- `src/main/resources/static/css/workspace.css`
- `src/test/java/com/tidymail/Phase17UnreadOnlyTest.java`
- `.planning/phases/17-fix-phase-15-and-16-ui-blocker-follow-ups/17-03-SUMMARY.md`
