---
phase: 12-review-dual-execution-history-undo
status: passed_with_warnings
verified: 2026-08-09
requirements_verified: 11
requirements_total: 11
full_suite: 231
failures: 0
errors: 0
skipped: 0
java: 21.0.12
postgresql: 16.14
---

# Phase 12 Independent Verification

## Verdict

**PASS WITH WARNINGS.** No production blocker or requirement failure was found.
All eleven Phase 12 requirements are traceable to executed positive and adversarial
tests. The full suite independently passed with zero failures, errors, or skips under
Java 21 and PostgreSQL 16 on Colima.

## Findings

### Warning W-01: The documented Java 21 command is not explicit on this host

`/usr/libexec/java_home -v 21` resolves to OpenJDK 26.0.1 in the current TRAE
environment. The suggested commands in `12-VALIDATION.md:562-582` therefore do not
guarantee Java 21 here.

Independent verification used:

```text
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

Surefire recorded `java.version=21.0.12`. This does not invalidate Phase 12, but the
verification command should use the explicit Homebrew path or first assert
`"$JAVA_HOME/bin/java" -version`.

### Warning W-02: Validation planning state is stale

`12-VALIDATION.md:3` still says `status: planned`, and line 632 says execution evidence
is pending implementation. This conflicts with `12-VERIFICATION.md`, five completed
summaries, `ROADMAP.md:76`, and `STATE.md:7,29,45`.

### Warning W-03: Requirements checklist and traceability disagree outside Phase 12

`REQUIREMENTS.md:61-62` leaves ACTN-02 and ACTN-03 unchecked, while
`REQUIREMENTS.md:159-160` marks both complete in Phase 9. These are not Phase 12
requirements and do not affect this verdict, but the milestone requirement state is
internally inconsistent.

## Requirement Traceability

| Requirement | Executed evidence | Result |
|---|---|---|
| SEND-06 | `Phase12ExecutionServiceTest`: sender mailbox/local shared ledger | PASS |
| UNRD-07 | `Phase12ExecutionServiceTest`: staged/immediate exact executor | PASS |
| UNSB-07 | `Phase12ExecutionServiceTest`: fresh Phase 11 safety and pending truth | PASS |
| ACTN-01 | `Phase12ExecutionServiceTest`: same scope/executor/ledger and no-I/O transaction | PASS |
| ACTN-04 | `Phase12ReviewWebTest`: seven real groups, owned facts, private accessible markup | PASS |
| ACTN-05 | `Phase12ReviewWebTest`, `Phase12MultiAccountTest`: atomic selected/all/cancel | PASS |
| ACTN-06 | `Phase12ReviewWebTest`, PostgreSQL race suite: exact account-local conflicts | PASS |
| ACTN-07 | execution, privacy, and PostgreSQL parity suites: truthful partitions/reasons | PASS |
| ACTN-08 | `Phase12UndoCompensationTest`: inverse request contains only forward succeeded ordinals | PASS |
| ACTN-09 | `Phase12UndoCompensationTest`: linked independent compensation, original unchanged | PASS |
| ACTN-10 | `Phase12ReviewWebTest`, PostgreSQL suite: strict durable History filters/order | PASS |

Surefire XML contains exactly one positive display-name prefix for each of the eleven
requirement IDs.

## Invariant Evidence

- **Executor uniqueness:** active forward commands and batch approvals enter
  `ExactScopeExecutor`; reservation is centralized in
  `ExecutionReservationService.reserveBatch`. Legacy numeric queue and blanket apply
  routes return `410 Gone`.
- **Exact scope:** dispatch resolves IDs from the immutable owned `ActionScope`;
  provider result classification cannot add foreign/duplicate success.
- **Applied-target-only undo:** `ActionExecutionTargetRepository` selects only
  `FORWARD`/`SUCCEEDED` rows. `UndoService` verifies ordinal fingerprints and sends
  only those IDs. The A/B succeeded, C failed, D pending test captures exactly A/B.
- **History and compensation truth:** legacy rows receive no fabricated execution
  facts; undo has an independent ledger; remove-protection creates a linked action and
  leaves the original unchanged; unsubscribe remains non-reversible and pending when
  merely accepted.
- **Privacy/security:** composite ownership FKs and bounded checks exist in V9.
  Auth/CSRF tests cover 18 mutation routes before I/O. New ledger DTO/entity/schema
  and rendered HTML allowlists exclude provider IDs, raw unsubscribe destinations,
  auth data, plaintext idempotency/lease values, response bodies, and exception text.
- **Zero skips:** 48 Surefire reports aggregate to 231 tests, 0 failures, 0 errors,
  0 skipped. The seven Phase 12 suites contribute 51 passing tests.
- **Runtime:** Maven compiled for release 21; independent runs used OpenJDK 21.0.12.
  Testcontainers ran `postgres:16-alpine`, reporting PostgreSQL 16.14.
- **Frozen migrations:** independent SHA-256 values match the recorded baselines:

```text
190c0d9cf10ca687bafc0604325aaa47380ae91593b1dbc2d669e9ba27256461  production V8
fd9fd85cdb5c3abae103ec8b82f30e96f67ff332934b9d26666a51ea4839724d  H2 V8
c092433908b88c3d6f7df912e09abd53348874b26af0c9d5163b35eb98ece379  production V9
8f5d6ad52bf4105af15bd5e3ca0e4d8d5d45352dd9d5d70f93e29621c2a1909f  H2 V9
```

## Commands Rerun

All Maven commands used explicit Java 21 and the Colima socket:

```text
mvn -q -Dtest=Phase12ActionStateMachineTest,Phase12ExecutionServiceTest,Phase12UndoCompensationTest test
mvn -q -Dtest=Phase12ReviewWebTest,Phase12MultiAccountTest,Phase12PrivacyTest test
mvn -q -Dtest=Phase12PostgresMigrationTest,Phase9ActionScopeTest,Phase10SelectionMaterializationTest,Phase11PostgresMigrationTest test
mvn -q test
git diff --check
```

All passed. `git diff --check` returned zero. No real mailbox mutation, live
unsubscribe request, browser UAT, deployment, production-code edit, test edit, or
commit was performed.

## Planning-State Assessment

Phase 12's main completion state is accurate: five plans and five summaries exist,
all eleven mapped requirements are verified, the 231-test count is current, and
Phase 13 is next. The stale validation status and unrelated ACTN-02/03 checkbox
discrepancy should be corrected as documentation maintenance.
