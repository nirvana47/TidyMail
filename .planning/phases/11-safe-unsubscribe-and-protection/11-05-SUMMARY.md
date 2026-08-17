---
phase: 11-safe-unsubscribe-and-protection
plan: "05"
subsystem: verification-and-security-closure
tags: [postgresql-16, h2, testcontainers, ssrf, tls, privacy, performance]
requires:
  - phase: 11-safe-unsubscribe-and-protection
    plans: ["01", "02", "03", "04"]
    provides: candidate browsing, inspection, signed execution, honest outcomes, and pinned transport
provides:
  - PostgreSQL 16 clean and V7-to-V8 upgrade, Hibernate, H2 parity, constraint, concurrency, index, and 10,000-row evidence
  - complete hostile URI, DNS, redirect, TLS pinning, timeout, response-limit, and captured-log evidence
  - requirement-level account, protection, privacy, provider-isolation, UI, and Phase 12 boundary verification
affects: [phase-12, phase-13]
actuals:
  tasks: 4
  commits: 0
tech-stack:
  added: []
  patterns:
    - production PostgreSQL tests fail rather than skip when Docker is unavailable
    - deterministic dialect snapshots compare user-visible candidate facts directly
    - correctness assertions precede warmed performance assertions
key-files:
  created:
    - .planning/phases/11-safe-unsubscribe-and-protection/11-05-SUMMARY.md
    - .planning/phases/11-safe-unsubscribe-and-protection/11-VERIFICATION.md
  modified:
    - src/main/java/com/tidymail/unsubscribe/Rfc8058Eligibility.java
    - src/main/java/com/tidymail/unsubscribe/UnsubscribeUriPolicy.java
    - src/test/java/com/tidymail/Phase11PostgresMigrationTest.java
    - src/test/java/com/tidymail/Phase11UnsubscribeParserTest.java
    - src/test/java/com/tidymail/Phase11UnsubscribeSecurityTest.java
key-decisions:
  - "RFC 8058 postarg recognition follows case-insensitive ABNF while outbound body bytes remain canonical."
  - "Invalid ACE labels fail during URI inspection, before DNS or method eligibility."
  - "PostgreSQL Testcontainers absence is a test error, never a skipped pass."
requirements-completed: [UNSB-01, UNSB-02, UNSB-03, UNSB-04, UNSB-05, UNSB-06, UNSB-08, QUAL-01]
coverage:
  - id: D1
    description: "Ranked, filtered, deterministic account-owned candidate browsing."
    requirement: UNSB-01
    verification:
      - kind: integration
        ref: "Phase11CandidateQueryTest"
        status: pass
      - kind: integration
        ref: "Phase11PostgresMigrationTest"
        status: pass
    human_judgment: false
  - id: D2
    description: "Every typed filter has positive, exclusion, canonical-state, and fail-closed evidence."
    requirement: UNSB-02
    verification:
      - kind: integration
        ref: "Phase11CandidateQueryTest#everyFilterHasPositiveAndNearMatchExclusionAndOutcomeIsIdentityNeutral"
        status: pass
      - kind: automated_ui
        ref: "Phase11UnsubscribeWebTest#routesHaveOneOwnerAndInvalidCanonicalStateFailsBeforeQuery"
        status: pass
    human_judgment: false
  - id: D3
    description: "Redacted destination inspection exposes derived facts without provider or network calls."
    requirement: UNSB-03
    verification:
      - kind: automated_ui
        ref: "Phase11UnsubscribeWebTest#fullPageResultFragmentAndInspectionRenderSamePrivacySafeCandidate"
        status: pass
    human_judgment: false
  - id: D4
    description: "Explicit RFC 8058 POST uses strict current facts, exact bytes, and honest outcomes."
    requirement: UNSB-04
    verification:
      - kind: integration
        ref: "Phase11UnsubscribeExecutionTest#pendingIsCommittedBeforeOneTransportCallAndAcceptedRemainsVerificationPending"
        status: pass
      - kind: unit
        ref: "Phase11UnsubscribeParserTest#rfc8058MatrixFailsClosedAndKeepsCanonicalOutboundBytes"
        status: pass
    human_judgment: false
  - id: D5
    description: "Legacy HTTPS GET is separately confirmed, bodyless, and never a fallback."
    requirement: UNSB-05
    verification:
      - kind: integration
        ref: "Phase11UnsubscribeExecutionTest#explicitLegacyGetIsBodylessAndPostFailureNeverFallsBack"
        status: pass
    human_judgment: false
  - id: D6
    description: "Mailto and provider alternatives require explicit navigation and never auto-send or server-fetch."
    requirement: UNSB-06
    verification:
      - kind: automated_ui
        ref: "Phase11UnsubscribeWebTest#explicitMailtoAndProviderNavigationRedirectOnlyAfterUserChoice"
        status: pass
    human_judgment: false
  - id: D7
    description: "All six append-only outcome states retain truthful source and completion semantics."
    requirement: UNSB-08
    verification:
      - kind: integration
        ref: "Phase11UnsubscribeExecutionTest"
        status: pass
      - kind: integration
        ref: "Phase11PostgresMigrationTest"
        status: pass
    human_judgment: false
  - id: D8
    description: "Unsafe URI, address, DNS, redirect, TLS, retry, timeout, and response paths fail closed."
    requirement: QUAL-01
    verification:
      - kind: integration
        ref: "Phase11UnsubscribeSecurityTest"
        status: pass
      - kind: integration
        ref: "Phase11UnsubscribeOnWireTlsTest"
        status: pass
    human_judgment: false
duration: 14 min
completed: 2026-08-10
status: complete
---

# Phase 11 Plan 05: Verification And Security Closure Summary

**PostgreSQL production parity, hostile-network proof, privacy closure, and zero-skip regression evidence for safe unsubscribe**

## Performance

- **Duration:** 14 min
- **Started:** 2026-08-10T03:08:00Z
- **Completed:** 2026-08-10T03:22:00Z
- **Tasks:** 4
- **Files modified:** 5 code/test files plus 2 planning artifacts
- **Commits:** 0, as requested

## Accomplishments

- Expanded PostgreSQL 16.14 proof to cover clean V1-V8, actual V7-to-V8 preservation,
  Hibernate validation, direct H2 candidate-fact parity, constraints, cascades,
  one-winner nonce concurrency, index plans, and a deterministic 10,000-row budget.
- Corrected RFC 8058 ASCII case-insensitive `postarg` recognition while retaining the
  exact canonical outbound body, and rejected invalid ACE labels during inspection.
- Completed captured-log privacy proof and reran the exact focused, hostile-network,
  PostgreSQL critical, and full regression matrices with zero skips.

## Verification

- Parser/query/web: **24 tests, 0 failures, 0 errors, 0 skipped**.
- Execution/security: **19 tests, 0 failures, 0 errors, 0 skipped**.
- Hostile network plus real TLS wire and intent tests: **29 tests, 0 failures,
  0 errors, 0 skipped**.
- PostgreSQL and inherited critical matrix: **55 tests, 0 failures, 0 errors,
  0 skipped**.
- Full Maven suite: **179 tests, 0 failures, 0 errors, 0 skipped**.
- `git diff --check` and untracked-file trailing-whitespace scan: passed.
- Frozen V8 SHA-256:
  - production: `190c0d9cf10ca687bafc0604325aaa47380ae91593b1dbc2d669e9ba27256461`
  - H2: `fd9fd85cdb5c3abae103ec8b82f30e96f67ff332934b9d26666a51ea4839724d`

## Deviations From Plan

### Auto-Fixed Issues

1. PostgreSQL evidence lacked direct dialect parity, concurrency, and 10,000-row proof.
   The production test now makes these acceptance conditions executable and fails
   rather than skips when Docker is unavailable.
2. RFC 8058 `postarg` recognition was incorrectly case-sensitive. Recognition now
   follows ABNF case rules while request bytes remain canonical.
3. Invalid `xn--` labels passed inspection but failed later in DNS validation. URI
   inspection now performs the same IDN round-trip rejection.
4. Log privacy was inferred rather than asserted. Captured output now rejects URI and
   response-body canaries.

All deviations close planned correctness or security gaps. No Phase 12 behavior,
schema change, cloud deployment, or real unsubscribe request was introduced.

## Issues Encountered

- Testcontainers cannot infer the Colima Docker context. Verification exported
  `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`.
- The macOS Java 21 selector resolves to installed OpenJDK 26.0.1; an actual Java 21
  runtime was unavailable.

## User Setup Required

None.

## Next Phase Readiness

Phase 11 is complete and independently verifiable. Phase 12 can consume the immutable
candidate, intent, attempt, and outcome contracts without weakening protection or
transport safety.

---
*Phase: 11-safe-unsubscribe-and-protection*
*Completed: 2026-08-10*
