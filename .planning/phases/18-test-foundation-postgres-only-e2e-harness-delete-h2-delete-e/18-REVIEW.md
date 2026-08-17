---
status: clean
phase: 18-test-foundation-postgres-only-e2e-harness-delete-h2-delete-e
review_date: 2026-08-14
review_depth: focused
head: 378ff54bdded17db1f051e3e449b3bb0d2ba5f38
diff_range: 378ff54^..378ff54
files_reviewed: 3
findings:
  critical: 0
  warning: 0
  info: 0
  total: 0
---

# Phase 18 Final Focused Re-review

## Findings

No critical, warning, or info findings remain.

## Resolved Findings

- `SyntheticMailboxGenerator` now retains one external sender per three-row thread while allowing
  owner-originated rows to model replies. `SENT` rows use the owner address and return immediately
  from label construction with only `SENT`, so they cannot also carry `INBOX` or `UNREAD`.
- `SyntheticMailboxPiiGuardTest` verifies thread sender coherence and sent-row invariants directly.
  Its PII scan serializes each complete `message_metadata` row with `to_jsonb(m)`, and its fixed-seed
  checksum hashes the same canonical whole-row representation in provider-message order. This
  covers scalar fields, arrays, timestamps, authentication flags, and authentication reasons.
- `AnalyticsJourneyTest` asserts the exact seeded analytics contract: 113 received messages, 7 sent
  messages, the expected top sender with count 113, and exact numerator/denominator pairs of `0/0`,
  `0/120`, and `0/0` for review coverage, cleanup coverage, and unsubscribe completion. The
  PostgreSQL `percentile_cont` control remains pinned to the expected median of 4096.

## Scope

Reviewed the three files changed by `378ff54`:

- `src/test/java/com/tidymail/journey/AnalyticsJourneyTest.java`
- `src/test/java/com/tidymail/support/SyntheticMailboxGenerator.java`
- `src/test/java/com/tidymail/support/SyntheticMailboxPiiGuardTest.java`

The prior full Phase 18 review's verified areas remain unchanged; this pass focused on confirming
the three previously reported findings were resolved without introducing a new issue.

## Verification

`DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test`

Result: 404 tests, 0 failures, 0 errors, 0 skipped.
