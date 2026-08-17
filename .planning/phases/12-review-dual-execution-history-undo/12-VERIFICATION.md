---
phase: 12-review-dual-execution-history-undo
status: passed
verified: 2026-08-09
requirements_verified: 11
requirements_total: 11
full_suite: 231
failures: 0
errors: 0
skipped: 0
postgresql: 16.14
---

# Phase 12 Verification

## Verdict

**Phase 12 passes.** All eleven mapped requirements have positive requirement-prefixed
Surefire evidence and adversarial ownership, CSRF, stale-state, replay, privacy,
concurrency, and malformed-result coverage. W-07 is closed.

## Requirement Evidence

| Requirement | Positive evidence |
|---|---|
| SEND-06 | `SEND-06 sender mailbox and local actions use the shared ledger` |
| UNRD-07 | `UNRD-07 unread staged and immediate actions use one exact executor` |
| UNSB-07 | `UNSB-07 immediate unsubscribe retains Phase 11 safety and outcome truth` |
| ACTN-01 | `ACTN-01 staged and immediate archive use one exact executor and ledger` |
| ACTN-04 | `ACTN-04 grouped Review uses real owned facts and accessible markup` |
| ACTN-05 | `ACTN-05 selected cancellation and all-eligible approval are atomic` |
| ACTN-06 | `ACTN-06 exact conflicts are account-local and scope preserving` |
| ACTN-07 | `ACTN-07 partial and malformed provider facts remain truthful` |
| ACTN-08 | `ACTN-08 undo sends only forward succeeded ordinals` |
| ACTN-09 | `ACTN-09 remove protection is a linked independent compensation` |
| ACTN-10 | `ACTN-10 History filters legacy truth and provider-free rendering` |

Surefire's JUnit 5 phrased reporter writes these literal prefixes to
`target/surefire-reports/TEST-*.xml`; the final audit found **11/11**.

## PostgreSQL And Parity

- PostgreSQL **16.14** ran production V1-V9 from empty and a real V8-to-V9 upgrade.
- V8 metadata, scopes/actions, unsubscribe attempts/events/nonces, protection, and
  override-audit rows and IDs survived; identity allocation continued.
- Hibernate `ddl-auto=validate`, exact V9 constraints, composite ownership FKs,
  uniqueness rules, cascades, and named indexes passed.
- Independent PostgreSQL transactions prove one `FORWARD` and one `UNDO` execution,
  one account-scoped idempotency winner, stale lease CAS rejection after lock wait,
  exact account-local conflict overlap, and cross-account target independence.
- Equivalent H2/PostgreSQL fixtures return equal queue metrics, History metrics,
  action/mode/status facts, succeeded-target counts, outcome partition, and undo
  eligibility.
- A PostgreSQL-only defect found by parity testing was fixed: null History date
  parameters are now explicitly typed as `TIMESTAMP WITH TIME ZONE`.

## W-07 And Safety Matrices

- Undo: concurrent duplicate submission, consumed replay, pending-only rejection,
  exact inverse mapping, succeeded-ordinal-only selection, stale recovery, partial
  truth, and legacy/tampered/foreign rejection.
- Compensation: staged execution, replay/duplicate rejection, conflict no-write
  behavior, immutable original action, and stable original navigation.
- History: every action/status/account/date/target filter, combinations, `%`/`_`
  escaping, canonical links, 25/6 pagination, equal-time stable ordering, V8
  unsubscribe linkage, and compensation linkage.
- Web/security: 18 mutation routes reject unauthenticated and missing-CSRF requests
  before writes or provider/transport I/O.
- Privacy/UI: schema/model/DTO/HTML/URL allowlists, secret canaries, landmarks,
  labels, captions, live/alert regions, focus, safe external links, 980/720/375
  breakpoints, 44px controls, reduced motion, wrapping, and overflow containment.

## Performance And Regression

The deterministic PostgreSQL fixture contains **10,000 messages and 500 actions**.
Correctness and first/last identities pass before timing. Warm queue and History
queries each measured **0 ms at millisecond resolution (<1 ms)** and used the intended
V9 indexes; both budgets are under two seconds.

Final authoritative Maven result using Java **21.0.12**:

```text
Tests run: 231, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Phase 12 inventory: **51 tests** across the seven Phase 12 suites. Focused core:
29; focused web/security: 18; PostgreSQL plus inherited exactness: 28. All passed
with zero skips.

`git diff --check`: PASS.

Migration SHA-256 values:

```text
190c0d9cf10ca687bafc0604325aaa47380ae91593b1dbc2d669e9ba27256461  production V8
fd9fd85cdb5c3abae103ec8b82f30e96f67ff332934b9d26666a51ea4839724d  H2 V8
c092433908b88c3d6f7df912e09abd53348874b26af0c9d5163b35eb98ece379  production V9
8f5d6ad52bf4105af15bd5e3ca0e4d8d5d45352dd9d5d70f93e29621c2a1909f  H2 V9
```

## Residuals

No Phase 12 blocker remains. Real mailbox/provider mutation, live unsubscribe
endpoints, browser visual judgment, and public deployment were intentionally not
performed; integrated localhost provider/browser UAT remains Phase 13 scope.

No commit was created.
