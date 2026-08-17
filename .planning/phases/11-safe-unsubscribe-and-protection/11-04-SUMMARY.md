---
phase: 11-safe-unsubscribe-and-protection
plan: "04"
subsystem: outbound-transport-security
tags: [httpclient5, ssrf, dns-pinning, tls, redirects, timeouts, signed-intent]
requires:
  - phase: 11-safe-unsubscribe-and-protection
    plan: "01"
    provides: strict URI parsing, RFC 8058 contract, and frozen V8 schema
  - phase: 11-safe-unsubscribe-and-protection
    plan: "03"
    status: complete
    provides: signed intents, protection/staleness checks, committed PENDING, and outcomes
provides:
  - bounded all-answer public DNS validation and immutable resolve-once destinations
  - request-scoped DNS-pinned direct TLS transport with default hostname verification
  - exact RFC 8058 POST and separately confirmed bodyless legacy HTTPS GET
  - production lifecycle integration after fresh checks and committed PENDING
  - on-wire pinned-address, SNI, Host, certificate, header, and body evidence
  - redirect, retry, timeout, body-limit, and privacy-safe failure contracts
affects: [11-05]
actuals:
  tasks: 4
  commits: 0
tech-stack:
  added: [Apache HttpClient 5.5.2]
  patterns:
    - resolve and validate every DNS answer once, then pin the immutable set
    - create and close one direct no-proxy client per outbound attempt
    - consume a method-bound signed intent and commit PENDING before DNS or HTTP
    - persist only allowlisted status-class and reason facts
key-files:
  reused-foundation:
    - src/main/java/com/tidymail/unsubscribe/PublicAddressPolicy.java
    - src/main/java/com/tidymail/unsubscribe/DestinationResolver.java
    - src/main/java/com/tidymail/unsubscribe/ResolvedDestination.java
    - src/main/java/com/tidymail/unsubscribe/UnsubscribeTransportProperties.java
    - src/main/java/com/tidymail/unsubscribe/ApacheUnsubscribeHttpTransport.java
    - src/main/java/com/tidymail/unsubscribe/ValidatedUnsubscribeRequest.java
  integrated:
    - src/main/java/com/tidymail/service/UnsubscribeExecutionService.java
    - src/main/java/com/tidymail/service/UnsubscribeCandidateService.java
    - src/main/resources/templates/fragments/unsubscribe-inspection-frag.html
    - src/main/java/com/tidymail/unsubscribe/PinnedTransportFactory.java
  evidence:
    - src/test/java/com/tidymail/unsubscribe/Phase11UnsubscribeOnWireTlsTest.java
    - src/test/java/com/tidymail/Phase11UnsubscribeSecurityTest.java
    - src/test/java/com/tidymail/Phase11UnsubscribeExecutionTest.java
    - src/test/java/com/tidymail/Phase11UnsubscribeWebTest.java
key-decisions:
  - "The early transport primitives were retained; Plan 04 completion adds lifecycle and wire-level closure."
  - "Legacy GET is not executable when current facts qualify for RFC 8058 POST."
  - "The RFC 8058 pair is case-sensitive after unfolding and outer OWS removal."
  - "The package-private TLS fixture seam cannot alter Spring production construction or public-address policy."
requirements-completed: [UNSB-04, UNSB-05, QUAL-01]
status: complete
completed: 2026-08-10
---

# Phase 11 Plan 04: Hardened Unsubscribe Transport Integration Summary

**The existing DNS-pinned transport foundation is now fully connected to the signed
single-candidate lifecycle, with exact POST/GET behavior and controlled on-wire proof.**

## Scope Reconciliation

The original early `11-04-SUMMARY.md` correctly described a reusable standalone
foundation, but Plan 04 was not complete at that point because Plan 03 lifecycle work
did not yet exist. This closure did not rebuild those primitives.

### Reused Early Foundation

- Explicit IPv4/IPv6 global-unicast and special-use hostname policy.
- Bounded resolve-once DNS with immutable all-public answer sets.
- Request-scoped Apache HttpClient with pinned DNS, direct routing, default TLS
  verification, no proxy/cookies/auth/retries/redirects, and finite deadlines.
- Exact POST/bodyless GET request records, 8192-byte response cap, sanitized results,
  and privacy-redacted object strings.

### Newly Completed Integration

- Confirmed `ApacheUnsubscribeHttpTransport` is the sole production transport bean.
- Connected it only after owned-source reload, method/eligibility reparse, signed
  intent consumption, staleness/account/protection checks, and committed `PENDING`.
- Kept DNS resolution and the one outbound call after `PENDING`, preserving
  write-before-I/O and exactly-once/no-service-retry behavior.
- Made the RFC 8058 token case-sensitive after permitted unfolding and outer OWS.
- Prevented legacy GET intent issuance and execution whenever RFC 8058 POST is eligible.
- Added exact `Send one-click POST` and `Send legacy HTTPS GET` confirmation copy,
  including accepted-not-confirmed and no-fallback warnings.
- Added controlled TLS wire evidence that the real request-scoped client receives the
  pinned public address, resolves once, sends the original Host and SNI, validates the
  certificate hostname, and emits the exact POST body/content type without credential
  headers.
- Expanded every tested 3xx across absent, public, private, non-HTTPS, credentialed,
  and malformed `Location` values; all stop after one request without body inspection.

## Task Coverage

| Plan task | Final evidence |
|---|---|
| Task 1: address and DNS policy | Existing exhaustive prefix/hostname/deadline/all-answer tests retained and green |
| Task 2: pinned direct TLS transport | Real-client wire test proves pinned address, one DNS call, SNI, Host, hostname rejection, exact headers, and request isolation |
| Task 3: RFC 8058 POST | Fresh signed lifecycle, committed PENDING, strict eligibility, exact bytes/content type, one call, redirect/no-retry, honest outcomes, and UI copy are green |
| Task 4: explicit legacy GET | Separate method-bound intent, POST precedence, bodyless one-call execution, identical transport policy, no fallback, redirect matrix, and UI copy are green |

## Safety Boundaries

- Account ownership, source freshness, destination fingerprint, method, one-time
  nonce, and protection are revalidated before attempt creation or network access.
- A protected, foreign, stale, changed, expired, or replayed confirmation performs no
  DNS lookup or transport call.
- `PENDING` is committed before DNS and HTTP. A timeout remains
  `TIMEOUT_UNKNOWN_REMOTE_RESULT` and is never retried.
- 2xx appends `ACCEPTED` then `VERIFICATION_PENDING`, never automated `CONFIRMED`.
- Every 3xx becomes `REDIRECT_BLOCKED`; `Location` is not parsed, resolved, or fetched.
- No full URI, response body, exception text, DNS address, provider identifier,
  credential, cookie, or authorization value enters outcome persistence or templates.
- No Phase 12 selection, Review routing, queue, `ActionScope`, `UserAction`, policy
  toggle, shared approval, or multi-candidate behavior was added.

## Verification

- Focused Phase 11 parser/candidate/lifecycle/security/TLS/MVC/intent:
  **46 tests, 0 failures, 0 errors, 0 skipped**.
- Named Phase 9/10 regression gate:
  **54 tests, 0 failures, 0 errors, 0 skipped**.
- PostgreSQL 16.14 Testcontainers:
  **1 test, 0 failures, 0 errors, 0 skipped**, covering clean V1-V8, V7-to-V8,
  Hibernate validation, constraints, and query behavior.
- Full Maven suite with PostgreSQL containers active:
  **178 tests, 0 failures, 0 errors, 0 skipped**.
- `git diff --check`: passed.
- Frozen V8 SHA-256 remained exact:
  - production: `190c0d9cf10ca687bafc0604325aaa47380ae91593b1dbc2d669e9ba27256461`
  - H2: `fd9fd85cdb5c3abae103ec8b82f30e96f67ff332934b9d26666a51ea4839724d`

All Maven commands used `JAVA_HOME=$(/usr/libexec/java_home -v 21)`. The local selector
resolves to installed Java 26.0.1. PostgreSQL and the full suite used Colima with
`TESTCONTAINERS_RYUK_DISABLED=true`; the report was checked for zero skips.

## Residual Risks

- The host does not currently provide an actual Java 21 runtime despite the selector.
- JVM DNS is blocking beneath the bounded executor. A timed-out native lookup can
  retain a daemon worker until the platform resolver returns; saturation fails closed.
- IANA special-purpose address registries can change and require periodic table review.

## User Setup Required

None.

---
*Phase: 11-safe-unsubscribe-and-protection*
*Plan: 04 complete*
