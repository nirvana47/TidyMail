# Pitfall Research: v1.1 Complete Cleanup Workspace

## Historical Accuracy From Snapshot Data

Current sender totals cannot reconstruct received volume or cleanup trends. Record message first-seen timestamps and immutable action outcomes before exposing analytics.

## Action Scope Drift

Never resolve message IDs again during approval or execution. A preview, queue row, execution, and undo must share the same immutable scope and scope hash.

## False Success On Partial Provider Failure

Gmail batch operations may not provide per-ID confirmation. Distinguish accepted, confirmed, failed, and verification-pending outcomes. Undo only IDs recorded as succeeded or accepted by the provider.

## Unsafe Unsubscribe Fetches

Unsubscribe URLs are untrusted input. Require HTTPS for automated requests, block loopback/private/link-local destinations after DNS resolution, cap redirects, set short timeouts, never forward OAuth cookies or tokens, and show the destination host before approval.

## Duplicate Or Conflicting Actions

Prevent active scopes from applying incompatible operations to the same message. Define deterministic precedence or reject the batch with a conflict list before mailbox mutation.

## Protected Sender Leakage

Protected senders must be excluded server-side from bulk select and execution, not merely hidden in the browser. An explicit include-protected override must be visible and audited.

## Ambiguous Keep Behavior

Implement both useful meanings without conflation: `KEEP` dismisses a current recommendation, while `PROTECT` suppresses future destructive recommendations until removed.

## Immediate Mode Weakening Safety

Immediate mode must not bypass preview, write-before-mutate logging, exact scope, partial-result recording, or undo. It only skips the separate review-page approval step.

## Analytics Denominator Drift

Define metrics precisely and expose their denominators in tooltips or details. Filters used for headline metrics, charts, drilldowns, and exports must be identical.

## Performance Regression

Page requests must not trigger Gmail scans. Query persisted metadata with indexes, fetch in pages, and refresh stale data asynchronously. Establish measurable localhost budgets for initial shell, filtered tables, and sync throughput.

## Privacy Regression Through Export

CSV and shared snapshots must include only visible metadata columns and counts. Never export tokens, raw headers beyond the approved projection, message bodies, or internal failure payloads containing secrets.

## Decorative Mock Controls

Every rendered control must either work, be disabled with a clear capability explanation, or be removed. Static buttons and links with `href="#"` are milestone failures.
