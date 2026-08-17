---
phase: 18
slug: test-foundation-postgres-only-e2e-harness-delete-h2-delete-e
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: validated
nyquist_compliant: true
wave_0_complete: true
created: 2026-08-13
---

# Phase 18 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Derived from RESEARCH §6 (Validation architecture) — six gates that prove the
> new Testcontainers-Postgres harness is trustworthy.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 (Jupiter) + Spring Boot Test 3.5.16 + Testcontainers `postgresql`/`junit-jupiter` |
| **Config file** | `pom.xml` (Surefire); shared harness base `PostgresJourneyTest` (Wave 1 installs) |
| **Quick run command** | `mvn -q -Dtest='*JourneyTest' test` |
| **Full suite command** | `mvn -q test` |
| **Estimated runtime** | ~90–180s (single shared static Postgres container boot + suite) |

**Docker/Colima env (local):** `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`, `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`. `@Testcontainers(disabledWithoutDocker = false)` hard-fails without Docker (CI-trust).

---

## Sampling Rate

- **After every task commit:** Run `{quick run command}` (journey tests on the shared harness)
- **After every plan wave:** Run `{full suite command}` — the whole point of this phase is that the full suite is green on real Postgres at every step
- **Before `/gsd:verify-work`:** Full suite must be green on Testcontainers-Postgres
- **Max feedback latency:** ~180 seconds (container boot dominates; a single shared static container amortizes it across the suite)

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 18-01 | 01 | 1 | TEST-04 | — | Shared harness boots Postgres, runs `classpath:db/migration`, `ddl-auto=validate` passes | e2e | `mvn -q -Dtest=SenderTriageJourneyTest test` | ✅ | ✅ green |
| 18-02 | 02 | 2 | TEST-05 | — | Generator seeds ≥10k rows; no verbatim PII in generated values | integration | `mvn -q -Dtest='SyntheticMailbox*' test` | ✅ | ✅ green |
| 18-03 | 03 | 3 | TEST-01, TEST-02, TEST-04 | — | 4 named journeys assert visible behavior over seeded volume; full H2→Postgres test migration completes (last H2 consumer gone); metadata-only, CSRF, exact-ID undo preserved | e2e | `mvn -q test` (green, still pre-collapse) | ✅ | ✅ green |
| 18-04 | 04 | 4 | TEST-01 | — | 5 production H2 branches collapsed to Postgres-only; FULL suite green post-collapse | integration | `grep -R 'contains("H2")\|ARRAY_CONTAINS\|boolean h2' src/main` → 0; `mvn -q test` | ✅ | ✅ green |
| 18-06 | 06 | 4 | TEST-03 | Info-disclosure via export | 6 export routes 404 for authenticated user; `export/` package, ExportProperties, property, DTO fields, SanitizedSnapshotService, scanForExport removed; `exportOrder` preserved | e2e | `mvn -q -Dtest=ExportRemovedJourneyTest test` | ✅ | ✅ green |
| 18-05 | 05 | 5 | TEST-01, TEST-04 | — | `db/test-migration/` (14 files) + H2 pom dependency deleted (pure orphan deletion); suite green | integration | `grep -R 'jdbc:h2:mem' src/test` → 0; `mvn -q test` | ✅ | ✅ green |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

> **Ordering invariant (Blocker 1 fix):** the full H2→Postgres test migration lives in 18-03 (4 cluster tasks) and MUST complete green BEFORE the production H2 branches are collapsed (18-04). The last H2 consumer is gone before the branches are removed, and the orphaned H2 migration set + pom dependency are deleted last (18-05), so `mvn test` stays green at every wave.

---

## Wave 0 Requirements

- [x] `PostgresJourneyTest` shared journey base plus global context customizer route Spring integration tests to one `@ServiceConnection` Postgres container
- [x] `UserAccountFixtures` — shared `user_accounts` fixture factory
- [x] `SyntheticMailboxGenerator` — deterministic seeded ≥10k-row fixture
- [x] `spring-boot-testcontainers` dependency added to `pom.xml` (enables `@ServiceConnection`)

*All later journey/migration tests depend on these Wave 0/Wave 1–2 fixtures existing.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| One-way-door deletion of the 5 production H2 branches | TEST-01 | Irreversible without git revert / re-add; needs a human confirm the last H2 consumer is gone and full suite is green first | `checkpoint:decision` in 18-04; confirm `grep -R 'jdbc:h2:mem' src/test` returns 0 before approving |
| One-way-door deletion of the H2 test-migration set + pom dependency | TEST-01 | Irreversible; deletes 14 files + build dep | `checkpoint:decision` + human-verify in 18-05; confirm `mvn -q test` green |
| One-way-door deletion of the export subsystem | TEST-03 | Irreversible; removes a live user-facing surface (6 routes) | `checkpoint:decision` in 18-06; confirm the 6 routes 404 and `exportOrder` still compiles |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references (`PostgresJourneyTest`, `UserAccountFixtures`, `SyntheticMailboxGenerator`)
- [x] No watch-mode flags
- [x] Feedback latency < 180s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** validated 2026-08-14 — final PostgreSQL suite: 404 tests, 0 failures, 0 errors.
