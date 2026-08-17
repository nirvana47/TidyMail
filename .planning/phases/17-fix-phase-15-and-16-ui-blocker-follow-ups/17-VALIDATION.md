---
phase: 17
slug: fix-phase-15-and-16-ui-blocker-follow-ups
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-08-13
---

# Phase 17 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5, Spring Boot MockMvc, repository/service tests, Thymeleaf source assertions |
| **Config file** | `pom.xml`, `src/test/resources/application-test.properties` |
| **Quick run command** | `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -Dtest='Phase17*Test,Phase16WorkspaceFidelityTest,Phase15WorkspaceWebTest,Phase15UnreadSemanticsTest,Phase15StagingTest,BulkUnsubscribeContractAlignmentTest,Phase12ReviewWebTest' test` |
| **Full suite command** | `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn test` |
| **Estimated runtime** | Quick: ~60-180s; full: Docker/Testcontainers-dependent |

---

## Sampling Rate

- **After every task commit:** Run the narrow test named in that task's `<verify>` block.
- **After every plan wave:** Run the quick command above, or the plan-specific superset if narrower commands cannot cover the touched files.
- **Before `/gsd:verify-work`:** Full non-Docker suite must be green; Docker/Testcontainers full suite must be green or documented as environment-blocked with exact error.
- **Max feedback latency:** Prefer <180s for focused checks; do not defer all verification to final UAT.

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------------|-----------|-------------------|-------------|--------|
| 17-01-* | 01 | 1 | UX-11, UX-13, UX-15 | Bulk Unsubscribe actions remain in-page and do not silently no-op | MockMvc/source/service | Focused BulkUnsubscribe + Phase17 tests | W0 | pending |
| 17-02-* | 02 | 2 | UX-10, UX-11, UX-12, UX-13, UX-14, UX-15 | Cleanup actions stage exact IDs only; counts are never scopes; UX-14 user-created category controls/marking remain reachable | MockMvc/source/JS | Focused cleanup category + staging tests | W0 | pending |
| 17-03-* | 03 | 3 | UX-10, UX-11, UX-12, UX-13, UX-15 | Unread total is server-owned Inbox unread; stale Unread notes are absent; destructive actions stage through Review | Repository/MockMvc/source | Focused unread semantic + staging tests | W0 | pending |
| 17-04-* | 04 | 4 | UX-09, UX-13, UX-15 | Rail destructive data deletion keeps POST, CSRF, confirmation | Source/CSS/MockMvc | Focused workspace fidelity tests | W0 | pending |
| 17-05-* | 05 | 5 | UX-13, UX-15 | Review History preserves durable audit and undo/follow-up semantics | Source/MockMvc | Focused review tests | W0 | pending |
| 17-06-* | 06 | 6 | UX-09, UX-10, UX-11, UX-12, UX-13, UX-14, UX-15 | Authenticated UAT validates the reported live blockers | Manual + automated sweep | Quick command + localhost walkthrough | W0 | pending |

---

## Wave 0 Requirements

- [ ] Add or update a `Phase17*Test` focused test class before broad template/CSS rewrites when no existing focused class cleanly owns the assertion.
- [ ] Reuse existing Phase 12/15/16 test fixtures where possible instead of creating duplicate setup.
- [ ] Add source-level assertions for HTMX script presence and continuation fragments before relying on manual clicks.
- [ ] Add explicit assertions for UX-14 user-created category creation/change reachability and `User generated` marking.
- [ ] Add explicit assertions that stale explanatory notes above the Unread category list are absent after the page-level `Inbox unread` total is introduced.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Authenticated stats modal, primary Unsubscribe, Load more, Expand all, category move-empty, Unread Delete selected, rail utility/collapse, and Review History | UX-15 | These blockers were found in live authenticated localhost with real account data and browser interaction | Walk `http://localhost:8080` while authenticated at desktop, 375x812, and 200% zoom. Capture pass/fail and screenshots or notes in Phase 17 verification artifact. |
| Review History compact cards | UX-13, UX-15 | Source checks cannot prove browser zoom geometry | Under the History tab, verify desktop rows prioritize action, target, outcome, counts, time, and follow-up. At 375px verify rows stack as compact cards with no page-level horizontal scrolling. At 200% zoom verify `Details`, undo, provider options, and compensation controls remain reachable. |
| Actual Gmail Inbox unread total reconciliation | UX-10, UX-12 | The source of truth is provider/account state at the time of UAT | Compare visible `Inbox unread` total with the user's current Gmail Inbox unread count and document any sync delay or provider limitation honestly. |
| User-created category flow and marking | UX-14 | Category creation/change reachability is interaction-heavy and tied to authenticated data shape | Create or use a user-generated category, verify the actual contextual management flow remains reachable, and verify the category is visibly marked `User generated`. |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies.
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify.
- [ ] No watch-mode flags.
- [ ] Full Docker/Testcontainers state recorded honestly.
- [ ] Authenticated UAT remains a required closure gate.

**Approval:** pending
