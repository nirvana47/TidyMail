---
phase: 11-safe-unsubscribe-and-protection
status: passed
verified: 2026-08-09
independently_reaudited: 2026-08-09
requirements_verified: 8
requirements_total: 8
automated_tests:
  focused_parser_query_web: 24
  focused_execution_security: 19
  hostile_network_wire_intent: 29
  postgres_critical_regression: 55
  full_suite: 179
  failures: 0
  errors: 0
  skipped: 0
manual_items_blocking: false
---

# Phase 11 Verification

## Verdict

**Phase 11 passes.** All eight mapped requirements and all four roadmap success
criteria have positive automated evidence plus relevant adversarial account,
protection, privacy, provider-isolation, and transport evidence. No blocking gap
remains.

## Independent Re-Audit

The completion claim was independently re-audited against `ROADMAP.md`, all eight
mapped requirements, Plans/Summaries 01-05, validation/review/verification artifacts,
the current code and templates, frozen V8 migrations, and generated Surefire reports.
No blocking defect or missing test was found, so no implementation or test file
required correction.

Freshly reproduced results:

- Focused parser/query/MVC: **24 tests**, 0 failures, 0 errors, 0 skipped.
- Focused execution/security: **19 tests**, 0 failures, 0 errors, 0 skipped.
- Combined hostile-network/lifecycle/MVC, including real TLS wire and signed intent:
  **47 tests**, 0 failures, 0 errors, 0 skipped.
- PostgreSQL 16.14 and inherited critical regression: **55 tests**, 0 failures,
  0 errors, 0 skipped.
- Full suite: **179 tests across 41 reports**, 0 failures, 0 errors, 0 skipped.
- Phase 11 inventory including PostgreSQL: **48 tests across 8 reports**, 0 failures,
  0 errors, 0 skipped.
- `git diff --check`, untracked trailing-whitespace scan, and frozen V8 hashes: PASS.

## Requirement Evidence

| Requirement | Result | Positive and adversarial evidence |
|---|---|---|
| UNSB-01 | PASS | `Phase11CandidateQueryTest` proves exact trailing-30-day volume, newest source selection, confidence/method classification, stable identities, all sorts, tie-breakers, and two disjoint pages. `Phase11PostgresMigrationTest` compares equivalent H2/PostgreSQL candidate facts and proves 100 candidates over 10,000 rows. DTO/HTML canaries and provider-free dependency tests are negative evidence. |
| UNSB-02 | PASS | `canonicalQueryRoundTripsEveryTypedDimensionAndFailsClosed` covers all typed values, malformed/duplicate/unknown parameters, account selectors, and page bounds. `everyFilterHasPositiveAndNearMatchExclusionAndOutcomeIsIdentityNeutral` covers method, volume, confidence, review, age, protected, search, and outcome inclusion/exclusion. Web tests prove invalid state stops before repository execution. |
| UNSB-03 | PASS | Full and HTMX inspection render the same normalized host, redacted path/query presence, header facts, trusted DKIM pass/coverage, confidence, protection, and separate method choices. Raw URI/query, authentication headers, provider IDs, and signatures are absent; inspection invokes no provider or transport. |
| UNSB-04 | PASS | RFC 2369/8058 tests cover one HTTPS URI, one post field, ASCII case-insensitive ABNF, canonical exact body, DKIM pass and same-signature coverage, duplicates, parameters, lookalikes, and missing facts. Execution proves authenticated CSRF submit, signed method/destination binding, committed PENDING before one POST, exact content type/body, no credential headers, no retry, and 2xx to ACCEPTED then VERIFICATION_PENDING, never automated CONFIRMED. |
| UNSB-05 | PASS | Web tests offer Legacy HTTPS GET only when RFC 8058 POST is unavailable. Execution proves one bodyless explicit GET, no POST-to-GET fallback, shared resolver/transport policy, redirect failure, and honest legacy copy. Browse and inspection never trigger it. |
| UNSB-06 | PASS | Explicit mailto and Gmail source-message redirects occur only after user choice, preserve validated target behavior, and create no server HTTP request or attempt. Inspection hides subject/body and provider IDs. Manual completion records MANUAL_REQUIRED, VERIFICATION_PENDING, and explicit USER_CONFIRMED source. Protected rows expose neither navigation nor completion controls. |
| UNSB-08 | PASS | Execution and PostgreSQL/H2 parity fixtures cover PENDING, ACCEPTED, CONFIRMED, FAILED, MANUAL_REQUIRED, and VERIFICATION_PENDING. Events remain append-only and ordered; a new attempt does not rewrite history; timeout records unknown remote result without retry; status classes and confirmation sources are bounded; no response body or exception detail persists. |
| QUAL-01 | PASS | Parser/security tests reject non-HTTPS schemes, authority confusion, controls, malformed percentages, invalid IDN/ACE, numeric literals, private/special IPv4, non-global/transition IPv6, empty/excess/mixed DNS answers, and late DNS results. Real TLS wire tests prove one pinned resolution, validated address use, original Host/SNI/certificate hostname, exact request bytes, and mismatch failure. Every tested 3xx makes one request and ignores Location; timeouts, response cap, no retry, no proxy/cookies/auth, and captured-log privacy pass. |

## PostgreSQL And Dialect Evidence

- PostgreSQL **16.14** ran production migrations from empty V1-V8 and an actual
  V7-to-V8 upgrade with no skip.
- V7 account, metadata, protection, and override-audit rows survived; identity
  allocation continued; new DKIM facts defaulted false.
- Hibernate `ddl-auto=validate` passed against the exact Testcontainers JDBC URL.
- PostgreSQL rejects unknown methods/statuses/reasons, malformed fingerprints,
  invalid status classes/hosts/expiry, and cross-account event references.
- Account deletion cascades attempts, events, and nonces.
- H2 and PostgreSQL return equal sender, volume, method, confidence, review,
  protection, and latest-outcome facts for equivalent fixtures.
- Concurrent PostgreSQL nonce consumption has exactly one `CONSUMED` winner and one
  `REPLAYED` result.
- A deterministic 10,000-row dataset first asserts 100 candidates and exact
  first/last identities, then enforces warmed repository and result-model requests
  under two seconds. PostgreSQL plans use the V8 candidate/source indexes.

## Security And Privacy Boundaries

- Unsafe destinations fail before connector invocation; stale, foreign, expired,
  replayed, protected, and changed-destination paths fail before unauthorized I/O.
- Protection added after inspection blocks execution. Phase 10 override audit rows do
  not grant unsubscribe authority. Removing protection does not bypass nonce and
  current-fact checks.
- Attempt/event/nonce schemas contain only allowlisted bounded facts. Entity
  reflection, H2/PostgreSQL information schema, DTO, HTML, request capture, and log
  capture reject URI, query, raw auth/DKIM, response, exception, cookie, token, DNS,
  and provider-ID canaries.
- Candidate browse, result fragments, inspection, and outcome reads are
  provider-neutral. Gmail/Outlook mocks and unsubscribe transport receive zero
  interactions on read paths.
- No Phase 11 route saves multi-candidate selection, sends to Review, chooses staged
  or immediate policy, creates `ActionScope`/`UserAction`, executes all visible or
  matching rows, or exposes analytics/export controls.

## Automated Results

Commands used the requested selector and explicit Colima socket:

```text
JAVA_HOME=$(/usr/libexec/java_home -v 21)
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
TESTCONTAINERS_RYUK_DISABLED=true
```

- Exact parser/query/web command: **24 tests**, 0 failures, 0 errors, 0 skipped.
- Exact execution/security command: **19 tests**, 0 failures, 0 errors, 0 skipped.
- Exact PostgreSQL/inherited critical command: **55 tests**, 0 failures, 0 errors,
  0 skipped.
- Hostile network plus real TLS wire and intent matrix: **29 tests**, 0 failures,
  0 errors, 0 skipped.
- Full Maven suite: **179 tests**, 0 failures, 0 errors, 0 skipped across 41 reports.
- `git diff --check`: PASS.
- Untracked-file trailing-whitespace scan: PASS.

Frozen V8 hashes are unchanged:

```text
190c0d9cf10ca687bafc0604325aaa47380ae91593b1dbc2d669e9ba27256461  production
fd9fd85cdb5c3abae103ec8b82f30e96f67ff332934b9d26666a51ea4839724d  H2
```

## Manual UAT Residuals

The following were not claimed as automated proof and remain assigned to integrated
Phase 13 browser/provider UAT:

- Visual quality and measured page-level overflow at desktop, 980px, 720px, and
  375px.
- Keyboard-only operation, actual HTMX focus movement, 200% zoom, and screen-reader
  wording judgment.
- Real Gmail metadata/authentication evidence and opening exact source messages in an
  authenticated provider session.
- Real public unsubscribe endpoints and observation of later mail suppression.

These are non-blocking for Phase 11 because semantic HTML, static responsive and
accessibility contracts, provider-free reads, exact request behavior, and all safety
boundaries are automated. No public endpoint was contacted.

## Residual Engineering Risks

- `/usr/libexec/java_home -v 21` resolves to OpenJDK 26.0.1 on this host, so the
  requested selector was used but an actual Java 21 runtime was unavailable.
- JVM DNS lookup cancellation is platform-dependent; workers and queue capacity are
  bounded, daemonized, and fail closed on timeout or saturation.
- IANA special-purpose address registries require periodic policy review.

## Repository State

No commit was created. The worktree remains intentionally dirty with the existing
Phase 9-13 work and this Phase 11 closure.
