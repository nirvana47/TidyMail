---
phase: 11-safe-unsubscribe-and-protection
plan: "01"
subsystem: metadata-to-inspection-tracer
tags: [flyway, postgres, h2, rfc2369, rfc8058, dkim, spring-mvc, thymeleaf]
requires:
  - phase: 10-sender-and-unread-decision-surfaces
    provides: canonical account ownership, protected senders, workspace shell, and query patterns
provides:
  - frozen complete paired V8 schema with derived DKIM facts and future attempt/event/nonce tables
  - conservative Gmail metadata trust reduction and bounded RFC 2369/URI/RFC 8058 parsing
  - provider-free account-owned candidate query, browse fragments, and redacted inspection
affects: [11-02, 11-03, 11-04, 11-05, phase-12]
actuals:
  tokens: 58813
  tasks: 4
  commits: 0
tech-stack:
  added: []
  patterns:
    - raw authentication evidence reduced once to allowlisted booleans at ingestion
    - structured URI values retained server-side while presentation receives redacted facts only
    - one authorized request instant drives candidate grouping, volume, browse, and inspection
key-files:
  created:
    - src/main/resources/db/migration/V8__safe_unsubscribe.sql
    - src/main/java/com/tidymail/unsubscribe/Rfc2369Parser.java
    - src/main/java/com/tidymail/unsubscribe/UnsubscribeUriPolicy.java
    - src/main/java/com/tidymail/unsubscribe/Rfc8058Eligibility.java
    - src/main/java/com/tidymail/repository/UnsubscribeCandidateRepository.java
    - src/main/java/com/tidymail/service/UnsubscribeCandidateService.java
    - src/test/java/com/tidymail/Phase11PostgresMigrationTest.java
  modified:
    - src/main/java/com/tidymail/entity/MessageMetadata.java
    - src/main/java/com/tidymail/service/MessageMetadataMapper.java
    - src/main/java/com/tidymail/mail/gmail/GmailMailService.java
    - src/main/java/com/tidymail/controller/WorkspaceController.java
    - src/main/resources/templates/unsubscribe.html
key-decisions:
  - "Duplicate or non-mx.google.com Authentication-Results never contributes trusted DKIM evidence."
  - "Any malformed RFC 2369 remainder makes the complete field review-only; earlier items are not offered as methods."
  - "V8 includes attempt, append-only event, and nonce storage now, but Plan 11-01 maps no execution endpoint."
requirements-completed: [UNSB-01, UNSB-03]
coverage:
  - id: D1
    description: "Paired V8 migrations preserve V7 rows, default trust facts false, and freeze the complete privacy-bounded Phase 11 schema."
    requirement: UNSB-01
    verification:
      - kind: integration
        ref: "Phase11CandidateQueryTest#V8 clean and V7 upgrade cases"
        status: pass
      - kind: integration
        ref: "Phase11PostgresMigrationTest#productionV1ToV8AndV7ToV8UpgradeValidateSchemaHibernateAndCandidateParity"
        status: pass
    human_judgment: false
  - id: D2
    description: "Gmail metadata ingestion derives only conservative same-signature DKIM pass and coverage facts."
    requirement: UNSB-03
    verification:
      - kind: unit
        ref: "Phase11UnsubscribeParserTest#mapperCorrelatesOneTrustedPassingSignatureAndDiscardsRawEvidence"
        status: pass
      - kind: integration
        ref: "Phase4GmailProviderTest#metadataWhitelistGuard"
        status: pass
    human_judgment: false
  - id: D3
    description: "Bounded RFC 2369, URI, and RFC 8058 parsing fails closed and preserves canonical outbound bytes without exposing targets."
    requirement: UNSB-03
    verification:
      - kind: unit
        ref: "Phase11UnsubscribeParserTest"
        status: pass
    human_judgment: false
  - id: D4
    description: "Account-owned candidate browse, result fragment, and inspection render redacted facts with zero provider or outbound calls."
    requirement: UNSB-01
    verification:
      - kind: integration
        ref: "Phase11CandidateQueryTest"
        status: pass
      - kind: automated_ui
        ref: "Phase11UnsubscribeWebTest"
        status: pass
    human_judgment: false
duration: 18 min
completed: 2026-08-10
status: complete
---

# Phase 11 Plan 01: Metadata-To-Inspection Tracer Summary

**Frozen privacy-bounded V8 schema, conservative DKIM/RFC classification, and provider-free account-owned unsubscribe browse and inspection**

## Performance

- **Duration:** 18 min
- **Started:** 2026-08-10T00:53:00Z
- **Completed:** 2026-08-10T01:10:36Z
- **Tasks:** 4
- **Files created/modified:** 27

## Accomplishments

- Added behaviorally equivalent PostgreSQL/H2 V8 migrations with false-safe derived
  trust facts, candidate indexes, and the complete future attempt/event/nonce schema.
- Kept Gmail on an exact 24-header `METADATA` allowlist and reduced trusted
  `mx.google.com` DKIM evidence to three booleans plus one allowlisted reason.
- Added bounded RFC 2369 state parsing, strict HTTPS/mailto policy, exact RFC 8058
  eligibility, canonical body bytes, and versioned canonical candidate locators.
- Delivered real `/unsubscribe`, `/unsubscribe/results`, and account-owned candidate
  inspection GETs with safe DTOs/HTML, protected inspection-only behavior, and no
  provider call, outbound transport, mutation endpoint, or Phase 12 control.

## Task Commits

None. The user explicitly requested no commit.

## Verification

- Focused Gmail/metadata/parser/query/web matrix: **31 tests, 0 failures, 0 errors,
  0 skipped**.
- Phase 11 PostgreSQL 16.14 Testcontainers suite: **1 test, 0 failures, 0 errors,
  0 skipped**; it executes clean V1-V8 and actual V7-to-V8 paths, exact JDBC URL
  assertions, constraints/indexes, candidate parity, and Hibernate validation.
- New Phase 11 suites: **14 tests, 0 failures, 0 errors, 0 skipped**.
- Full Maven suite with all Testcontainers active: **145 tests, 0 failures, 0 errors,
  0 skipped**.
- `git diff --check`: passed.
- Frozen V8 SHA-256:
  production `190c0d9cf10ca687bafc0604325aaa47380ae91593b1dbc2d669e9ba27256461`;
  H2 `fd9fd85cdb5c3abae103ec8b82f30e96f67ff332934b9d26666a51ea4839724d`.

All Maven commands used `JAVA_HOME=$(/usr/libexec/java_home -v 21)`. The host selector
resolves to installed Java 26.0.1. PostgreSQL tests used the active Colima socket.

## Deviations from Plan

### Auto-fixed Issues

1. Thymeleaf evaluated the inspection replacement before its condition; an outer
   `th:block` now prevents null fragment evaluation.
2. Phase 3 still asserted V7 as latest; its baseline now expects V8.
3. Phase 9 still classified Unsubscribe as a disabled placeholder; it now recognizes
   the real inspect-only route while Review and Analytics remain disabled.
4. Safety review found that malformed RFC 2369 tails and duplicate authentication
   result fields could retain partial trust; both now fail closed.
5. Cross-plan freeze review found the nonce table lacked Plan 11-03's exact
   candidate/destination/method binding and used a different redirect reason token;
   both paired migrations and schema tests now use the final downstream contract.

**Total deviations:** 5 auto-fixed correctness/regression issues.
**Impact:** All fixes narrow behavior or update stale baselines; no Phase 12 or outbound
scope was introduced.

## Residual Risks

- The Java 21 selector resolves to Java 26.0.1 on this host, so an actual Java 21
  runtime was not exercised.
- DKIM is intentionally not cryptographically reverified. Eligibility requires one
  provider-owned `mx.google.com` result and same-signature coverage; ambiguous evidence
  remains ineligible.
- Candidate classification is proven on deterministic fixtures but larger production
  candidate distributions still need the planned Phase 11 performance/budget coverage.
- Actual 375px layout, keyboard focus after enhanced fragment swaps, 200% zoom, and
  screen-reader wording remain Phase 13 browser UAT.

## User Setup Required

None.

## Next Phase Readiness

V8 is frozen and ready for later Phase 11 entity mappings. Plan 11-02 can expand the
canonical browse filters and inspection choices without changing either V8 migration;
no execution endpoint exists yet.

---
*Phase: 11-safe-unsubscribe-and-protection*
*Completed: 2026-08-10*
