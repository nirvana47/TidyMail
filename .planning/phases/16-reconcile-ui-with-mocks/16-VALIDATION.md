---
phase: 16
slug: reconcile-ui-with-mocks
# status lifecycle: draft (seeded by plan-phase) -> validated (set by validate-phase)
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-08-12
---

# Phase 16 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 / Spring Boot Test / MockMvc / Maven |
| **Config file** | `pom.xml` |
| **Quick run command** | `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH ./mvnw -Dtest=Phase15WorkspaceWebTest,Phase10SenderUnreadWebTest test` |
| **Full suite command** | `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH ./mvnw test` |
| **Estimated runtime** | quick ~60-120 seconds; full suite project-dependent |

---

## Sampling Rate

- **After every task commit:** Run the quick Phase 16 UI/web test command or a narrower task-specific equivalent named in the PLAN.
- **After every plan wave:** Run the full suite unless the PLAN records a narrower justified wave command plus an explicit full-suite deferral.
- **Before `/gsd:verify-work`:** Full suite must be green, and authenticated page-by-page walkthrough evidence must be captured.
- **Max feedback latency:** one task; no three consecutive UI/template/JS/CSS edits may proceed without automated MockMvc or source assertion coverage.

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| TBD | TBD | TBD | UX-09..UX-15 | — | Exact-ID staging, no export affordances, CSRF, account authorization, and deletion warnings remain intact | MockMvc/source/manual UAT | PLAN-specific command | ❌ W0 | ⬜ pending |

*Status: pending until PLAN.md files assign concrete task IDs and commands.*

---

## Wave 0 Requirements

- [ ] Phase 16 audit artifact path is identified in the plan before remediation starts.
- [ ] Existing `Phase15WorkspaceWebTest` and `Phase10SenderUnreadWebTest` expectations are reviewed and updated for the row-feedback/no-panel contract.
- [ ] Source assertions cover removal of visible rail badges, removal of Unread notes, exact Unread copy, no cleanup status panel, and no user-facing export affordances.
- [ ] Manual authenticated walkthrough checklist covers Bulk Archive/Delete, Unread Only, Bulk Unsubscribe, Review, Analytics, and the shared rail against the `.design` file and Build Notes.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Authenticated page-by-page visual reconciliation against `tidymail-ux-rehaul-draft/tidymail-ux-rehaul-draft.design` and Build Notes | UX-15 | Requires authenticated localhost UI, real route rendering, and visual comparison against design contract | Run the app locally, authenticate, capture page-by-page expected/current/delta/remediation status for shared rail, Bulk Archive/Delete, Unread Only, Bulk Unsubscribe, Review, and Analytics. |
| Row/button-only staging feedback feels discoverable without persistent status panel | UX-11 | Requires interaction judgment after staging with real rendered rows | Stage a non-delete cleanup action, verify affected rows/buttons show staged/disabled state, no persistent panel appears, and Review remains the inspection destination. |
| Cleanup density and hierarchy match mock intent | UX-12, UX-13, UX-14 | Visual density cannot be fully proven by unit tests | Compare category headers, row spacing, category-management affordances, button rounding, and `User generated` tags against mock exports and Build Notes. |

---

## Validation Sign-Off

- [ ] All tasks have automated verify commands or explicit manual-only justification.
- [ ] Sampling continuity: no 3 consecutive tasks without automated verification.
- [ ] Wave 0 covers all missing source assertions before broad remediation.
- [ ] No watch-mode flags.
- [ ] Feedback latency stays within one task.
- [ ] `nyquist_compliant: true` set in frontmatter after validation review.

**Approval:** pending
