---
phase: 19
slug: mailbox-freshness-first-run-onboarding-gmail-history-api-sync
status: automated-green-manual-pending
nyquist_compliant: true
wave_0_complete: true
created: 2026-08-14
updated: 2026-08-14
---

# Phase 19 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5, Spring Boot Test, MockMvc, Testcontainers PostgreSQL 16 |
| **Config file** | `pom.xml`, `src/test/resources/META-INF/spring.factories` |
| **Quick run command** | `DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q -Dtest='<affected journey or contract tests>' test` |
| **Full suite command** | `DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test` |
| **Estimated runtime** | Quick slices under 180 seconds; full suite approximately 6 minutes |

---

## Sampling Rate

- **After every task commit:** Run the narrowest affected PostgreSQL journey/contract test set.
- **After every plan wave:** Run the full Maven suite.
- **Before `/gsd:verify-work`:** Full suite, source guards, and live Gmail UAT must be green.
- **Max feedback latency:** 180 seconds for task-local checks.

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 19-01-01 | 01 | 1 | FRESH-03, FRESH-04, FRESH-08, FRESH-10 | T19-01 | State, revision, provenance, and OAuth columns are account-scoped and migration-valid | integration | `mvn -q -Dtest='MailboxSyncStateTest,*PostgresMigrationTest,SyntheticMailboxPiiGuardTest' test` | ❌ create | ⬜ pending |
| 19-01-02 | 01 | 1 | FRESH-01, FRESH-03, FRESH-04, FRESH-08, FRESH-10 | T19-01, T19-02 | Message/delete/activity/cursor effects commit atomically and higher revisions win | integration | `mvn -q -Dtest='MailboxSyncPersistenceTest,MailboxSyncStateTest' test` | ❌ create | ⬜ pending |
| 19-02-01 | 02 | 2 | FRESH-05 | T19-03 | Removal and terminal refresh failure preserve account and indexed rows | integration | `mvn -q -Dtest='OAuthAuthorizedClientRefreshTest,Phase3PersistenceTest' test` | ❌ create | ⬜ pending |
| 19-02-02 | 02 | 2 | FRESH-05 | T19-03 | Same-account callers refresh once and use the persisted managed token | integration | `mvn -q -Dtest='OAuthAuthorizedClientRefreshTest,Phase4GmailProviderTest' test` | ❌ create | ⬜ pending |
| 19-03-01 | 03 | 2 | FRESH-03, FRESH-08, FRESH-10 | T19-04 | Provider-neutral contracts reject provider-specific cursor/type leakage | compile | `mvn -q -DskipTests compile` | ❌ create | ⬜ pending |
| 19-03-02 | 03 | 2 | FRESH-03, FRESH-06, FRESH-08, FRESH-10 | T19-04 | Gmail sync paths are metadata-only and all 13 scoped opt-out guards pass | contract | `mvn -q -Dtest='GmailSynchronizationContractTest,Phase4GmailProviderTest,Phase4ConfigValidationTest' test` | ❌ create | ⬜ pending |
| 19-04-01 | 04 | 3 | FRESH-01, FRESH-02, FRESH-03, FRESH-04 | T19-02, T19-05 | Durable same-lane exclusion permits freshness beside saturated backfill | concurrency | `mvn -q -Dtest='MailboxFreshnessConcurrencyTest,MailboxSyncPersistenceTest' test` | ❌ create | ⬜ pending |
| 19-04-02 | 04 | 3 | FRESH-01, FRESH-03, FRESH-04, FRESH-06, FRESH-08 | T19-05 | Bootstrap, backfill, empty mailbox, and expired-history rescan remain durable and browser-independent | journey | `mvn -q -Dtest='MailboxFreshnessJourneyTest,MailboxFreshnessConcurrencyTest,SenderTriageJourneyTest,Phase9MetadataIndexTest,OAuthLoginSuccessListenerTest' test` | ❌ create / ✅ extend | ⬜ pending |
| 19-05-01 | 05 | 4 | FRESH-04, FRESH-07 | T19-06 | Activity routes enforce ownership and render only factual safe state | journey | `mvn -q -Dtest='MailboxActivityJourneyTest,SecurityConfigIntegrationTest' test` | ❌ create | ⬜ pending |
| 19-05-02 | 05 | 4 | FRESH-04, FRESH-07 | T19-06 | Polling is bounded/focus-safe and responsive assertions remain consolidated | journey/fidelity | `mvn -q -Dtest='MailboxActivityJourneyTest,Phase13WorkspaceResponsiveTest,Phase16WorkspaceFidelityTest' test` | ❌ create / ✅ extend | ⬜ pending |
| 19-06-01 | 06 | 5 | FRESH-04, FRESH-09 | T19-07 | Shared unique-message queries distinguish Inbox/All-Mail and pending/zero | journey | `mvn -q -Dtest='SenderTriageJourneyTest,UnreadReviewJourneyTest,AnalyticsJourneyTest' test` | ✅ extend | ⬜ pending |
| 19-06-02 | 06 | 5 | FRESH-10 | T19-04, T19-07 | Attachment UNKNOWN remains Checking and only PRESENT enters attachment scope | journey/contract | `mvn -q -Dtest='MailboxFreshnessJourneyTest,UnreadReviewJourneyTest,SenderTriageJourneyTest,GmailSynchronizationContractTest' test` | ❌ create / ✅ extend | ⬜ pending |
| 19-06-03 | 06 | 5 | FRESH-01..10 | T19-01..07 | Eight consolidated surfaces, realistic volume, and scoped API guards replace stale coverage | integration/perf | `mvn -q -Dtest='MailboxSyncStateTest,MailboxSyncPersistenceTest,MailboxFreshnessConcurrencyTest,OAuthAuthorizedClientRefreshTest,GmailSynchronizationContractTest,MailboxFreshnessJourneyTest,MailboxActivityJourneyTest,MailboxSyncPerformanceTest,SenderTriageJourneyTest,UnreadReviewJourneyTest,AnalyticsJourneyTest' test` | ❌ create / ✅ extend | ⬜ pending |
| 19-07-01 | 07 | 6 | FRESH-01..10 | T19-01..07 | Automated preflight, privacy guards, scoped API guards, and evidence template are clean | release preflight | `mvn -q test && mvn -q -Dtest='GmailSynchronizationContractTest' test && git diff --check` | ❌ create | ⬜ pending |
| 19-07-02 | 07 | 6 | FRESH-01..10 | T19-01..07 | Controlled Gmail UAT verifies provider behavior without recording secrets or PII | human checkpoint | `mvn -q -Dtest='MailboxFreshnessJourneyTest,MailboxActivityJourneyTest,OAuthAuthorizedClientRefreshTest,GmailSynchronizationContractTest' test` | ❌ create | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

The validation architecture is approved and Nyquist-compliant. `wave_0_complete` remains `false` because these eight consolidated test surfaces are implementation work created during execution, not prerequisites already present at planning time.

- [ ] `MailboxSyncStateTest` — production Flyway state schema and cascade evidence.
- [ ] `MailboxSyncPersistenceTest` — revision-aware atomic upsert/delete/cursor commit.
- [ ] `MailboxFreshnessConcurrencyTest` — independent lane leases and stale-write protection.
- [ ] `OAuthAuthorizedClientRefreshTest` — managed refresh persistence and nondestructive failure.
- [ ] `GmailSynchronizationContractTest` — profile/history/backfill/attachment-search request semantics.
- [ ] `MailboxFreshnessJourneyTest` — first batch, concurrent fresh mail, expired-history recovery.
- [ ] `MailboxActivityJourneyTest` — activity route, truthful progress, polling lifecycle, reauth/error states.
- [ ] `MailboxSyncPerformanceTest` — 10,000-row default and optional 100,000-row profile.

The existing Phase 18 `PostgresJourneyTest` and synthetic mailbox generator remain the shared infrastructure. New tests should consolidate or replace overlapping Phase 9 cases rather than duplicate them.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Real Gmail message appears during active backfill | FRESH-01, FRESH-03 | Gmail history retention and provider latency cannot be reproduced fully offline | Connect a controlled account, begin backfill, send one uniquely identifiable message, and record receipt-to-render latency within one configured cycle. |
| Real OAuth access token refresh persists once | FRESH-05 | Requires Google's token endpoint and a real refresh token | Let a controlled access token expire, trigger sync, verify one refresh and persisted expiry without deleting indexed data. |
| Real unread, size, and attachment membership reach UI | FRESH-10 | Gmail METADATA and search behavior require provider confirmation | Send controlled unread messages with and without attachments; verify database facts and visible Inbox/All-Mail/attachment labels without recording PII. |
| Responsive and focus behavior of live polling surface | FRESH-07 | Requires visual/browser evidence across actual viewports | Verify desktop and mobile layouts, focus retention, reduced motion, polling stop/backoff, and no overlap at 1440px and 390px. |

---

## Validation Sign-Off

- [x] All tasks have automated verification or explicit Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 defines all missing test references as execution deliverables
- [x] No watch-mode flags
- [x] Feedback latency target < 180 seconds
- [x] Full suite remains journey-readable; redundant Phase 9 tests are removed as equivalent coverage lands
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** approved 2026-08-14; Wave 0 implementation is complete and its
final automated evidence is recorded below.

---

## Plan 07 Final Verification - 2026-08-14

All commands used:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="${JAVA_HOME}/bin:${PATH}"
export DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

Environment preflight passed with OpenJDK `21.0.12`, Colima running on the
explicit socket, Docker server `29.2.1`, and the Testcontainers in-container
socket override `/var/run/docker.sock`.

### Automated Results

| Surface | Exact command | Result |
|---------|---------------|--------|
| Eight Phase 19 validation surfaces plus sender, unread, and analytics journeys | `mvn -q -Dtest='MailboxSyncStateTest,MailboxSyncPersistenceTest,MailboxFreshnessConcurrencyTest,OAuthAuthorizedClientRefreshTest,GmailSynchronizationContractTest,MailboxFreshnessJourneyTest,MailboxActivityJourneyTest,MailboxSyncPerformanceTest,SenderTriageJourneyTest,UnreadReviewJourneyTest,AnalyticsJourneyTest' test` | PASS - 53 tests in 11 reports; 0 failures, 0 errors, 0 skipped |
| Complete focused validation-map union | `mvn -q -Dtest='MailboxSyncStateTest,*PostgresMigrationTest,SyntheticMailboxPiiGuardTest,MailboxSyncPersistenceTest,OAuthAuthorizedClientRefreshTest,Phase3PersistenceTest,Phase4GmailProviderTest,Phase4ConfigValidationTest,MailboxFreshnessConcurrencyTest,MailboxFreshnessJourneyTest,SenderTriageJourneyTest,Phase9MetadataIndexTest,OAuthLoginSuccessListenerTest,MailboxActivityJourneyTest,SecurityConfigIntegrationTest,Phase13WorkspaceResponsiveTest,Phase16WorkspaceFidelityTest,UnreadReviewJourneyTest,AnalyticsJourneyTest,GmailSynchronizationContractTest,MailboxSyncPerformanceTest' test` | PASS - 115 tests in 26 reports; 0 failures, 0 errors, 0 skipped |
| Full PostgreSQL suite | `mvn -q test` | PASS - 447 tests in 92 reports; 0 failures, 0 errors, 0 skipped |
| JavaScript syntax | `node --check src/main/resources/static/js/htmx-lite.js` | PASS |
| Production privacy, destructive-action, browser-ownership, and stale-ceiling guard | `! rg -n 'setFormat\("(FULL|RAW)"\)|attachments\(\)\.get|getSnippet\(\)|userAccountRepository\.delete|keep (this )?(window|page|tab) open|SYNC_MAX_MESSAGES_PER_ACCOUNT:50000' src/main .env.example` | PASS - zero matches |
| Patch whitespace | `git diff --check` | PASS |

`GmailSynchronizationContractTest` passed all 13
`phase19SyncPathDoesNotUseOptedOutCapability` parameterized cases: message
mutation, FULL, RAW, body/snippet/MIME content, attachment content, watch/stop,
settings, filters, label management, Contacts/People, drafts, send, and
threads. The guard reads only the new `MailboxSyncProvider` contract and the
six Phase 19 Gmail sync method bodies, so pre-existing reviewed cleanup
mutations remain explicitly excluded.

### Default Performance Profile

`MailboxSyncPerformanceTest` ran with no `TIDYMAIL_PERF_ROWS` override:

- Rows: 10,000
- Seed: 765 ms
- First 200 rows: 1 ms
- Sender route: 284 ms
- Unread route: 149 ms
- Activity route: 43 ms
- Bounded upsert: 500 rows in 289 ms
- Retained bounded-work heap: 0 bytes in this run
- Unread plan: `idx_message_metadata_account_unread_received`, 1.232 ms
- Generation sweep plan: `idx_message_metadata_account_last_synced`, 2.752 ms

### Cleanup Decision

No additional dead code was removed. Plans 04 and 06 already removed the
obsolete Phase 9 orchestration and stale Phase 4/9 metadata assertions after
green replacement evidence. Plan 07 found no further deletion explicitly
allowed by the plan and backed by new replacement evidence.

### Manual Verification Still Required

The authenticated `/activity` desktop/mobile visual check remains manual
because this executor has no controlled OAuth browser session. Verify the 28
UI backstops at 320, 375, 720, 980, and desktop widths plus 200% zoom,
including tooltip containment, 44px touch targets, keyboard focus across live
swaps, unchanged scroll position, polite live-region behavior, and reduced
motion. This manual limitation does not weaken the green automated responsive,
fidelity, MockMvc, JavaScript syntax, or polling-contract evidence above.
