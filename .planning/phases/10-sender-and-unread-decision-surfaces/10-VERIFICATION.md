---
phase: 10-sender-and-unread-decision-surfaces
status: passed
verified: 2026-08-09
verifier: independent
requirements_verified: 14
requirements_total: 14
automated_tests:
  focused_phase_9_10: 66
  full_suite: 131
  failures: 0
  errors: 0
  skipped: 0
manual_items_blocking: false
---

# Phase 10 Verification

## Verdict

Phase 10 passes. The current implementation satisfies the ROADMAP goal, all 14
mapped requirements, all four success criteria, the three plans and summaries, and
the UI specification's automatable contracts.

No verification-blocking code or test defect was found, so the verifier made no
implementation or test changes. This file is the only verification change.

## Requirement Evidence

| Requirement | Result | Positive implementation and test evidence |
|---|---|---|
| WORK-02 | PASS | `SenderQuery` and `UnreadQuery` parse bounded typed values and emit stable canonical maps used by full pages, result fragments, pagination, preview forms, and return links. `Phase10DecisionQueryTest` proves every value round-trips and malformed/duplicate/unknown values fail closed. `Phase10SenderUnreadWebTest` positively reloads canonical sender state and an exact unread sender/provider/sort fragment. |
| SEND-01 | PASS | The shared sender CTE searches normalized display name, address, and domain with bound parameters and escaped `LIKE` metacharacters. Query tests positively match normal and literal `%_!` searches; rendered tests expose the real search field and result data. |
| SEND-02 | PASS | `SenderQuery` and `DecisionSurfaceSql.senderFilters` implement time window, owned account, type, risk, unread pressure, queue, and protected state. Integration tests iterate every filter enum, positively distinguish protected-only and account-specific results, and verify provider mocks receive no interactions. |
| SEND-03 | PASS | All six sender sorts run in SQL with deterministic account/sender tie-breakers and null placement. Query tests iterate every sort and prove disjoint stable pages; the Senders page renders all six real sort options. |
| SEND-04 | PASS | `SenderDecisionRow`, `DecisionClassifier`, and the sender result table expose volume, unread count/percentage, activity, deterministic type, recommendation, risk, protection, and visible plain-English reasons. SQL classifications are independently checked against `DecisionRuleSet.V1`; rendered tests positively assert representative values and reason copy. |
| SEND-05 | PASS | Individual, visible-page, and all-matching sender modes rerun the authorized classified predicate and materialize exact provider IDs into immutable account-owned scopes. Tests compare persisted IDs with expected IDs, exercise global page selection and account partitioning, and prove 1,000 succeeds while 1,001 returns 422 with no writes. |
| SEND-07 | PASS | Protection is normalized by `(account_id, sender_address)` and excluded in SQL for every selection mode. Tests positively show an unprotected scope plus exact excluded counts, then a CSRF-protected reasoned override containing all three expected IDs and a 64-character private audit fingerprint. Multi-account failure rolls back every scope and audit. |
| UNRD-01 | PASS | The unread CTE is fixed to `unread=true`; `UnreadDecisionRow` and the table expose sender, subject header, received time/age basis, thread state, waiting state, deterministic category, and recommendation. Rendered tests positively assert each field from seeded metadata. |
| UNRD-02 | PASS | `UnreadQuery` and `DecisionSurfaceSql.unreadFilters` implement one-to-one/list, age, waiting-on-me, thread shape, recommendation, owned account, exact sender, and allowlisted provider. Query tests iterate every enum and positively match waiting, list, account, and exact-sender cases. |
| UNRD-03 | PASS | Unread search covers display name, address, domain, and subject header through escaped bound SQL parameters. Canonical request tests preserve search state; integration tests query real indexed rows without provider calls. |
| UNRD-04 | PASS | `DecisionRuleSet.V1` emits stable visible reasons for reply-required, review, archive, trash, mark-read, and keep branches. Pure tests cover every recommendation and require nonblank copy; integration tests exercise every filter/recommendation branch; rendered tests show reason text without hover. |
| UNRD-05 | PASS | Individual, visible-page, and all-matching unread modes use the same authorized unread predicate as browsing. Tests positively materialize exact IDs for all three modes, prove stored scopes do not drift after metadata changes, and render ID-free exact count/hash previews. |
| UNRD-06 | PASS | `ProviderDeepLinkService` creates only validated HTTPS `mail.google.com` links, prefers thread ID, falls back to message ID, independently encodes account/item segments, and rejects traversal, controls, malformed Unicode, user-info, ports, queries, and unsupported providers. Tests parse positive links, prove fallback and encoding, and render the new-tab/noopener contract. |
| QUAL-03 | PASS | Pages and preview fragments provide visible labels and reasons, captions, scoped headers, named checkboxes, status/alert live regions, focus targets, external-link announcements, cap/protection explanations, and local responsive CSS. MockMvc positively renders these semantics. Static tests lock 980/720/375 breakpoints, 44px narrow targets, internal table scrolling, focus-visible, reduced motion, and local assets. Browser judgment remains listed below. |

## Roadmap Success Criteria

1. **Canonical sender state:** PASS. Search, all seven sender filter dimensions,
   six sorts, account, protection, queue, and pagination are represented by typed
   canonical query parameters and positively reconstructed in full/fragment GETs.
2. **Complete sender decisions:** PASS. Real rows expose volume, unread pressure,
   activity, type, recommendation, risk, protection, and deterministic reasons.
3. **Complete unread decisions:** PASS. Real rows expose metadata-only subject
   context, age basis, thread/waiting state, category, recommendation, reasons, and
   exact safe Gmail links.
4. **Exact protected selection:** PASS. All six surface/mode combinations use
   server-authoritative predicates. Caps are per account, protected rows are excluded
   server-side, and reasoned inclusion is private, audited, and atomic.

## Isolation, Privacy, And Phase Boundary

- Account ownership is resolved through `OwnedAccountScopeService`, rechecked by the
  query services, included in every base CTE, rejoined during materialization, and
  locked in ascending ID order before writes. Foreign account selectors and row keys
  fail closed; all-account scopes remain partitioned by account.
- Senders/Unread GETs and preview POSTs depend on repositories and classifiers, not
  `MailProvider`. Positive full-page, fragment, query, and preview tests verify zero
  Gmail and Outlook interactions.
- View models expose approved metadata and final validated provider URLs. Raw
  provider IDs never enter rendered HTML. Override audits allowlist only account,
  surface, mode, action, versioned query fingerprint, server counts, reason, and time.
- Phase 10 creates immutable preview scopes but no `UserAction` and performs no
  mailbox mutation. The pages render preview intents only. Crafted queue requests
  for `SENDER_SELECTION` and `UNREAD_SELECTION` fail with conflict, leave action
  counts unchanged, and invoke no provider. `KEEP` and `REVIEW` remain preview-only.
  Existing legacy Phase 7/9 queue/apply/undo endpoints are not exposed by these
  surfaces and do not accept the new scope kinds. `SEND-06` and `UNRD-07` therefore
  remain deliberately unimplemented for Phase 12.

## Migration And Query Parity

- Production PostgreSQL 16.14 ran real V1-V7 Flyway migrations. Phase 9 verified
  V5-to-V7 upgrade continuity; Phase 10 independently migrated V1-V6 then V7.
- Hibernate `ddl-auto=validate` passed against the migrated PostgreSQL schema.
- PostgreSQL and H2 tests cover equivalent V7 tables, allowlisted columns, checks,
  uniqueness, cascades, identities, indexes, empty/null-first arrays, browse queries,
  all six materialization modes, protected behavior, and cap rejection.
- Warm 10,000-row repository, full-page, and result-fragment requests each remained
  under the two-second indexed-result budget. Provider interaction counts stayed zero.

## Automated Results

Commands used `JAVA_HOME=$(/usr/libexec/java_home -v 21)` and explicit Colima
Testcontainers variables:

```text
DOCKER_HOST=unix://${HOME}/.colima/default/docker.sock
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

- Focused `Phase9*Test,Phase10*Test`: PASS, 66 tests, 0 failures, 0 errors,
  0 skipped.
- Dedicated `Phase9PostgresMigrationTest,Phase10PostgresMigrationTest`: PASS,
  2 tests, PostgreSQL 16.14, 0 failures, 0 errors, 0 skipped.
- Full Maven suite: PASS, 131 tests, 0 failures, 0 errors, 0 skipped.
- `git diff --check`: PASS.
- Untracked-file trailing-whitespace scan: PASS.

The macOS Java 21 selector resolves to OpenJDK 26.0.1 on this host. Verification
therefore used the requested selector but did not exercise an actual Java 21 runtime.

## Artifact Reconciliation

- Plan 10-01 claims are present in V7, canonical query records, shared classified
  CTEs, Java parity rules, account isolation, provider-free query services, and
  H2/PostgreSQL performance tests.
- Plan 10-02 claims are present in exact materialization, per-account caps,
  protected exclusion/override audit, repeatable-read atomic scope persistence,
  ID-free preview rendering, and fail-closed legacy queue handling.
- Plan 10-03 claims are present in the sole route owner, complete real controls,
  metadata tables, deterministic reasons, safe Gmail links, accessible fragments,
  responsive declarations, provider isolation, and request budgets.
- The UI specification's static and semantic contracts are automated. Its explicitly
  manual visual and assistive-technology checks remain residuals rather than being
  inferred from source text.

## Non-Blocking Manual UAT

The following require a real browser, provider session, or human judgment and were
not performed in this verification pass:

- Measure actual page-level overflow, internal table scrolling, and long-value
  wrapping at desktop, 980px, 720px, and 375px.
- Exercise keyboard-only flow, visible focus after HTMX replacement, focus return,
  sticky selection behavior, and 200% zoom.
- Judge screen-reader announcement quality for result and preview live regions.
- Open generated Gmail thread/message links in a signed-in account and confirm the
  provider resolves the intended target.

These residuals are non-blocking because Phase 10's automatable accessibility,
privacy, responsive, and URL contracts pass, while integrated browser UAT is
explicitly assigned to Phase 13.

## Repository State

No commit was created. The worktree remains intentionally dirty with pre-existing
Phase 9-11 implementation and planning work plus this verification document.
