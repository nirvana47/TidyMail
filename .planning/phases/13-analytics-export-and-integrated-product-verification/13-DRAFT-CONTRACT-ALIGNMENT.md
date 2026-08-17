# Latest Design Contract Alignment

**Date:** 2026-08-11
**Source:** `tidymail-ux-rehaul-draft/tidymail-ux-rehaul-draft.design`

Only the `Bulk Archive/Delete`, `Unread Only`, `Bulk Unsubscribe`, `Review`,
`Analytics`, and `Build Notes` pages in that Design Canvas project are authoritative.
The `Build Notes` page is the implementation contract. Older TidyMail mocks, HTML
exports, other chats, and separate Design Canvas projects must not be used.

## Implemented

- Shared rail-only active-account selector, owned-account session state, Gmail add-account routing, retained-data logout, and confirmed full-data deletion logout.
- Quiet Bulk Archive/Delete and Unread category accordions, non-exclusive unread classification, exact action settings, and sanitized user categories.
- Individual-sender Bulk Unsubscribe with lifecycle category, exact date range, sender/domain search, Archive/Delete fallback, Read %, exact actions, and reusable stats modal.
- Review LIFO ordering by immutable `queued_at` and `sequence`, exact selected counts, delete-specific warnings, and reusable View Emails modal.
- One-account Analytics with period presets, intelligent grouping defaults, four metric tabs, Top Received/Top Sent limits, full-message size cards, two-bar Archived/Deleted results, and provider denominator fallback.
- Exact materialized message scopes, protected-sender handling, override auditing, honest unsubscribe outcomes, and local indexed-data queries remain enforced.

## Schema

- V11: owned connected-account identity and session support.
- V12: message-size/attachment/snippet metadata and user sender categories.
- V13: immutable Review queue `queued_at` and `sequence`.

## Verification

```text
Java: 21.0.12
Database: PostgreSQL 16.14 via Testcontainers, plus H2 parity suites
Tests: 344
Failures: 0
Errors: 0
Skipped: 0
```

Command:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
DOCKER_HOST=unix:///Users/bytedance/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
mvn test
```

## Remaining Manual Gates

The earlier Phase 13 live-browser constraints remain unchanged: authenticated 375x812 at 200% zoom and a genuinely eligible real unsubscribe endpoint still require suitable external conditions. Public deployment remains blocked until those gates are deliberately rerun.
