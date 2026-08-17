---
status: fixed
phase: 11
plan: "04"
depth: deep
files_reviewed: 18
findings:
  critical: 0
  warning: 3
  info: 0
  total: 3
---

# Phase 11 Plan 04 Review

## Fixed Findings

### WR-01: Nonce and PENDING persistence were not atomic

Nonce consumption committed before attempt/event creation. A persistence failure or
known protection rejection could permanently consume the signed intent without a
recorded attempt.

Fixed with one preparation transaction covering fresh intent/candidate/protection
validation, nonce consumption, and PENDING attempt/event persistence. DNS and HTTP
remain outside the transaction.

### WR-02: IPv6 future/reserved space passed the public-address policy

The denylist rejected named special ranges but accepted IPv6 addresses outside the
global-unicast allocation, including `4000::1` and `8000::1`.

Fixed by requiring IPv6 destinations to be inside `2000::/3` before applying the
existing special-range exclusions and global exceptions. Boundary tests were added.

### WR-03: Cleanup and unusual statuses could distort safe results

A client-close failure could replace an already determined result, and final HTTP
status classes outside 2xx-5xx could fail result construction.

Fixed by making client cleanup best-effort after classification and mapping unusual
status classes to a sanitized remote rejection with no persisted status class.

## Verification

- Focused Phase 11: 47 tests, 0 failures, 0 errors, 0 skipped.
- PostgreSQL 16.14: 1 test, 0 failures, 0 errors, 0 skipped.
- Full suite: 179 tests, 0 failures, 0 errors, 0 skipped.
- `git diff --check` and trailing-whitespace scan passed.
- Frozen production and H2 V8 migration SHA-256 hashes are unchanged.

## Residual Risks

- The host's Java 21 selector resolves to Java 26.0.1; Java 21 was not available.
- JVM DNS lookup cancellation remains platform-dependent, but resolver workers and
  queue capacity are bounded and daemonized.
- IANA special-purpose registries require periodic policy review.
