# Phase 11: Safe Unsubscribe And Protection - Context

**Gathered:** 2026-08-09
**Status:** Ready for research and planning
**Mode:** Autonomous decisions; no user questions

<domain>
## Phase Boundary

Phase 11 delivers a complete metadata-only unsubscribe surface: deterministic
candidate detection, reproducible filtering and sorting, destination inspection,
explicit execution of one selected unsubscribe method, protected-sender blocking,
and durable honest outcomes.

Phase 11 owns:

- Parsing approved `List-Unsubscribe` and `List-Unsubscribe-Post` metadata.
- Deriving one safe candidate per account and unsubscribe identity.
- Browsing, filtering, sorting, inspecting, and selecting candidates.
- Executing a single explicitly confirmed RFC 8058 POST or guarded legacy GET.
- Opening explicit `mailto:` and browser/provider account-page alternatives.
- Persisting method-specific attempt and outcome facts.

Phase 12 owns:

- Staged-review versus opt-in immediate policy.
- Saving a multi-candidate selection or sending it to Review.
- Converting unsubscribe candidates into the shared immutable action-scope engine.
- Cross-action overlap/conflict handling, approval orchestration, and unified history.

Phase 11 must not add a policy toggle, queue an unsubscribe action, approve a Review
item, or silently route an unavailable method to another method. Its direct execution
flow is a method-specific single-candidate safety flow, not the Phase 12 policy engine.
</domain>

<decisions>
## Locked Decisions

### Candidate Identity And Detection

- A candidate is account-scoped and based only on indexed approved metadata. No
  message body, snippet, attachment, remote page content, or provider call is used by
  a browse GET.
- Parse RFC 2369 angle-bracket URI lists with a structured parser. Do not split on
  commas or angle brackets ad hoc. Malformed fields fail closed and remain inspectable
  as `MANUAL_REQUIRED`; parsing never guesses a destination.
- Group by `(account_id, normalized sender address, list identity)`. Prefer a
  normalized `List-ID`; otherwise use the normalized sender address. Within a group,
  the newest metadata row is the source snapshot. A versioned opaque candidate key
  identifies that row without exposing a provider message ID or URL.
- A candidate may advertise multiple explicit methods. Method priority is display
  guidance only: `RFC8058_POST`, `LEGACY_HTTPS_GET`, `MAILTO`, then
  `ACCOUNT_PAGE`/provider fallback. The user chooses the method; failure never
  auto-falls through to the next method.
- Estimated monthly volume is the count of matching indexed messages received in the
  trailing 30 days. It is labeled as an estimate and never projected from a shorter
  sample. Age is time since the newest matching message.
- Confidence is deterministic:
  - `HIGH`: syntactically valid RFC 8058 pair plus trusted DKIM pass/coverage facts,
    or a valid HTTPS legacy destination with stable list identity.
  - `MEDIUM`: valid `mailto:` or valid HTTPS destination without all one-click trust
    evidence.
  - `REVIEW`: malformed/ambiguous headers, conflicting current destinations,
    missing list identity, unsupported scheme, or protected state.
- Protected senders remain visible and filterable but are inspect-only. Their
  selection and execution controls are disabled with a visible explanation. Phase 11
  provides no unsubscribe override. Existing Phase 10 override audits do not grant
  permission for an irreversible unsubscribe.

### RFC 8058 Eligibility

- `RFC8058_POST` is eligible only when the same indexed message has:
  1. exactly one usable HTTPS URI selected from a syntactically valid
     `List-Unsubscribe` field;
  2. exactly one `List-Unsubscribe-Post` value equal to the case-sensitive
     `List-Unsubscribe=One-Click` token after permitted header unfolding/outer OWS;
  3. a provider-trusted DKIM pass result; and
  4. evidence that the passing DKIM signature covers both `list-unsubscribe` and
     `list-unsubscribe-post`.
- Authentication evidence is derived during metadata sync. Raw
  `Authentication-Results` and `DKIM-Signature` values are not persisted or rendered.
  If evidence is absent or ambiguous, POST is ineligible rather than assumed safe.
- POST sends no cookies, authorization, referrer, user-agent identity derived from the
  mailbox, or provider credentials. The body is exactly
  `List-Unsubscribe=One-Click` with
  `Content-Type: application/x-www-form-urlencoded`.
- Redirects are disabled. Any 3xx response is a failed attempt, never a followed
  request and never a GET conversion.

### Explicit Alternatives

- A valid HTTPS URI without complete RFC 8058 evidence may be offered as
  `LEGACY_HTTPS_GET`. It requires its own confirmation showing method, normalized
  destination host, account, sender/list identity, and the warning that the page may
  require more interaction.
- Legacy GET uses the same SSRF controls as POST. It never executes during inspection,
  link preview, page render, or method detection.
- `mailto:` is rendered only from a valid `mailto` URI. Opening it is an explicit
  browser/client action; Phase 11 does not send mail through a provider API. Display
  the recipient and whether subject/body parameters exist, but do not render tokenized
  query values into analytics, logs, or outcome records.
- Account-page fallback is an explicit browser navigation, preferably the existing
  provider deep link for the source message. A separately advertised HTTPS account
  page is allowed only after structural validation and host inspection. It is never
  fetched server-side unless the user explicitly chose guarded legacy GET.
- Unsupported schemes, credentials/user-info, fragments on automated targets,
  malformed IDNs, control characters, and ambiguous hosts produce
  `MANUAL_REQUIRED`.

### Destination Inspection And SSRF

- Inspection shows normalized ASCII host, method, HTTPS requirement, source header
  presence, confidence, protection state, and safety decision. It never displays the
  complete tokenized URI by default. A bounded path summary may be shown with query
  values redacted.
- Automated requests accept only absolute `https` URIs with a non-empty DNS host,
  default port 443, no user-info, and no fragment. Reject IP-literal destinations,
  noncanonical/ambiguous host syntax, backslashes, encoded authority confusion,
  control characters, and overlong URI/host/path/query components.
- Resolve all A and AAAA results immediately before each attempt. Reject the complete
  destination if any answer is loopback, unspecified, private/site-local, link-local,
  multicast, reserved/documentation/benchmark/CGNAT, IPv4-mapped non-public, or
  otherwise non-global.
- The actual TLS connection must be pinned to the validated address set while
  retaining the original hostname for SNI and certificate verification. Validation
  followed by an independent client DNS lookup is forbidden.
- Redirect following is disabled at the HTTP client. DNS resolution, connect, TLS,
  response-header, and total request timeouts are bounded. Response bodies are neither
  required nor persisted; read at most a small fixed discard limit before closing.
- Application validation is mandatory even when deployment egress controls exist.
  Network egress denial to local/private/cloud-metadata ranges is a defense-in-depth
  deployment recommendation, not a substitute for code.

### Execution And Honest Outcomes

- Confirmation is a CSRF-protected authenticated POST containing only an opaque
  candidate key, selected method, and a server-issued short-lived intent token. The
  server reloads owned metadata, reparses it, reruns protection and destination safety,
  and verifies the intent fingerprint before network I/O.
- Persist `PENDING` before attempting network I/O. Store bounded method, host,
  destination fingerprint, candidate fingerprint, account, sender/list summary,
  timestamps, HTTP status class, safe reason code, and confirmation source. Never
  store a full destination URI, raw headers, response body, provider ID, mailbox
  address embedded in a URI, cookies, credentials, or exception text.
- `ACCEPTED` means the remote endpoint returned 2xx. It does not mean future mail has
  stopped. The UI must say this explicitly.
- `VERIFICATION_PENDING` means an accepted automated request or user-completed manual
  path has no independent confirmation yet.
- `CONFIRMED` requires explicit evidence and records its source. In Phase 11 that may
  be a bounded, clearly labeled user attestation (`USER_CONFIRMED`) after a manual
  path; absence of new mail alone is not proof. Future automated reconciliation may
  add a stronger source without changing the status vocabulary.
- `FAILED` covers transport errors, timeout, non-2xx, redirects, stale intent,
  destination/protection revalidation failure, and security rejection. Safe reason
  codes distinguish them without leaking network details.
- `MANUAL_REQUIRED` is a candidate state/outcome for no eligible automated method,
  protected candidates, malformed headers, or a method requiring user navigation.
- Outcome records are append-only attempts/events. Never overwrite a prior accepted
  or failed fact to manufacture success.

### UI And URL State

- Reuse the Phase 9 shell, Phase 10 canonical GET-query pattern, semantic result
  tables, opaque row keys, shared workspace state, and local CSS.
- Every browse state is reproducible from the URL: account, method, minimum volume,
  confidence, review need, age, protected state, selected/outcome state, search, sort,
  and page. Unknown, duplicate, or invalid recognized parameters fail closed.
- Required sorts are estimated monthly volume, confidence, method, newest/oldest
  activity, review need, sender, and selected/outcome state, each with complete stable
  database tie-breakers.
- Row inspection is a real details route/fragment. Execution is never available from
  a single ambiguous table click; inspection and confirmation are distinct steps.
- The mock's ranked queue, metric strip, filters, status/method/recommendation fields,
  and side context are retained conceptually. Mock sample data, `href="#"`, fabricated
  impact claims, `Send to Review`, and `Save selection` are not implemented in Phase
  11.

### Schema And Privacy

- Add a dialect-equivalent Phase 11 migration after V7. PostgreSQL is production
  truth; H2 is the deterministic test dialect.
- Persist derived authentication booleans and bounded parser facts, not raw
  authentication headers. Existing approved raw unsubscribe header arrays remain the
  source record and must not be duplicated into candidate/outcome tables.
- Prefer query-time candidate derivation from `message_metadata` plus narrow durable
  attempt/outcome tables. Do not cache remote response content or create a generic
  header/JSON blob.
- All text columns have explicit bounds and allowlisted enums/check constraints.
  Foreign keys are account-scoped; ownership is checked before lookup details are
  returned. Indexes support account plus method/confidence/protection/activity and
  account plus outcome/created-at queries.
- Logs and templates use safe reason codes, hostnames, counts, and fingerprints only.
  URI query values and opaque unsubscribe tokens are secrets.

### Testing

- H2 covers exhaustive parser, classifier, query, MVC, lifecycle, privacy, and
  provider-isolation behavior.
- PostgreSQL 16 Testcontainers covers the production migration, constraints, indexes,
  Hibernate validation, query parity, stable ordering, and append-only outcome
  persistence.
- Security tests use injected DNS and provider-neutral transport boundaries. They
  prove loopback/private/link-local/IPv6/mapped/reserved rejection, mixed public and
  private answer rejection, rebinding resistance through pinned addresses, disabled
  redirects, redirect-to-private failure, TLS hostname retention, and timeout/body
  limits without weakening production validation for localhost fixtures.
- No Phase 11 browse or inspection GET calls `MailProvider`. Automated unsubscribe HTTP
  is isolated behind its own provider-neutral boundary and sends no mailbox/provider
  credentials.
</decisions>

<code_context>
## Existing Foundations To Reuse

- `MessageMetadata` already stores bounded arrays for `List-Unsubscribe`,
  `List-Unsubscribe-Post`, `List-ID`, sender identity, provider message/thread IDs,
  and approved classification metadata.
- `MessageMetadataMapper` and the Gmail metadata whitelist are the only approved
  ingestion boundary; derived DKIM facts must be added there without persisting raw
  authentication headers.
- `ProtectedSender` and Phase 10's normalized account/address join are the canonical
  protection source.
- `DecisionSurfaceSql`, `DecisionSurfaceQueryRepository`, strict query records, and
  versioned `DecisionRowKey` establish deterministic H2/PostgreSQL query and opaque-key
  patterns.
- `WorkspaceController`, `WorkspaceModelService`, shared workspace fragments, and
  `workspace.css` own the shell and account authorization.
- Phase 10's provider deep-link service/pattern is the preferred provider/account-page
  fallback and must remain provider-call-free.
- `MailProvider` is mailbox-domain functionality and must not be expanded with generic
  outbound HTTP. Introduce a separate unsubscribe transport boundary.

### Established Constraints

- Java 21, Spring Boot MVC, Thymeleaf, HTMX, PostgreSQL 16, Flyway, and H2 tests.
- Server-rendered HTML remains usable without JavaScript; HTMX only enhances fragments.
- No React pipeline or remote runtime UI dependency.
- Provider GET isolation, account ownership, exact server authority, bounded data,
  and metadata-only privacy are build-failing invariants.
- The current worktree is intentionally dirty; Phase 11 planning must not alter or
  revert any product file.
</code_context>

<canonical_refs>
## Canonical References

- `.planning/ROADMAP.md` - Phase 11 goal, requirements, success criteria, and Phase 12 boundary.
- `.planning/REQUIREMENTS.md` - UNSB-01..08 and QUAL-01 definitions.
- `.planning/PROJECT.md` - product, stack, privacy, and localhost constraints.
- `.planning/STATE.md` - inherited decisions and current implementation state.
- `.planning/phases/09-metadata-index-and-action-foundations/09-CONTEXT.md`
- `.planning/phases/09-metadata-index-and-action-foundations/09-RESEARCH.md`
- `.planning/phases/09-metadata-index-and-action-foundations/09-UI-SPEC.md`
- `.planning/phases/09-metadata-index-and-action-foundations/09-VERIFICATION.md`
- `.planning/phases/10-sender-and-unread-decision-surfaces/10-CONTEXT.md`
- `.planning/phases/10-sender-and-unread-decision-surfaces/10-RESEARCH.md`
- `.planning/phases/10-sender-and-unread-decision-surfaces/10-UI-SPEC.md`
- `.planning/phases/10-sender-and-unread-decision-surfaces/10-01-SUMMARY.md`
- `.planning/phases/10-sender-and-unread-decision-surfaces/10-02-SUMMARY.md`
- `tidymail-ux-rehaul/pages/unsubscribe-compact-suite.html` - visual/content reference, not source of truth.
- `src/main/resources/templates/unsubscribe.html` - current Phase 9 placeholder.
- `https://www.rfc-editor.org/rfc/rfc8058.html` - normative one-click POST and DKIM requirements.
- `https://www.rfc-editor.org/rfc/rfc2369.html` - normative structured list-header URI syntax and confirmation rule.
- `https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html` - SSRF defense-in-depth reference.
</canonical_refs>

<deferred>
## Deferred To Phase 12

- Staged Review versus immediate execution policy.
- Multi-candidate saved selections and immutable unsubscribe action scopes.
- Review approval/removal, cross-action conflict checks, and unified execution history.
- Any policy-level protected override.

## Deferred Beyond Phase 11

- Automated long-horizon confirmation based on sender/list behavior.
- Analytics, CSV export, and integrated browser UAT.
- Provider-specific unsubscribe APIs not represented by approved headers.
</deferred>

## Requirement Coverage

| Requirement | Context decision |
|---|---|
| UNSB-01 | Deterministic ranked candidates, fields, selected/outcome state, stable sorts |
| UNSB-02 | Canonical filters for method, volume, confidence, review, age, account, protection |
| UNSB-03 | Redacted destination inspection and detected-header evidence before confirmation |
| UNSB-04 | Strict RFC 8058 pair, DKIM evidence, explicit consent, exact POST contract |
| UNSB-05 | Separate confirmed legacy HTTPS GET with identical SSRF controls |
| UNSB-06 | Explicit validated `mailto:` and provider/account-page browser fallbacks |
| UNSB-08 | Append-only pending/accepted/confirmed/failed/manual/verification states |
| QUAL-01 | HTTPS-only pinned-DNS transport, non-public rejection, no redirects |

## Autonomous Resolution Note

The user requested autonomous planning inputs and no questions. Ambiguities were
resolved toward the narrowest behavior that satisfies the roadmap while preserving
privacy and preventing irreversible or network-sensitive actions from being inferred.
