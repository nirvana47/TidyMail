---
phase: 11-safe-unsubscribe-and-protection
status: planned
requirements:
  - UNSB-01
  - UNSB-02
  - UNSB-03
  - UNSB-04
  - UNSB-05
  - UNSB-06
  - UNSB-08
  - QUAL-01
automated_architecture:
  - pure-unit
  - h2-integration
  - mockmvc
  - postgres-16-testcontainers
  - security-transport-contract
manual_browser_blocking: false
---

# Phase 11: Validation Strategy

## Validation Goal

Prove that Phase 11 detects and explains real metadata-derived unsubscribe candidates,
executes only the method the user explicitly confirmed, never crosses a forbidden
network boundary, and records only what actually happened.

Full-suite green is necessary but insufficient. Every requirement needs positive,
requirement-specific evidence plus negative privacy, ownership, provider-isolation,
and Phase 12-boundary evidence.

## Test Layers

### Layer 1: Pure Deterministic Tests

No Spring context or network.

Target components:

- RFC 2369 parser.
- RFC 8058 eligibility classifier.
- URI canonicalizer/redactor.
- IP/global-unicast policy.
- Candidate confidence/method/review classifier.
- Versioned candidate key and intent fingerprint/signature.
- Outcome state/event rules and safe copy mapping.

Properties:

- Table-driven fixtures with named cases.
- One fixed clock per test.
- Locale-independent normalization.
- No DNS or HTTP hidden inside URI parsing.
- Fuzz/property cases for malformed bracket/comma/percent/Unicode/control input.

### Layer 2: H2 Migration, Repository, Service, And MVC

H2 is the fast exhaustive behavioral dialect.

Cover:

- V1-V8 clean migration and V7-to-V8 upgrade fixtures.
- Hibernate schema validation.
- Derived authentication fact defaults and round trips.
- Candidate grouping, list/sender fallback identity, latest source, metrics, filters,
  sorts, stable pages, account isolation, and protection joins.
- Inspection and explicit method forms.
- Intent expiry/replay/staleness.
- Write-before-I/O attempts and append-only events.
- Full/fragment rendering, canonical URL state, accessibility, and privacy.
- Zero mailbox-provider calls on all candidate/inspection/outcome paths.

H2 must not be used as proof that PostgreSQL DDL, indexes, or query plans are valid.

### Layer 3: PostgreSQL 16 Testcontainers

Run against production Flyway migrations and the same repository SQL.

Cover:

- V7 fixture upgrade preserving metadata and ownership rows.
- V8 columns/tables/defaults/checks/foreign keys/indexes/sequences.
- Hibernate `ddl-auto=validate`.
- Candidate SQL parity with H2 for every method/confidence/protection class.
- Null ordering and deterministic tie behavior.
- Append-only event ordering and concurrent one-time intent/nonce handling.
- Deterministic 10,000-row correctness and warmed query budget if candidate SQL is on
  the route request path.

The test must force Spring and Flyway onto the exact Testcontainers JDBC URL and assert
that URL, following Phase 9's fixed migration test pattern.

### Layer 4: Security Transport Contract

Production safety is tested through injected boundaries, not by adding a localhost
escape hatch.

Boundaries:

```text
DestinationResolver
PublicAddressPolicy
UnsubscribeHttpTransport
TransportFactory / pinned connector
Clock / nonce store
```

Evidence must prove both:

1. Unsafe destinations are rejected before any connector call.
2. The connector uses only the exact validated address set while retaining the
   original hostname for TLS verification.

### Layer 5: Full Regression

Run all Phase 9/10 suites that enforce:

- Metadata privacy and idempotency.
- PostgreSQL migration continuity.
- Workspace shell behavior.
- Protected-sender semantics.
- Provider-free decision surfaces.
- Exact action-scope boundaries and rejection of unsupported queue kinds.

Phase 11 must not weaken those contracts.

## Required Test Suites

Suggested class ownership; final names may follow local conventions:

| Suite | Purpose |
|---|---|
| `Phase11UnsubscribeParserTest` | RFC 2369/8058, URI, redaction, classifier |
| `Phase11CandidateQueryTest` | H2 migration, candidate facts, filters/sorts/pages |
| `Phase11UnsubscribeWebTest` | Full/fragment/inspection/outcome MVC and UI contract |
| `Phase11UnsubscribeExecutionTest` | Intent, protection, persistence, transport boundary |
| `Phase11UnsubscribeSecurityTest` | IP classes, DNS pinning/rebinding, redirects, limits |
| `Phase11PostgresMigrationTest` | PostgreSQL V8, parity, constraints, indexes, budget |

Avoid one monolithic test class. Parser and security matrices should run without the
Spring context.

## Detailed Acceptance Matrix

### UNSB-01: Browse And Rank Candidates

Positive evidence:

- Candidate fixture with RFC POST, legacy GET, mailto, manual-only, protected, and
  prior-outcome rows renders every required field.
- Trailing-30-day volume is exact and excludes older rows.
- Confidence and method derive from source metadata and auth facts.
- Latest outcome/selected method is visible without changing candidate identity.
- Each required sort is tested with ties and nulls across at least two pages.
- Repeating the same query returns identical row order and opaque keys.

Negative evidence:

- No body/snippet/attachment/provider fetch contributes to ranking.
- No raw URI/provider message ID is returned by DTO or HTML.
- A candidate with ambiguous/malformed headers is not promoted to an automated method.

### UNSB-02: Candidate Filters

For each method, volume, confidence, review, age, account, protected, and outcome
filter:

- At least one positive fixture included.
- At least one near-match excluded.
- Canonical query parse/render round trip.
- Page resets on query-shape change.
- Pagination preserves complete normalized state.

Unknown parameters, duplicate recognized parameters, malformed values, foreign
accounts, and unsupported providers fail closed before repository execution.

### UNSB-03: Destination Inspection

Positive evidence:

- Inspection shows normalized destination host, method, redacted path/query presence,
  detected header facts, DKIM pass/coverage facts, confidence, and protection.
- Multiple available methods remain separate choices in RFC preference order.
- Inspection GET performs no HTTP or provider call.
- Full page and HTMX fragment return the same facts from one service path.

Privacy evidence:

- Tokenized URL/query, raw headers, mailbox address from URI, DNS answers, provider ID,
  and exception detail are absent from model and rendered HTML.
- Safe bounded destination host and SHA-256 fingerprint are allowed.

### UNSB-04: RFC 8058 POST

Eligibility matrix includes:

| HTTPS URI | Exact post pair | DKIM pass | Covers both | Eligible |
|---|---:|---:|---:|---:|
| yes | yes | yes | yes | yes |
| no/non-HTTPS | yes | yes | yes | no |
| yes | missing/wrong/multiple | yes | yes | no |
| yes | yes | no/unknown | yes | no |
| yes | yes | yes | one/both missing | no |

Execution evidence:

- Authenticated CSRF-protected explicit submit.
- Signed intent matches account, candidate, method, and destination fingerprint.
- `PENDING` exists before transport invocation.
- Exactly one POST; no retry.
- Exact body bytes `List-Unsubscribe=One-Click`.
- Content type `application/x-www-form-urlencoded`.
- No cookie, authorization, referrer, provider token, or mailbox data header.
- 2xx appends `ACCEPTED`, then `VERIFICATION_PENDING`; never `CONFIRMED`.
- 3xx, 4xx, 5xx, timeout, TLS, and I/O failures append safe failed events.

### UNSB-05: Explicit Legacy HTTPS GET

Positive evidence:

- Offered only for a structurally valid HTTPS destination when POST is unavailable or
  separately chosen.
- Inspection and confirmation say `Legacy HTTPS GET`.
- One explicit GET executes through the same resolver/address/transport policy.
- No body and no fallback method.
- 2xx is `ACCEPTED`/`VERIFICATION_PENDING`, not page-content confirmation.

Negative evidence:

- Rendering or inspecting never triggers GET.
- POST failure never triggers GET.
- Redirect never triggers a second request.
- Non-HTTPS URI is never offered.

### UNSB-06: Mailto And Account-Page Fallbacks

Positive evidence:

- Valid `mailto:` preserves one bounded recipient and allowed subject/body intent,
  opens the client, and never auto-sends.
- Existing provider deep link uses exact source identity and provider-free construction.
- Valid external account-page navigation shows inspected host and new-tab behavior.
- Manual completion records its explicit source.

Negative evidence:

- Unsupported schemes, multiple/malformed recipients, controls, credentials, and
  overlong values render `MANUAL_REQUIRED`.
- Browser-open fallback causes no server HTTP request.
- Raw mailto body/query and provider message ID do not enter outcomes/logs.

### UNSB-08: Honest Outcomes

Positive evidence for all required states:

- `PENDING`: persisted before transport, rendered as not complete.
- `ACCEPTED`: only from 2xx, with disclaimer.
- `CONFIRMED`: source required; Phase 11 fixture is clearly user-confirmed/manual.
- `FAILED`: reason code and bounded next step.
- `MANUAL_REQUIRED`: no eligible automated method/protected/manual path.
- `VERIFICATION_PENDING`: follows accepted/manual completion without independent proof.

Persistence evidence:

- Events are append-only and chronologically stable.
- Latest status is derived/updated without deleting earlier facts.
- A second attempt creates a new attempt; it does not rewrite the first.
- Timeout has `UNKNOWN_REMOTE_RESULT` and is not automatically retried.
- HTTP response body and raw exception are never persisted.

### QUAL-01: SSRF, DNS Rebinding, And Redirect Safety

Scheme/authority rejection:

- `http`, `file`, `ftp`, `gopher`, `data`, scheme-relative, relative, and malformed URI.
- User-info, fragment, non-default port, backslash, controls, encoded authority
  confusion, invalid IDN, and overlong components.
- IPv4, IPv6, mapped IPv6, decimal/octal/hex/short numeric literals.

Address rejection:

- `0.0.0.0/8`, `127.0.0.0/8`, RFC 1918, CGNAT, link-local/cloud metadata,
  documentation, benchmark, multicast, reserved/future-use.
- IPv6 unspecified, loopback, link-local, unique-local/site-local, multicast,
  documentation, and mapped prohibited IPv4.
- Empty DNS answer and excessive answer count.
- Mixed public plus any prohibited answer rejects the whole destination.

Rebinding/pinning:

- Resolver called exactly once per attempt.
- Validated public address set is passed into connector.
- Connector performs no second DNS lookup.
- Original normalized hostname is used for SNI/certificate verification and Host.
- Simulated DNS change after validation cannot change connector address.

Redirects and limits:

- 301, 302, 303, 307, and 308 each produce failure with one request total.
- `Location` to private, public, non-HTTPS, or malformed targets is never fetched.
- DNS/connect/TLS/header/total timeouts are bounded.
- Response discard/body limit is enforced; body is never parsed/persisted.
- No automatic retry after request dispatch.

## Protected-Sender Matrix

| Situation | Expected behavior |
|---|---|
| Default browse | Protected excluded, metric remains truthful |
| `protected=include` | Row visible, badge/reason visible |
| `protected=only` | Only protected rows visible |
| Inspection | Methods/facts visible, execution unavailable |
| Crafted execute POST | 409/422 safe block, no attempt or transport call |
| Protection added after inspect | Intent revalidation blocks; no network |
| Phase 10 override audit exists | Still blocked; override does not transfer |
| Protection removed legitimately | New inspection/intent required |

## Schema And Privacy Assertions

### Allowed Persisted Facts

- Account foreign keys.
- Candidate/sender/destination fingerprints.
- Normalized destination hostname.
- Method/status/reason/source enums.
- Timestamps and HTTP status class.
- Derived DKIM pass and coverage booleans.

### Forbidden Persisted/Rendered Facts

- Full unsubscribe URI or query.
- Raw `List-Unsubscribe`, `List-Unsubscribe-Post`, `DKIM-Signature`, or
  `Authentication-Results` in new outcome/candidate tables.
- Response body/title/HTML.
- Cookies, authorization, provider/OAuth tokens.
- Provider message/thread ID in candidate/outcome HTML.
- Raw DNS addresses or exception stack/message.
- Generic JSON/blob for arbitrary remote data.

Tests:

- Entity reflection for forbidden field names/types.
- Information-schema column allowlist in H2 and PostgreSQL.
- Rendered HTML/model string absence with secret canary values.
- Captured log absence using token/query/exception canaries.
- Repository round-trip proves only allowed fields.

## Provider-Neutral Boundary Assertions

- Candidate query, browse, inspection, manual link, outcome read, and security
  validation services have no `MailProvider` field/constructor dependency.
- Gmail and Outlook provider mocks receive zero interactions for every Phase 11 test.
- `UnsubscribeHttpTransport` receives only validated request records, never
  `UserAccount`, OAuth client, provider token, or `MailMessage`.
- `MailProvider` interface remains unchanged.
- Provider deep links remain pure URI construction.

## Phase 12 Boundary Assertions

Rendered HTML and controllers must have no Phase 11 path that:

- Saves a multi-candidate selection.
- Sends unsubscribe candidates to Review.
- Selects staged/immediate policy.
- Creates/queues a `UserAction`.
- Approves/applies a shared action scope.
- Silently executes all matching/visible candidates.

Existing Phase 9/10 queue endpoints reject any premature unsubscribe target kind.
Phase 11 may persist only unsubscribe attempts/events from a single explicit method
confirmation.

## Accessibility And Responsive Validation

Automated rendered/static assertions:

- One `main`/`h1`, skip link, active nav, table caption, scoped headers.
- Labels/legends for all filters and sort.
- Visible text for method/confidence/protection/outcome/reasons.
- Native disabled protected/ineligible controls with `aria-describedby`.
- Polite result/outcome live regions and alert security failures.
- External app/new-tab announcement and noopener/noreferrer.
- Focus target after HTMX result.
- Existing 980/720/375 breakpoints, 44px targets, focus outline, reduced motion, and
  overflow containment declarations.
- No `href="#"`, inline remote assets, or JavaScript-only execution.

Manual Phase 13 integrated browser UAT remains responsible for:

- Actual 375px page-width measurement.
- Desktop/980/720/375 visual quality.
- Keyboard-only flow and focus after real HTMX swaps.
- 200% zoom and screen-reader wording judgment.
- Real Gmail metadata/auth evidence and real public unsubscribe endpoints.

These manual items do not replace Phase 11's build-failing safety tests.

## Performance And Determinism

- Candidate/result GETs remain provider- and transport-free.
- On a deterministic 10,000-row supported dataset, warm candidate repository and
  fragment requests target the inherited under-two-second indexed-result budget.
- Correct expected count and first/last identities are asserted before timing.
- Parser work is bounded by header count/length and candidate page size.
- DNS answer count, response discard bytes, and all network timeouts are bounded.
- Timing tests report diagnostics but do not hide correctness failures.

## Suggested Verification Commands

Use the project's required Java selector:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q \
  -Dtest=Phase11UnsubscribeParserTest,Phase11CandidateQueryTest,Phase11UnsubscribeWebTest test
```

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q \
  -Dtest=Phase11UnsubscribeExecutionTest,Phase11UnsubscribeSecurityTest test
```

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q \
  -Dtest=Phase9MetadataIndexTest,Phase9WorkspaceShellTest,Phase10DecisionQueryTest,Phase10SelectionMaterializationTest,Phase11PostgresMigrationTest test
```

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test
git diff --check
```

When Colima is required, export the active user socket exactly as established by the
Phase 9/10 PostgreSQL tests. No PostgreSQL test may silently skip.

## Requirement Traceability

| Requirement | Primary suites | Required result |
|---|---|---|
| UNSB-01 | parser, candidate query, web | Real ranked fields and stable sorts/pages |
| UNSB-02 | candidate query, web, PostgreSQL | Every filter positive/exclusion and URL replay |
| UNSB-03 | parser, web | Safe host/header/auth inspection before action |
| UNSB-04 | parser, execution, security | Strict eligible POST, exact request, honest 2xx |
| UNSB-05 | execution, security, web | Explicit guarded GET, no fallback/redirect |
| UNSB-06 | parser, web, execution | Explicit mailto/provider/account-page paths |
| UNSB-08 | execution, web, PostgreSQL | All six append-only honest outcome states |
| QUAL-01 | security, execution, PostgreSQL regression | Non-public/non-HTTPS/rebinding/redirect rejection |

## Completion Gate

Phase 11 can be marked complete only when:

- All eight mapped requirements have named positive automated evidence.
- PostgreSQL 16 migration/parity tests run with zero skips.
- Every hostile destination and redirect class is blocked before a second request.
- DNS pinning is proven at the actual connector boundary.
- Protected senders cannot execute through UI, crafted request, stale intent, or Phase
  10 override state.
- No raw URI/auth/response/provider secret enters schema, HTML, or logs.
- All six outcome states render with truthful semantics.
- Phase 12 controls and side effects remain absent.
- Focused and full regression suites pass.

## Planned Validation Status

| Area | Status |
|---|---|
| Requirement mapping | Complete |
| H2 architecture | Specified |
| PostgreSQL architecture | Specified |
| Security transport architecture | Specified |
| Privacy/schema assertions | Specified |
| Accessibility/responsive assertions | Specified |
| Phase 12 boundary assertions | Specified |
| Execution evidence | Pending Phase 11 implementation |
