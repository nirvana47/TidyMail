# TidyMail Roadmap: v1.1 Complete Cleanup Workspace

**Goal:** Convert the authoritative `tidymail-ux-rehaul-draft/tidymail-ux-rehaul-draft.design` contract into a working metadata-only product with exact actions, safe unsubscribe behavior, durable analytics, and no decorative controls. Its `Build Notes` page is the implementation contract; older HTML mocks and separate Design Canvas projects are excluded.

**Phases:** 9
**Requirements:** 76
**Numbering:** Continues from v1.0 at Phase 9

## Phase 9: Metadata Index And Action Foundations

**Goal:** Make every workspace query and action operate from durable, indexed, exact metadata instead of synchronous provider rescans.

**Status:** Complete — independently verified 2026-08-09

**Requirements:** DATA-01, DATA-02, DATA-03, DATA-04, DATA-05, DATA-06, DATA-07, WORK-01, WORK-03, WORK-04, ACTN-02, ACTN-03, QUAL-04, QUAL-05

**Plans:** 3/3 complete

- [x] 09-01 — Metadata Index and Sync Foundations
- [x] 09-02 — Action Scope Foundations
- [x] 09-03 — Workspace Shell

**Success criteria:**

1. A repeated 500-message Gmail sync produces one metadata row per provider message, advances visible progress, and never stores body or attachment content.
2. Senders and message queries execute from PostgreSQL without a provider call in the page request and meet the localhost response budgets.
3. An action preview persists an immutable message-ID scope and hash that remains unchanged through later approval, execution, and undo.
4. The shared workspace shell renders all five routes with account scope, queue count, and consistent partial/error states.

## Phase 10: Sender And Unread Decision Surfaces

**Goal:** Deliver fully queryable sender-level and message-level triage with deterministic explanations and exact selection semantics.

**Status:** Complete — independently verified 2026-08-09

**Requirements:** WORK-02, SEND-01, SEND-02, SEND-03, SEND-04, SEND-05, SEND-07, UNRD-01, UNRD-02, UNRD-03, UNRD-04, UNRD-05, UNRD-06, QUAL-03

**Plans:** 3/3 complete

- [x] 10-01 — Sender And Unread Query Foundation
- [x] 10-02 — Exact Selection Preview
- [x] 10-03 — Sender And Unread Web Surfaces

**Success criteria:**

1. Every sender mock filter, sort, search, queue, account, risk, and protected-state control reproduces its state from the URL.
2. Sender rows expose volume, unread pressure, activity, recommendation, risk, and plain-English deterministic signals.
3. Unread rows expose metadata-only subject context, age, thread state, category, recommendation, reasons, and provider deep links.
4. Visible-page and all-matching selections produce exact server-side impact previews, while protected senders remain excluded without an audited override.

## Phase 11: Safe Unsubscribe And Protection

**Goal:** Implement the complete unsubscribe queue with secure automated methods, explicit manual alternatives, and honest outcome states.

**Status:** Complete — independently verified 2026-08-09

**Requirements:** UNSB-01, UNSB-02, UNSB-03, UNSB-04, UNSB-05, UNSB-06, UNSB-08, QUAL-01

**Plans:** 5/5 complete

- [x] 11-01 — Metadata-To-Candidate Tracer
- [x] 11-02 — Browse And Inspection UI
- [x] 11-03 — Consent, Execution, And Honest Outcomes
- [x] 11-04 — Hardened Unsubscribe Transport Integration
- [x] 11-05 — Verification And Security Closure

**Success criteria:**

1. Candidates can be ranked and filtered by method, volume, confidence, age, review need, account, and protection state.
2. RFC 8058 requests use guarded HTTPS POST only when the required headers are present and show the destination host before approval.
3. Legacy HTTPS GET, `mailto:`, and account-page alternatives are explicit user choices rather than silent fallbacks.
4. SSRF tests reject loopback, private, link-local, non-HTTPS, and redirect-escaped destinations, while outcomes remain visible as pending, accepted, confirmed, failed, manual-required, or verification-pending.

## Phase 12: Review, Dual Execution Modes, History, And Undo

**Goal:** Complete one action engine that supports both staged review and opt-in immediate execution without weakening exactness or reversibility.

**Status:** Complete — verified 2026-08-09

**Requirements:** SEND-06, UNRD-07, UNSB-07, ACTN-01, ACTN-04, ACTN-05, ACTN-06, ACTN-07, ACTN-08, ACTN-09, ACTN-10

**Plans:** 5/5 complete

- [x] 12-01 — Shared Execution Ledger And Walking Tracer
- [x] 12-02 — Complete Action Surface Adoption
- [x] 12-03 — Review Queue And Conflict Control
- [x] 12-04 — History, Recovery, Undo, And Compensation
- [x] 12-05 — Production Verification And Regression Closure

**Success criteria:**

1. Sender, unread, and unsubscribe decisions can enter staged review or immediate execution through the same immutable action scope.
2. Review groups all action types, supports removing and approving selected items, and blocks incompatible overlapping actions before mutation.
3. Execution records requested, accepted, succeeded, failed, and verification-pending outcomes instead of marking every queued action successful.
4. Archive, trash, and mark-read undo uses only recorded applied IDs; irreversible decisions explain available compensating actions.
5. Durable history can be filtered by date, account, action type, status, and target.

## Phase 13: Analytics, Export, And Integrated Product Verification

**Goal:** Turn real sync and action facts into trustworthy analytics, complete every remaining mock control, and verify the full product locally.

**Requirements:** WORK-05, ANLY-01, ANLY-02, ANLY-03, ANLY-04, ANLY-05, ANLY-06, ANLY-07, ANLY-08, EXPT-01, EXPT-02, EXPT-03, QUAL-02, QUAL-06, UX-01, UX-02, UX-03, UX-04, UX-05, UX-06, UX-07, UX-08

**Plans:** 6/6 plans executed; latest design contract aligned; release verification constrained

- [x] 13-01-PLAN.md
- [x] 13-02-PLAN.md
- [x] 13-03-PLAN.md
- [x] 13-04-PLAN.md
- [x] 13-05-PLAN.md
- [x] 13-06-PLAN.md

**Success criteria:**

1. Analytics supports date range, day/week/month grouping, account/action filters, prior-period comparison, trend buckets, top senders, outcomes, coverage, and efficiency metrics with explicit denominators.
2. Every chart and metric drills into the same filtered source rows, and incomplete history is labeled rather than fabricated.
3. Historical Phase 13 export and snapshot behavior remains superseded for user-facing product behavior; authenticated workspace pages must not expose export, CSV, snapshot, or download affordances.
4. All five pages work at 375px with 44px touch targets and no page-level horizontal scrolling.
5. Real localhost Gmail UAT completes sync, both execution policies, unsubscribe alternatives, result history, analytics, and undo before any public deployment.

### Latest Design Contract Alignment

The updated `.design` draft and Build Notes were reconciled after the original Phase 13 execution. Shared rail/session behavior, quiet cleanup category workflows, individual-sender unsubscribe, LIFO Review, and one-account Analytics are implemented and verified by 344 passing Java 21 tests with PostgreSQL 16 and H2 parity. See `13-DRAFT-CONTRACT-ALIGNMENT.md`.

## Coverage

| Phase | Requirements | Count |
|-------|--------------|-------|
| 9 | DATA-01..07, WORK-01/03/04, ACTN-02/03, QUAL-04/05 | 14 |
| 10 | WORK-02, SEND-01..05/07, UNRD-01..06, QUAL-03 | 14 |
| 11 | UNSB-01..06/08, QUAL-01 | 8 |
| 12 | SEND-06, UNRD-07, UNSB-07, ACTN-01/04..10 | 11 |
| 13 | WORK-05, ANLY-01..08, EXPT-01..03, QUAL-02/06, UX-01..08 | 22 |
| 16 | UX-09..15 | 7 |
| **Total** | **All v1.1 requirements** | **76** |

**Coverage validation:** 76 mapped, 0 unmapped, 0 multiply mapped.

### Phase 14: UI Fidelity And Route Recovery

**Goal:** Restore all five authenticated workspace routes, converge the production shell and page hierarchy on `tidymail-ux-rehaul-draft/tidymail-ux-rehaul-draft.design`, and repair represented interactions without weakening exact-ID action safety.
**Requirements**: QUAL-02, QUAL-03, QUAL-05, QUAL-06, ACCT-01, ACCT-02, ACTN-01, ACTN-02, ACTN-03, ACTN-04, EXPT-01, EXPT-02
**Depends on:** Phase 13
**Plans:** 3 plans

Plans:

- [x] 14-01: Recover Unread and Unsubscribe against real PostgreSQL data
- [x] 14-02: Replace the legacy top navigation with the approved shared left rail
- [x] 14-03: Close page interactions, exports, analytics visuals, and localhost verification

### Phase 15: Implement the pending workspace cleanup navigation and classification todo using the authoritative tidymail-ux-rehaul-draft.design contract

**Goal:** Deliver the authoritative five-page workspace contract with rail-only account scope, server-bounded cleanup categories, durable account-local classification corrections, Inbox-only unread semantics, direct per-sender exact-ID Review staging, measured page-bounded unsubscribe queries, no user-facing export affordances, and authenticated responsive localhost acceptance.
**Status:** Complete — accepted 2026-08-13; functionality and data-quality follow-ups deferred to a new phase
**Requirements**: ACTN-02, ACTN-03, ACTN-07, EXPT-01, EXPT-02, EXPT-03, QUAL-01, QUAL-02, QUAL-03, QUAL-04, UX-01, UX-03, UX-04, UX-05, UX-07
**Depends on:** Phase 14
**Plans:** 11/11 complete

**ACTN-02 Phase 15 supersession:** For Bulk Archive/Delete and Unread Only, Phase 15 replaces the separate pre-queue Selection Preview page with server-side exact materialization on the direct row/selected-sender click and an inline exact count/staged result without navigation. Review remains mandatory before execution and preserves the immutable exact scope hash/count. Preview behavior remains in force for other surfaces where applicable.

Plans:

- [x] 15-01-PLAN.md
- [x] 15-02-PLAN.md
- [x] 15-03-PLAN.md
- [x] 15-04-PLAN.md
- [x] 15-05-PLAN.md
- [x] 15-06-PLAN.md
- [x] 15-07-PLAN.md
- [x] 15-08-PLAN.md
- [x] 15-09-PLAN.md
- [x] 15-10-PLAN.md
- [x] 15-11-PLAN.md

**Wave 1**

- [x] 15-01: Deliver the server-bounded Bulk Archive/Delete query tracer with exact summaries, 5/+5 paging, and the 1,000-row Load All/422 contract

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 15-02: Build the strict direct per-sender exact staging boundary and transactional Review reconciliation
- [x] 15-03: Persist account-owned classification corrections and repair deterministic rules with PostgreSQL/H2 parity
- [x] 15-07: Profile and replace unbounded Bulk Unsubscribe navigation with page-bounded repository work

**Wave 3** *(blocked on the Wave 2 staging and classification-correction foundations)*

- [x] 15-09: Complete the authoritative Bulk Archive/Delete UI, owned sender correction routing, and inline staged reconciliation
- [x] 15-04: Centralize effective classification and Inbox-plus-unread semantics across queries, counts, and exact materialization

**Wave 4** *(blocked on classification and Inbox-unread semantics)*

- [x] 15-05: Complete overlapping bounded Unread Only categories, reuse the sender correction route, and document freshness
- [x] 15-06: Integrate the authoritative rail, route-preserving account scope, authorized badges, and Analytics no-export contract

**Wave 5** *(blocked on the bounded backends and shared workspace foundations)*

- [x] 15-10: Complete Unread Only direct staging and responsive interaction
- [x] 15-11: Complete the authoritative individual-sender Unsubscribe UI and Review/Unsubscribe no-export contract

**Wave 6** *(blocked on all implementation plans; automated Java 21/fixture/server/readiness preparation precedes the final human checkpoint)*

- [x] 15-08: Run structural, focused, full-suite, PostgreSQL/H2, deterministic localhost preparation, and the end-of-phase authenticated five-page browser checkpoint

**Success criteria:**

- Category headers load without row bodies; category requests return 5/+5 rows, explicit Load all never returns more than 1,000 cumulative sender rows, and totals above 1,000 receive HTTP 422 guidance to continue bounded paging.
- Bulk sender and unread direct clicks materialize exact IDs server-side, return an inline exact count/staged result without navigation, and transactionally create or reuse one account-local staged Review action per unique sender. Review preserves the immutable exact scope hash/count before execution, with verified conflicts, duplicates, deterministic LIFO ordering, idempotency, disabled-row reconciliation, and the separate 1,000 action-ID cap.
- Unread Only consistently requires both Inbox membership and unread state, preserves overlapping categories and account-local corrections, and visibly explains indexed-snapshot freshness.
- Bulk Unsubscribe performs page-bounded repository work within the indexed-results budget without weakening destination safety, consent, protected-sender, or outcome semantics.
- All five workspace pages expose the authoritative rail and no user-facing export controls, and pass authenticated desktop, 375x812, 200% zoom, keyboard, 44px target, and overflow checks.

**Cross-cutting constraints:**

- Resolve authenticated ownership and selected account before every query or mutation; never trust browser ownership, counts, totals, provider IDs, or execution mode.
- Preserve exact immutable action scopes, protected-sender auditing, CSRF, Delete recovery acknowledgement, and zero provider mutation before Review approval.
- Keep PostgreSQL 16 and H2 behavior aligned, retain production-shaped query-plan evidence, and do not deploy publicly before localhost acceptance.

### Phase 16: Reconcile UI with mocks

**Goal:** Reconcile the running authenticated workspace with `tidymail-ux-rehaul-draft/tidymail-ux-rehaul-draft.design` and its Build Notes by removing cleanup-page status clutter, restoring mock-aligned category and sender-row hierarchy, replacing correction clutter with the approved category-management affordances, and preserving exact-ID Review staging.
**Status:** Complete — accepted 2026-08-13
**Requirements**: UX-09, UX-10, UX-11, UX-12, UX-13, UX-14, UX-15
**Depends on:** `.planning/todos/completed/2026-08-11-refine-workspace-cleanup-navigation-and-classification.md`, the authoritative blocker and gap contract for plans 16-04 through 16-08
**Plans:** 8/8 complete

- [x] 16-01-PLAN.md
- [x] 16-02-PLAN.md
- [x] 16-03-PLAN.md
- [x] 16-04-PLAN.md — Shared shell, page-header, menu, and button remediation
- [x] 16-05-PLAN.md — Contextual cleanup actions and Bulk Unsubscribe remediation
- [x] 16-06-PLAN.md — Compact Analytics hierarchy remediation
- [x] 16-07-PLAN.md — Compact Review Queue/History remediation
- [x] 16-08-PLAN.md — Focused verification and authenticated walkthrough

**Source UAT:** `.planning/todos/completed/2026-08-11-refine-workspace-cleanup-navigation-and-classification.md` is authoritative for plans 16-04 through 16-08; `.planning/todos/completed/2026-08-11-remove-cleanup-page-status-clutter.md` remains historical source UAT for the original plans 16-01 through 16-03.
**Precedence:** The authoritative blocker todo supersedes conflicting older visual or mock direction while preserving all non-conflicting safety and behavior constraints.

**Success criteria:**

- The left rail shows exact text labels only for Unread Only and Review, without numeric badge clutter, while Review remains the inspection destination for staged actions.
- Unread Only removes the two stale explanatory notes above the category list and replaces the remaining instruction with approved, user-friendly copy.
- Bulk Archive/Delete, Unread Only, and audited cleanup pages remove the generic `Ready to stage for Review` status panel without weakening exact message-ID materialization, authorization, immutable scope hashing, or Review approval.
- Cleanup controls, category headers, category rows, and sender rows match the mock density, hierarchy, spacing, button rounding, and compact category structure instead of broad pill/card wrappers.
- Category headers show the mock-required left-side name/description and right-side sender count plus size or unread total.
- Sender correction uses the mock-aligned category-change/create-category flow, and user-created categories receive a visible `User generated` tag.
- A second authenticated page-by-page walkthrough captures app-versus-mock deltas before remediation and verifies closure against the `.design` Build Notes.

**Cross-cutting constraints:**

- Use `tidymail-ux-rehaul-draft/tidymail-ux-rehaul-draft.design` and its Build Notes only for visual decisions not superseded by the authoritative blocker todo.
- Do not reintroduce export, CSV, snapshot, or download affordances.
- Preserve exact-ID-to-Review staging, protected-sender handling, CSRF, destructive-action warnings, and account authorization.

### Phase 17: Fix Phase 15 and 16 UI blocker follow-ups

**Goal:** Close the remaining authenticated Phase 15/16 UI blocker feedback from `.planning/todos/completed/2026-08-11-refine-workspace-cleanup-navigation-and-classification.md`, prioritizing the two latest 2026-08-13 continuation sections: Bulk Unsubscribe stats modal and action wiring, Bulk Archive/Delete and Unread Only cleanup controls, Load more continuation behavior, empty-category metrics, Expand all coverage, Inbox-only unread totals and attachment grouping, rail utility styling, Review History density, and destructive selected-action staging.
**Status:** Complete — user accepted the current UI on 2026-08-13
**Requirements**: UX-09, UX-10, UX-11, UX-12, UX-13, UX-14, UX-15
**Depends on:** Phase 16 executed remediation and `.planning/todos/completed/2026-08-11-refine-workspace-cleanup-navigation-and-classification.md`
**Plans:** 6/6 complete

Plans:

- [x] 17-01-PLAN.md — Bulk Unsubscribe interaction and bounded stats tracer
- [x] 17-02-PLAN.md — Cleanup category controls, continuation fragments, empty metrics, and Expand all
- [x] 17-03-PLAN.md — Unread Only Inbox total, attachment grouping audit, and destructive selected-action staging
- [x] 17-04-PLAN.md — Rail brand, utility menu, and collapse affordance polish
- [x] 17-05-PLAN.md — Compact Review History with preserved audit details
- [x] 17-06-PLAN.md — Focused verification, Docker/Testcontainers status, and authenticated UAT closure

**Wave 1**

- [x] 17-01: Bulk Unsubscribe interaction and bounded stats tracer

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 17-02: Cleanup category controls, continuation fragments, empty metrics, and Expand all

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 17-03: Unread Only Inbox total, attachment grouping audit, and destructive selected-action staging

**Wave 4** *(blocked on Wave 3 completion)*

- [x] 17-04: Rail brand, utility menu, and collapse affordance polish

**Wave 5** *(blocked on Wave 4 completion)*

- [x] 17-05: Compact Review History with preserved audit details

**Wave 6** *(blocked on all implementation plans)*

- [x] 17-06: Focused verification, Docker/Testcontainers status, and authenticated UAT closure

**Cross-cutting constraints:**

- Preserve exact immutable message-ID staging, CSRF, account authorization, protected-sender handling, delete acknowledgement, Review-before-mutation, honest unsubscribe outcomes, and 1,000-ID guards.
- Do not reintroduce authenticated workspace export, CSV, snapshot, download, share, public tunnel, direct cleanup provider execution, AI, or a new frontend framework.
- Counts, category totals, estimated sizes, visible checkbox counts, and provider unread totals are presentation facts, never execution scopes.
- Keep metadata-only privacy: no message-body, attachment-content, token, credential, raw private header, or sensitive provider-ID exposure in UI or verification artifacts.
- Treat focused test failures, Docker/Testcontainers failures, and authenticated UAT blockers as real blockers, not passable polish.

---
*Last updated: 2026-08-13 — Phases 15, 16, and 17 accepted and closed; functionality and data-quality work deferred to a new phase*

---

# TidyMail Roadmap: v1.2 Trustworthy Data & Maintainable Core

**Goal:** Make the tool reflect the user's actual current inbox, load fast for a brand-new user, classify well enough to be trusted, act on Gmail with honest progress feedback, and rest on a single-dialect, readable-test foundation the maintainer could own alone. Turns away from UI polish toward data trustworthiness, first-run experience, and code health.

**Phases:** 5 (18–22)
**Requirements:** 33 (TEST-01..05, FRESH-01..10, CLASS-01..06, ARCH-01..07, SCOPE-01, RULE-01..02, PERF-01..02)
**Numbering:** Continues from v1.1 at Phase 18
**Milestone note:** v1.1 (Phases 9–17) is complete and closed. UI accepted at ~90%.

**Sequencing rationale:** The phases stack — the data/health phases depend on the one before. The test foundation is a prerequisite, not cleanup: freshness and classification changes are unsafe without trustworthy tests, and tests are untrustworthy while H2 silently diverges from production Postgres. Phase 18 also removes 4 of the 6 Critical review findings (C-1/2/3 dual-dialect, C-5 export) at the root, and adds the large PII-stripped synthetic dataset (TEST-05) so freshness/performance work is proven at realistic volume, not on a 100-row toy inbox. Freshness (Phase 19) now also owns first-run onboarding — cold-start fast render, background backfill with a visible sync page, honest Inbox-vs-All-Mail unread, and audited metadata extraction — because they share the same sync engine. Classification (Phase 20) adds correction-persistence across progressive ingestion. The dedicated architectural rewrite (Phase 21) is held behavior-constant by the Phase 18 journey tests. Phase 22 is last and net-new: it depends on the expanded Gmail scopes (which require a Google Cloud Console change and re-consent) and on the Phase 21 execution refactor, delivering Gmail-side rules and honest bulk-action progress feedback.

```
18 Test Foundation ──▶ 19 Freshness &     ──▶ 20 Classification ──▶ 21 Rewrite    ──▶ 22 Gmail Rules &
   (safety net;          First-Run             (benchmark +           (god-classes,      Bulk Feedback
    kills C1/2/3/5;       Onboarding            embedded ML;           C-6, W/I sweep,    (scopes+consent,
    TEST-05 big           (History API;         CLASS-06 correction    jOOQ decision)     filters/labels,
    synthetic set)        cold-start,           persistence)                             progress+ETA,
                          sync page,                                                      bg execution)
                          unread truth,
                          W-14 OAuth)
```

## Phase 18: Test Foundation — Postgres-only E2E harness, delete H2, delete export

**Goal:** Establish a trustworthy safety net before touching sync or classification: run all integration tests on Testcontainers-Postgres against the production migrations, delete the divergent H2 set and the live export subsystem, replace the worst phase-named/mock-heavy tests with readable user-journey E2E tests, and build a large PII-stripped synthetic dataset so later phases are proven at realistic volume.

**Status:** Complete — independently verified 2026-08-14

**Plans:** 6/6 complete

**Requirements:** TEST-01, TEST-02, TEST-03, TEST-04, TEST-05

**Review coverage:** Root-fixes C-1, C-2, C-3, and C-5; removes the C-4 path.

**Depends on:** v1.1 closure (Phase 17)

**Success criteria:**

1. The full integration suite runs against real PostgreSQL via Testcontainers against `src/main/resources/db/migration`; `src/test/resources/db/test-migration` and every `h2` query branch are deleted, and the suite is green.
2. No export endpoint (`/senders.csv`, `/unread.csv`, `/unsubscribe.csv`, `/review/history.csv`, `/analytics.csv`, `/analytics/snapshot.json`) is reachable by an authenticated user; `ExportController`, `ExportService`, the `export/` package, `ExportProperties`, the `tidymail.export.max-rows` property, and the dead `exportUrl` field are removed.
3. At least 3–5 named user-journey E2E tests (real HTTP + real DB, plain-English story names) replace the equivalent phase-named/mock-heavy tests and read as behavior, not implementation.
4. A shared test harness/base removes the copy-pasted datasource/Flyway/`user_accounts` fixtures; no green test can pass on H2 while production Postgres SQL differs.
5. A large (≥10,000 messages, ideally larger), PII-stripped synthetic dataset — modeled on the real ~119,811-row mailbox shape but with anonymized addresses, names, subjects, and IDs — seeds Testcontainers-Postgres so E2E and performance journeys run against realistic volume; a generator/fixture is committed and reproducible.

**Cross-cutting constraints:** Preserve metadata-only privacy, exact-ID action safety, CSRF, and protected-sender handling in all rewritten tests. Do not reintroduce export anywhere. Journey tests must assert visible text/behavior, never raw HTML template strings. The synthetic dataset must contain no real PII — addresses, names, subjects, and provider IDs are anonymized/derived, never copied verbatim.

## Phase 19: Mailbox Freshness & First-Run Onboarding — Gmail History API sync

**Goal:** Make freshly arrived mail appear in the tool within one sync cycle by moving incremental sync to the Gmail History API and decoupling new-mail ingestion from historical backfill; give a brand-new user a fast, non-blank first run with a visible background-sync page; make unread counts honest (Inbox vs All Mail); prove metadata extraction (unread/attachments/size) end-to-end; and manage/persist OAuth token refresh.

**Status:** Planned

**Requirements:** FRESH-01, FRESH-02, FRESH-03, FRESH-04, FRESH-05 (fixes W-14), FRESH-06, FRESH-07, FRESH-08, FRESH-09, FRESH-10

**Depends on:** Phase 18 (protected by the new harness and large synthetic dataset)

**Success criteria:**

1. A live-mailbox UAT shows a newly arrived Gmail message reflected in the tool within one sync cycle, without waiting for backfill.
2. Historical backfill continues in the background without starving new-mail ingestion; the two no longer share one blocking pagination cursor.
3. Incremental sync advances from Gmail `historyId`, with a safe bounded-rescan fallback when history is expired/unavailable, and remains idempotent (one row per provider message).
4. Freshness state is honest — last successful sync point, current lag, and full-rescan-in-progress — with no fabricated "up to date" claim.
5. Gmail access-token refresh runs through a managed `OAuth2AuthorizedClientManager`, the refreshed token is persisted, and null timestamps no longer coerce a token to already-expired.
6. A brand-new account sees populated senders/categories within seconds of first sign-in — a first batch of ~100–250 messages is ingested, classified, and rendered before full history arrives — never a blank screen; the accidental `MAX_PAGES=5 × PAGE_SIZE=100` cold-start behavior is replaced by an explicit fast-first-batch-then-background-backfill design.
7. A dedicated sync/activity page (separate from Analytics) shows live background-ingestion progress — messages indexed so far, reliable total/known remaining, current phase, and ETA only when supportable — while durable server-side synchronization continues after navigation or browser closure; active and queued states explicitly say the page can be closed and never instruct the user to keep a page, tab, or window open.
8. The entire mailbox is progressively indexed with no GB/storage cap (DECISIONS.md §11); an optional maintainer-only message-count ceiling (default unlimited) exists purely as a safety bound and is never surfaced as a Gmail-storage figure.
9. One honest, consistently-labeled unread definition ships — Inbox-unread vs All-Mail-unread are computed and labeled distinctly from a single source of truth, so a message counted under multiple Gmail labels no longer produces conflicting totals (e.g. 8,200 vs 7,600).
10. A journey test proves unread state, attachment presence, and size-estimate are extracted from `format=METADATA`, ingested, stored, and displayed correctly against realistic data — auditing `GmailMailService.toMailMessage` extraction vs `MessageMetadata` storage vs UI display, not assumed.

**Cross-cutting constraints:** Metadata-only (`format=METADATA`, whitelisted headers); never store bodies/attachments/tokens in the clear; no provider mutation during sync; keep the localhost-first, no-public-deploy gate. First-run classification of the first batch reuses the existing deterministic rules (Phase 20 improves them); the sync page shows facts, never fabricated progress.

## Phase 20: Classification Quality — embedded ML on a measured benchmark

**Goal:** Improve sender/message classification accuracy measurably, using enriched deterministic signals plus an embedded on-device ML classifier (no LLM, no external AI-API calls per DECISIONS.md §10), while keeping every recommendation explainable and the deterministic rules as a correctable fallback.

**Status:** Planned

**Requirements:** CLASS-01, CLASS-02, CLASS-03, CLASS-04, CLASS-05, CLASS-06

**Depends on:** Phase 19 (fresh, trustworthy data to classify)

**Success criteria:**

1. A labeled ground-truth benchmark of senders/messages exists and produces a repeatable accuracy number for the current rules baseline.
2. Deterministic signals are enriched (header cadence, reply ratios, ESP/domain heuristics) beyond the shallow v1.1 header checks.
3. An embedded, in-JVM ML classifier is trained on the user's own metadata with no LLM/external AI-API call at train or inference time; a governance check verifies zero network model calls.
4. Every recommendation still shows a plain-English reason; the deterministic rules remain a first-class, user-correctable fallback.
5. The ML path is only adopted where it measurably beats the rules baseline on the benchmark; otherwise the rules baseline stands.
6. A user re-categorization (e.g. 20 Costco messages moved from Transactional to Lists/Newsletters) persists and is automatically re-applied to messages ingested in later backfill/sync batches, so the same sender is not re-misclassified as progressive history arrives — verified by a journey test that ingests, corrects, then ingests more.

**Cross-cutting constraints:** No LLM and no network AI-API call, ever (DECISIONS.md §10). Preserve metadata-only privacy and explainability. Account-local classification corrections from v1.1 must keep working, survive progressive ingestion, and continue to override model output.

## Phase 21: Architectural Remediation — the dedicated rewrite

**Goal:** With behavior locked by Phase 18 journey tests and data trustworthy, decompose the god-classes, replace false cross-instance locking, unify validation/error handling, sweep the housekeeping backlog, and make an explicit jOOQ decision.

**Status:** Planned

**Requirements:** ARCH-01, ARCH-02, ARCH-03, ARCH-04, ARCH-05, ARCH-06, ARCH-07

**Review coverage:** Fixes C-6 and resolves W-1..W-13 and I-1..I-12 not already handled.

**Depends on:** Phase 18 (behavior-constant tests), Phase 20 (classification internals stable before refactor)

**Success criteria:**

1. `UnsubscribeCandidateService` (`classify` ~169 lines / 887-line class) and the duplicated selection-preparation skeleton are decomposed into focused components; `AnalyticsService` no longer uses `null` beans as feature flags.
2. Cleanup staging no longer uses an in-JVM `ReentrantLock` for cross-instance exclusion (uses `pg_advisory_xact_lock` or existing row locks) and no per-account lock map leaks memory.
3. Controller input uses Bean Validation with a shared `@ControllerAdvice`; the copy-pasted validators and duplicated `currentAccount`/`resolveProvider` helpers are consolidated; side-effecting GETs are moved to POST.
4. Housekeeping sweep complete: one `Sha256` utility, defensive `byte[]` copies + no public `id` setter, injected `Clock`, GONE/no-op stubs and `remove_rule_interactions.py` removed, consistent enum persistence and controller return types.
5. An explicit jOOQ decision is recorded (adopt with a single generated schema, or consciously defer now the dual-dialect risk is gone) with rationale.
6. All journey tests from Phase 18 still pass unchanged — no behavior regression from the refactor.

**Cross-cutting constraints:** No behavior change to user-visible flows; preserve exact-ID action safety, CSRF, protected-sender auditing, and metadata-only privacy. Do not reintroduce export. Refactors land under green journey tests.

## Phase 22: Gmail Rules & Bulk Action Feedback

**Goal:** Deliver two net-new capabilities that depend on the expanded Gmail scopes and the refactored execution engine: let the user turn a cleanup decision into a persistent Gmail-side filter/label and open a scoped message set directly in Gmail, and give every slow bulk action honest live progress with per-item status, a time estimate, and durable background execution.

**Status:** Planned

**Requirements:** SCOPE-01, RULE-01, RULE-02, PERF-01, PERF-02

**Depends on:** Phase 21 (consolidated execution/validation layer and moved-to-POST side-effecting endpoints), DECISIONS.md §12 (scope expansion) and the maintainer's Google Cloud Console change + re-consent

**Success criteria:**

1. Gmail OAuth consent requests `gmail.settings.basic` and `contacts.readonly` in addition to the existing scopes; before the maintainer adds them in Google Cloud Console the app degrades gracefully — scope-dependent features are hidden, and the OAuth handshake does not fail.
2. A user can turn a cleanup decision into a persistent Gmail filter/rule and/or label created inside their own Gmail (e.g. "always archive future mail from this sender"), and the created rule is visible in-app and reversible.
3. A user can open a materialized group of messages (a sender or category selection) directly in the Gmail web UI from within TidyMail, using the exact scoped message set.
4. A slow multi-step action (bulk unsubscribe across several senders, or a large multi-action Review approval) shows a live progress indicator with per-item status and a time estimate, instead of a blocked request with no feedback; fast single `batchModify` archive/trash/mark-read actions get honest confirmation rather than a fake long-running bar.
5. A long-running action executes on a background worker; the request returns immediately, the user can watch progress and see partial-failure detail, and the result is durably recorded even if the browser closes mid-run.

**Cross-cutting constraints:** Preserve exact immutable message-ID scopes, protected-sender auditing, CSRF, and metadata-only privacy. `contacts.readonly` is read-only and feeds classification signal only; it never mutates contacts. Gmail rule/label creation is reversible and visible; never create rules silently. Progress and ETA are honest facts derived from real per-item completion, never fabricated animation. No public deploy before localhost acceptance.

## Coverage (v1.2)

| Phase | Requirements | Count |
|-------|--------------|-------|
| 18 | TEST-01..05 | 5 |
| 19 | FRESH-01..10 | 10 |
| 20 | CLASS-01..06 | 6 |
| 21 | ARCH-01..07 | 7 |
| 22 | SCOPE-01, RULE-01..02, PERF-01..02 | 5 |
| **Total** | **All v1.2 requirements** | **33** |

**Coverage validation:** 33 mapped, 0 unmapped, 0 multiply mapped.

---
*Last updated: 2026-08-13 — v1.2 expanded to Phases 18–22: Test Foundation (+TEST-05 synthetic dataset) → Freshness & First-Run Onboarding (+FRESH-06..10) → Classification (+CLASS-06 correction persistence) → Architectural Remediation → Gmail Rules & Bulk Action Feedback (new Phase 22: SCOPE/RULE/PERF). v1.1 remains complete and closed.*
