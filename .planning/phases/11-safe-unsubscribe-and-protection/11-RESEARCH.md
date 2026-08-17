# Phase 11: Safe Unsubscribe And Protection - Research

**Researched:** 2026-08-09
**Scope:** Candidate detection, guarded unsubscribe transport, outcomes, persistence,
and verification architecture
**Confidence:** High for standards/security boundaries; medium for final class names

## Executive Summary

Phase 11 is not a mailbox-provider feature. Candidate discovery is a deterministic
query over Phase 9 metadata, and automated unsubscribe is a narrowly constrained
outbound HTTPS operation. Keeping those two responsibilities separate is the main
architectural requirement.

The safe implementation has four layers:

1. Parse and classify approved headers into immutable, explainable candidate facts.
2. Render canonical provider-free browse and destination-inspection surfaces.
3. Revalidate an opaque, short-lived intent immediately before an explicit method.
4. Execute through a redirect-disabled, DNS-pinned HTTP boundary and append a
   privacy-bounded outcome event.

RFC 8058 eligibility is stricter than the presence of two strings. The RFC requires
one HTTPS URI, the exact `List-Unsubscribe=One-Click` post argument, a valid DKIM
signature, and signature coverage of both unsubscribe headers. The current schema has
the first two inputs but no trusted DKIM pass/coverage facts. Phase 11 therefore needs
derived booleans at metadata ingestion; it must not persist raw authentication or
signature headers.

## Standards Findings

### RFC 2369 Parsing

RFC 2369 defines each list command field as one or more comma-separated,
angle-bracket-enclosed URLs in preference order. Internal whitespace introduced
inside brackets is to be ignored by clients, malformed sub-items stop processing of
the remainder, and only one field instance should exist. It also requires user
confirmation before a command is executed.

Planning consequences:

- Use a bounded state-machine/parser over unfolded header field values.
- Preserve method preference order but never interpret preference as permission to
  execute or silently fall back.
- Reject bare URLs, unsupported schemes, malformed angle-bracket structure, comments
  that create ambiguity, control characters, excessive item counts, and oversized
  components.
- Return structured parse facts and reason codes; never pass raw strings directly to
  templates or the HTTP client.

### RFC 8058 Eligibility And Request

RFC 8058 sections 3-5 require:

- One `List-Unsubscribe` field containing an HTTPS URI.
- One `List-Unsubscribe-Post` field containing the single key/value pair
  `List-Unsubscribe=One-Click`.
- At least one valid DKIM signature covering both `List-Unsubscribe` and
  `List-Unsubscribe-Post`.
- User consent before the receiver performs POST.
- No cookies, HTTP authorization, or prior browsing context.
- POST body equal to the post header pair.
- `multipart/form-data` SHOULD or `application/x-www-form-urlencoded` MAY.
- The sender must not return a redirect; therefore the client should treat every 3xx
  as failure.

TidyMail should use `application/x-www-form-urlencoded` because the payload is fixed,
small, deterministic, and shown in the RFC's simple example. A 2xx response proves
request acceptance, not independently verified suppression of future mail.

### DKIM Evidence Gap

Current `MessageMetadata` persists unsubscribe headers but not authentication facts.
Treating header presence alone as RFC 8058 eligibility would violate the RFC's
additional requirements.

Recommended ingestion contract:

- Expand the approved metadata request only for `Authentication-Results` and
  `DKIM-Signature`.
- Parse them in memory at the trusted provider ingestion boundary.
- Persist only:
  - `unsubscribe_dkim_pass BOOLEAN NOT NULL DEFAULT FALSE`
  - `unsubscribe_dkim_covers_list_unsubscribe BOOLEAN NOT NULL DEFAULT FALSE`
  - `unsubscribe_dkim_covers_list_unsubscribe_post BOOLEAN NOT NULL DEFAULT FALSE`
  - optional bounded `unsubscribe_auth_reason VARCHAR(32)` allowlisted reason code
- Discard raw values after mapping and never render them.

For Gmail, only provider-added/trusted authentication result context should be used.
If the implementation cannot distinguish a trusted result header, eligibility must
remain false. Do not implement cryptographic DKIM verification from scratch in this
phase.

## Candidate Model

### Source And Grouping

Build candidates at query time from `message_metadata`, `protected_senders`, and the
latest unsubscribe outcome. This avoids stale duplicated header data and keeps
candidate behavior tied to the indexed source.

Recommended identity:

```text
account_id
+ normalized_sender_address
+ normalized_list_id, or sender_address fallback
```

Select the newest message in each group with `ROW_NUMBER() OVER (PARTITION BY ...
ORDER BY received_at DESC NULLS LAST, id DESC)`. Parse its bounded unsubscribe arrays
in Java before presentation/execution. SQL should aggregate counts and select source
rows; URI parsing should not be encoded in dialect-specific SQL.

When the newest rows disagree on destination or method within a bounded recency
window, set `needs_review=true` and lower confidence. Do not combine tokenized URIs
from different messages.

### Candidate Facts

An immutable candidate row/view should contain:

- Opaque versioned candidate key.
- Account display label and owned account ID internally.
- Sender display name/address/domain and normalized list identity.
- Estimated trailing-30-day volume and newest activity.
- Available method set and recommended method.
- Confidence, review reason codes, protection state.
- Destination host and redacted path summary.
- Header-presence booleans and RFC 8058 eligibility facts.
- Latest outcome state/time and selected method, if any.

Never include the raw provider message ID, complete URI, URI query, raw header, or
authentication value in the presentation DTO.

### Filters And Sorts

Typed URL parameters:

| Parameter | Values |
|---|---|
| `account` | existing owned account selector or `all` |
| `method` | `all`, `rfc8058`, `legacy_get`, `mailto`, `manual` |
| `volume` | `all`, `10`, `20`, `50`, `100` minimum monthly estimate |
| `confidence` | `all`, `high`, `medium`, `review` |
| `review` | `all`, `ready`, `needs_review` |
| `age` | `all`, `7d`, `30d`, `90d`, `older_90d` |
| `protected` | `exclude`, `include`, `only` |
| `state` | `all`, `not_attempted`, plus each UNSB-08 state |
| `q` | bounded sender/address/domain/list-id search |
| `sort` | `volume_desc`, `confidence_desc`, `method`, `newest`, `oldest`, `review`, `sender`, `state` |
| `page` | positive bounded page, reset on query-shape changes |

Use fixed 25-row pages and explicit tie-breakers ending in normalized sender/list key,
account ID, then source metadata ID. Classification occurs before filtering and
paging. Capture one request-scoped `Instant`.

## Parsing And Destination Inspection

### URI Parser Rules

Parse once with `java.net.URI` after strict prechecks, then canonicalize:

- Maximum complete field, URI, host, path, and query sizes.
- Reject CR/LF/NUL, other controls, backslashes, malformed percent encodings, and
  Unicode normalization ambiguity.
- Convert DNS names through `IDN.toASCII(..., USE_STD3_ASCII_RULES)`, lowercase using
  `Locale.ROOT`, remove one terminal dot, and reparse.
- Automated HTTPS permits no user-info, fragment, or non-default port.
- Reject IP literals for automated methods, including IPv4, bracketed IPv6, mapped
  IPv6, and unusual numeric forms.
- `mailto:` permits one bounded recipient and allowlisted `subject`/`body` parameters;
  opening remains client-side.
- Never decode and re-encode query values for execution. The validated original URI
  bytes remain server-side; only a fingerprint and redacted summary cross boundaries.

### Inspection DTO

Show:

- Method and whether it is automated or manual.
- Normalized destination host.
- Redacted path shape and whether a query token exists.
- Presence of `List-Unsubscribe`, `List-Unsubscribe-Post`, and derived DKIM evidence.
- Why one-click is eligible/ineligible.
- Protected state and method-specific warning.

Do not show:

- Full URI/query values.
- Recipient address embedded in an unsubscribe URI.
- Raw DKIM/authentication headers.
- DNS addresses, internal rejection details, exception messages, or response bodies.

## SSRF And Transport Architecture

### Required Defense Sequence

For each POST or GET attempt:

1. Reload the owned source metadata and selected method.
2. Reparse and canonicalize the target.
3. Require absolute HTTPS/default port/no user-info/no fragment.
4. Resolve all A/AAAA addresses through an injected `DestinationResolver`.
5. Reject if resolution is empty, excessive, or contains any non-global address.
6. Build a request-scoped transport pinned to exactly the validated addresses.
7. Connect with original hostname retained for SNI and certificate hostname checks.
8. Disable redirects and proxies; use direct HTTPS only.
9. Enforce bounded DNS/connect/TLS/header/total timeouts and response discard limit.
10. Classify status; append safe outcome; close the request-scoped transport.

Rejecting mixed public/private answer sets matters. Choosing only the public member
allows an attacker to change which answer the client uses. Resolving once and then
letting the HTTP library resolve again leaves a DNS-rebinding time-of-check/time-of-use
gap.

### Address Policy

Centralize address classification and test IPv4 and IPv6. Reject at minimum:

- Unspecified/any-local and loopback.
- RFC 1918 private and IPv6 unique-local/site-local.
- IPv4/IPv6 link-local, including cloud metadata ranges.
- Multicast.
- Carrier-grade NAT/shared space.
- Benchmark, documentation, reserved, and future-use blocks.
- IPv4-mapped IPv6 when the mapped address is non-global.

Use an explicit global-unicast policy rather than only Java's
`isSiteLocalAddress()`, which is incomplete for SSRF.

### HTTP Boundary

Do not add unsubscribe methods to `MailProvider`. Recommended interfaces:

```java
interface DestinationResolver {
    ResolvedDestination resolveAndValidate(String asciiHost);
}

interface UnsubscribeHttpTransport {
    TransportResponse execute(ValidatedUnsubscribeRequest request);
}

record ValidatedUnsubscribeRequest(
    URI uri,
    String tlsHostname,
    List<InetAddress> pinnedAddresses,
    Method method,
    byte[] body
) {}
```

The application service owns consent, account/protection checks, intent verification,
and outcome persistence. The transport owns only direct HTTPS mechanics. A production
implementation likely needs Apache HttpClient 5 or an equivalent client that supports
a request-scoped/custom DNS resolver while preserving TLS hostname verification.
Java's default `HttpClient` does not expose a clean per-request pinned DNS resolver;
validation followed by default lookup is not sufficient.

Use a request-scoped client or pool partition that cannot reuse a connection validated
for another intent. Redirect handling must be `NEVER`. Proxy environment variables
must not silently reroute requests.

## Intent And Execution Flow

### Inspect

`GET /unsubscribe/candidates/{opaqueKey}`:

- Authenticate and resolve current owned account scope.
- Reload and parse source metadata.
- Render safe destination facts and method choices.
- Make no external request and persist no `PENDING` attempt.

### Confirm

Inspection issues a short-lived server-signed intent containing candidate fingerprint,
method, destination fingerprint, account ID, issued/expiry time, and random nonce.
The browser receives only the opaque signed value.

`POST /unsubscribe/execute`:

- CSRF required.
- Accept opaque candidate key, selected method, and intent only.
- Verify signature, expiry, one-time nonce, ownership, and current fingerprints.
- Recheck protected state and all safety rules.
- Persist `PENDING` before outbound I/O.
- Execute once; do not retry an irreversible request automatically.
- Append terminal event(s) with safe codes.

An ambiguous timeout remains `FAILED` with `TIMEOUT_UNKNOWN_REMOTE_RESULT`, not a
retry. The UI can recommend verification/manual review.

### Manual Alternatives

- `mailto:` is a validated link with `rel`/copy appropriate to an external application.
  A separate CSRF POST may record `MANUAL_REQUIRED`/`VERIFICATION_PENDING` after the
  user chooses it; merely rendering the link records nothing.
- Provider deep link opens the exact source message and never calls the provider.
- A user may record `USER_CONFIRMED` after completing a manual path. Label it
  "Confirmed by you"; do not equate it with server verification.

## Outcome Persistence

Use append-only attempt and event tables, for example:

### `unsubscribe_attempts`

- `id`, `account_id`, `candidate_fingerprint`, `sender_key_fingerprint`
- `method`, `destination_host`, `destination_fingerprint`
- `created_at`, `completed_at`
- `latest_status`, `http_status_class`, `reason_code`
- `confirmation_source`

### `unsubscribe_outcome_events`

- `id`, `attempt_id`, `status`, `occurred_at`
- `reason_code`, `http_status_class`, `confirmation_source`

Constraints:

- Allowlisted methods/statuses/reasons/sources.
- Host <=253; fingerprints fixed SHA-256 format; reason <=48.
- `http_status_class` is null or 2-5, not raw response content.
- Account-scoped foreign key and cascade behavior are explicit.
- No mutable generic JSON and no full URI/header/body/provider ID.
- Update of old events is prohibited at service level; PostgreSQL trigger protection
  is optional if the project does not already use triggers.

Lifecycle:

```text
PENDING -> ACCEPTED -> VERIFICATION_PENDING
PENDING -> FAILED
candidate/no safe method -> MANUAL_REQUIRED
MANUAL_REQUIRED -> VERIFICATION_PENDING
VERIFICATION_PENDING -> CONFIRMED (with source)
```

`ACCEPTED` remains an event even when latest status becomes
`VERIFICATION_PENDING`, allowing the UI to show both accurately.

## Schema And Dialect Design

Create equivalent V8 migrations:

- PostgreSQL arrays/booleans/timestamps use existing production conventions.
- H2 test migration uses behaviorally equivalent types and checks.
- Add derived DKIM columns to `message_metadata` with safe false defaults so V1-V7
  upgrades preserve rows.
- Add outcome tables and indexes.
- Update Hibernate entities and migration-count assertions.

PostgreSQL indexes should support:

- Candidate grouping/latest source:
  `(account_id, from_address, received_at DESC, id DESC)` and list identity as needed.
- Header-bearing candidates through a partial index where practical.
- Outcome display:
  `(account_id, latest_status, created_at DESC, id DESC)`.
- Attempt events:
  `(attempt_id, occurred_at, id)`.

Do not force identical index syntax in H2 when partial/include indexes are unsupported;
assert equivalent query behavior and required named indexes per dialect.

## UI Architecture

- Extend the sole workspace GET owner and typed route parser used by Phase 10.
- Full page and result fragment use the same authorized query object.
- Inspection is full-page first and an HTMX fragment enhancement second.
- Confirmation is a separate form with explicit method and destination host.
- Outcome timeline/list is server rendered from append-only events.
- Shared workspace readiness remains visible; partial indexed data is usable but
  labeled.
- Browser-open alternatives announce external navigation/new application.

The existing mock is a composition reference. Replace sample metrics with filtered
facts:

- Matching candidates.
- RFC 8058 eligible.
- Manual/review required.
- Protected or verification-pending, with explicit denominator.

"Monthly reduction" is not a Phase 11 fact because executing an unsubscribe does not
prove future reduction.

## Testing Architecture

### Pure Unit Tests

- RFC 2369 parser fixtures: folded headers, ordering, commas in invalid positions,
  comments, malformed brackets, bare URLs, controls, size bounds.
- RFC 8058 classifier matrix: pair presence, exact post value, multiple values, HTTPS,
  DKIM pass, and both coverage booleans.
- URI canonicalizer: IDN, terminal dot, user-info, ports, fragments, encoded
  authorities, backslashes, controls, IPv4/IPv6/numeric forms.
- IP policy: every prohibited IPv4/IPv6 category plus public controls.
- Outcome transition and copy mappings.

### H2 Integration And MVC

- V1-V8 migration and Hibernate validation.
- Candidate grouping, latest-source choice, confidence/method/review classification,
  every filter/sort, stable pages, and account isolation.
- Protected rows visible/filterable but never executable.
- Inspect GET has zero provider and transport interactions.
- CSRF/auth/ownership/stale-intent/replay protection.
- Write-before-I/O `PENDING`; append accepted/verification/failed events.
- Privacy reflection/schema/template/log assertions.
- Full and fragment rendering, accessibility, canonical URLs, and no Phase 12 controls.

### PostgreSQL 16 Testcontainers

- Upgrade production migrations from V7 with row preservation.
- Hibernate `ddl-auto=validate`.
- Check constraints, foreign keys, defaults, indexes, identity sequences.
- Candidate query parity and deterministic tie ordering.
- Append-only outcome ordering and concurrent intent/nonce behavior.
- Query plan/budget on a deterministic 10,000-row dataset if Phase 11 adds material
  query complexity; reuse Phase 10's under-two-second supported-dataset expectation.

### Security Transport Tests

Use three layers:

1. Pure resolver/address-policy tests with injected DNS answers.
2. Transport contract tests with a fake connector asserting pinned addresses, original
   TLS hostname, redirect policy, body, headers, timeouts, and response cap.
3. Narrow integration tests using a test transport factory and controlled TLS server,
   without adding a production "allow localhost" switch.

Required attacks:

- `localhost`, `127.0.0.1`, `0.0.0.0`, RFC 1918, CGNAT, link-local/cloud metadata.
- `::1`, unique-local, mapped IPv4, multicast/reserved/documentation ranges.
- Public hostname returning one private answer.
- Resolver returns public at validation then private on a hypothetical second lookup;
  assert the connector receives the first pinned set and performs no lookup.
- 301/302/303/307/308 to public and private targets; assert no second request.
- User-info/parser confusion, alternate numeric IP forms, IDN ambiguity, non-HTTPS.
- Timeout after request dispatch produces unknown-result failure and no retry.
- Oversized response is bounded/discarded and never persisted.

## Requirement-To-Architecture Map

| Requirement | Primary implementation evidence |
|---|---|
| UNSB-01 | Candidate query/view, ranking fields, selected/latest outcome, stable sorts |
| UNSB-02 | Strict typed query and positive/exclusion case for every filter |
| UNSB-03 | Safe inspection DTO/page with redacted host/header/auth facts |
| UNSB-04 | Eligibility matrix, exact POST, consent intent, accepted outcome |
| UNSB-05 | Separate explicit legacy GET flow using identical destination policy |
| UNSB-06 | Validated mailto and provider/account-page browser alternatives |
| UNSB-08 | Append-only events and UI coverage for all six outcome states |
| QUAL-01 | URI/IP policy, pinned DNS, disabled redirects, security matrix |

## Risks And Mitigations

| Risk | Mitigation |
|---|---|
| Header presence misrepresented as RFC compliance | Require derived DKIM pass and coverage facts |
| DNS rebinding between validation and fetch | Pin validated addresses into actual connector |
| Redirect escapes validation | Disable redirects; classify every 3xx as failure |
| Timeout causes duplicate unsubscribe on retry | No automatic retries; unknown-result reason |
| URI token leaks through UI/log/history | Host/fingerprint/redacted path only |
| Protected sender unsubscribed through old override path | Dedicated Phase 11 hard block |
| Candidate changes between inspect and execute | Signed expiring fingerprinted intent plus revalidation |
| 2xx shown as confirmed | `ACCEPTED` then `VERIFICATION_PENDING`; confirmation source required |
| Phase 12 scope pulled forward | No queue, policy toggle, saved selection, Review action, or shared execution |

## Sources

- RFC 8058, *Signaling One-Click Functionality for List Email Headers*:
  https://www.rfc-editor.org/rfc/rfc8058.html
- RFC 2369, *The Use of URLs as Meta-Syntax for Core Mail List Commands*:
  https://www.rfc-editor.org/rfc/rfc2369.html
- OWASP, *Server-Side Request Forgery Prevention Cheat Sheet*:
  https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html
- Project Phase 9/10 artifacts and current source listed in `11-CONTEXT.md`.

## Planning Recommendations

Split implementation planning by invariant, not by page:

1. Migration, derived authentication facts, parser/classifier, and candidate query.
2. Canonical browse/inspection UI with protected and privacy contracts.
3. Intent, append-only outcomes, and provider-neutral guarded transport.
4. H2/PostgreSQL/security verification and regression.

Transport security should be complete before any real execution endpoint is enabled.
