# Requirements: TidyMail

**Defined:** 2026-08-09
**Milestone:** v1.1 Complete Cleanup Workspace
**Core Value:** Users can confidently clean large amounts of inbox clutter through exact, explainable, reversible actions without exposing message bodies.

## v1.1 Requirements

### Metadata And Sync

- [x] **DATA-01**: User can browse indexed message metadata without a Gmail scan running inside the page request. [Evidence](phases/09-metadata-index-and-action-foundations/09-VERIFICATION.md)
- [x] **DATA-02**: User can refresh metadata incrementally with visible queued, running, complete, partial, and failed states. [Evidence](phases/09-metadata-index-and-action-foundations/09-VERIFICATION.md)
- [x] **DATA-03**: User can see sync progress, messages scanned, last successful sync, and a useful failure explanation. [Evidence](phases/09-metadata-index-and-action-foundations/09-VERIFICATION.md)
- [x] **DATA-04**: User can use sender, subject header, domain, received time, labels, unread state, thread references, and unsubscribe headers while message bodies and attachments remain unavailable. [Evidence](phases/09-metadata-index-and-action-foundations/09-VERIFICATION.md)
- [x] **DATA-05**: User sees one current metadata row per provider message after repeated or overlapping sync runs. [Evidence](phases/09-metadata-index-and-action-foundations/09-VERIFICATION.md)
- [x] **DATA-06**: User can start using indexed results while older pages continue syncing in the background. [Evidence](phases/09-metadata-index-and-action-foundations/09-VERIFICATION.md)
- [x] **DATA-07**: User can select one linked account or all supported linked accounts consistently across workspace pages. [Evidence](phases/09-metadata-index-and-action-foundations/09-VERIFICATION.md)

### Workspace

- [x] **WORK-01**: User can navigate between Senders, Unread, Unsubscribe, Review, and Analytics through one responsive application shell. [Evidence](phases/09-metadata-index-and-action-foundations/09-VERIFICATION.md)
- [x] **WORK-02**: User can reproduce a filtered, sorted, paginated view by loading its query-string URL. [Evidence](phases/10-sender-and-unread-decision-surfaces/10-VERIFICATION.md#requirement-evidence)
- [x] **WORK-03**: User sees consistent loading, empty, partial-data, stale-data, and error states on every workspace page. [Evidence](phases/09-metadata-index-and-action-foundations/09-VERIFICATION.md)
- [x] **WORK-04**: User can see the queued-action count and current account scope from every workspace page. [Evidence](phases/09-metadata-index-and-action-foundations/09-VERIFICATION.md)
- [x] **WORK-05**: User never encounters a rendered action, selector, drilldown, or export control that is decorative only. [Evidence](phases/13-analytics-export-and-integrated-product-verification/13-VERIFICATION.md#requirement-evidence)

### Senders

- [x] **SEND-01**: User can search senders by display name, address, or domain. [Evidence](phases/10-sender-and-unread-decision-surfaces/10-VERIFICATION.md#requirement-evidence)
- [x] **SEND-02**: User can filter senders by time window, account, deterministic type, risk, unread pressure, queue state, and protected state. [Evidence](phases/10-sender-and-unread-decision-surfaces/10-VERIFICATION.md#requirement-evidence)
- [x] **SEND-03**: User can sort sender rows by volume, unread count, age, risk, recommendation, or recent activity. [Evidence](phases/10-sender-and-unread-decision-surfaces/10-VERIFICATION.md#requirement-evidence)
- [x] **SEND-04**: User can see each sender's volume, unread count, last activity, deterministic type, recommendation, risk, and plain-English signal explanation. [Evidence](phases/10-sender-and-unread-decision-surfaces/10-VERIFICATION.md#requirement-evidence)
- [x] **SEND-05**: User can select individual sender rows, all visible rows, or all matching rows with an exact selected-message preview. [Evidence](phases/10-sender-and-unread-decision-surfaces/10-VERIFICATION.md#requirement-evidence)
- [x] **SEND-06**: User can queue or immediately run archive, trash, mark-read, keep, protect, and review decisions from a sender row or bulk selection. [Evidence](phases/12-review-dual-execution-history-undo/12-VERIFICATION.md#requirement-evidence)
- [x] **SEND-07**: User can exclude protected senders from bulk selection server-side and explicitly include them only through an audited override. [Evidence](phases/10-sender-and-unread-decision-surfaces/10-VERIFICATION.md#requirement-evidence)

### Unread

- [x] **UNRD-01**: User can browse unread messages with sender, subject header, age, thread state, deterministic category, and recommendation. [Evidence](phases/10-sender-and-unread-decision-surfaces/10-VERIFICATION.md#requirement-evidence)
- [x] **UNRD-02**: User can filter unread rows by one-to-one/list type, age, waiting-on-me state, thread shape, recommendation, account, sender, and provider. [Evidence](phases/10-sender-and-unread-decision-surfaces/10-VERIFICATION.md#requirement-evidence)
- [x] **UNRD-03**: User can search unread rows by sender, subject header, or domain. [Evidence](phases/10-sender-and-unread-decision-surfaces/10-VERIFICATION.md#requirement-evidence)
- [x] **UNRD-04**: User can see plain-English reasons for reply-required, review, archive, delete, mark-read, and keep recommendations. [Evidence](phases/10-sender-and-unread-decision-surfaces/10-VERIFICATION.md#requirement-evidence)
- [x] **UNRD-05**: User can select unread rows individually, by visible page, or by the full filtered result with an exact impact preview. [Evidence](phases/10-sender-and-unread-decision-surfaces/10-VERIFICATION.md#requirement-evidence)
- [x] **UNRD-06**: User can open the exact message or thread in the provider web interface. [Evidence](phases/10-sender-and-unread-decision-surfaces/10-VERIFICATION.md#requirement-evidence)
- [x] **UNRD-07**: User can queue or immediately run archive, trash, mark-read, keep, and review decisions on unread rows. [Evidence](phases/12-review-dual-execution-history-undo/12-VERIFICATION.md#requirement-evidence)

### Unsubscribe

- [x] **UNSB-01**: User can browse unsubscribe candidates ranked by estimated monthly volume, confidence, method, age, and selected status. [Evidence](phases/11-safe-unsubscribe-and-protection/11-VERIFICATION.md#requirement-evidence)
- [x] **UNSB-02**: User can filter candidates by one-click/manual method, volume, confidence, review need, age, account, and protected state. [Evidence](phases/11-safe-unsubscribe-and-protection/11-VERIFICATION.md#requirement-evidence)
- [x] **UNSB-03**: User can inspect the destination host and detected unsubscribe headers before approving an automated request. [Evidence](phases/11-safe-unsubscribe-and-protection/11-VERIFICATION.md#requirement-evidence)
- [x] **UNSB-04**: User can execute RFC 8058 HTTPS one-click POST when the required header pair is present. [Evidence](phases/11-safe-unsubscribe-and-protection/11-VERIFICATION.md#requirement-evidence)
- [x] **UNSB-05**: User can explicitly choose a guarded legacy HTTPS GET path when one-click POST is unavailable. [Evidence](phases/11-safe-unsubscribe-and-protection/11-VERIFICATION.md#requirement-evidence)
- [x] **UNSB-06**: User can open a `mailto:` or provider/account-page fallback when automated unsubscribe is unavailable or inappropriate. [Evidence](phases/11-safe-unsubscribe-and-protection/11-VERIFICATION.md#requirement-evidence)
- [x] **UNSB-07**: User can save a candidate selection, send it to Review, or execute it immediately under the selected action policy. [Evidence](phases/12-review-dual-execution-history-undo/12-VERIFICATION.md#requirement-evidence)
- [x] **UNSB-08**: User can see pending, accepted, confirmed, failed, manual-required, and verification-pending unsubscribe outcomes. [Evidence](phases/11-safe-unsubscribe-and-protection/11-VERIFICATION.md#requirement-evidence)

### Review And Actions

- [x] **ACTN-01**: User can choose staged-review mode or opt-in immediate mode without changing action scope or safety guarantees. [Evidence](phases/12-review-dual-execution-history-undo/12-VERIFICATION.md#requirement-evidence)
- [x] **ACTN-02**: User can inspect the exact message count, target summary, risk, and scope hash before execution. Phase 15 supersedes the separate pre-queue Selection Preview page for Bulk Archive/Delete and Unread Only: the direct cleanup-row or selected-sender action is the staging intent, the server materializes the exact scope and returns the exact count/staged result inline without navigation, and Review is the required pre-execution inspection/approval surface preserving that immutable count and hash. Other action surfaces retain their existing preview requirements where applicable. [Phase 9 foundation evidence](phases/09-metadata-index-and-action-foundations/09-VERIFICATION.md#requirement-evidence)
- [x] **ACTN-03**: User can rely on preview, approval, execution, history, and undo using the same immutable message-ID scope without a later query. [Evidence](phases/09-metadata-index-and-action-foundations/09-VERIFICATION.md#requirement-evidence)
- [x] **ACTN-04**: User can review queued actions grouped by archive, trash, mark-read, unsubscribe, keep, and protect. [Evidence](phases/12-review-dual-execution-history-undo/12-VERIFICATION.md#requirement-evidence)
- [x] **ACTN-05**: User can remove one queued action, approve selected actions, or approve all eligible actions. [Evidence](phases/12-review-dual-execution-history-undo/12-VERIFICATION.md#requirement-evidence)
- [x] **ACTN-06**: User can see conflicts when incompatible active actions target the same message and resolve them before execution. [Evidence](phases/12-review-dual-execution-history-undo/12-VERIFICATION.md#requirement-evidence)
- [x] **ACTN-07**: User can see requested, accepted, succeeded, failed, and verification-pending counts plus per-target failure summaries. [Evidence](phases/12-review-dual-execution-history-undo/12-VERIFICATION.md#requirement-evidence)
- [x] **ACTN-08**: User can undo applied archive, trash, and mark-read operations using only IDs recorded as applied. [Evidence](phases/12-review-dual-execution-history-undo/12-VERIFICATION.md#requirement-evidence)
- [x] **ACTN-09**: User can see why unsubscribe and protect decisions are not mechanically reversible and choose an available compensating action. [Evidence](phases/12-review-dual-execution-history-undo/12-VERIFICATION.md#requirement-evidence)
- [x] **ACTN-10**: User can browse durable action history by date, account, action type, status, and target. [Evidence](phases/12-review-dual-execution-history-undo/12-VERIFICATION.md#requirement-evidence)

### Analytics

- [x] **ANLY-01**: User can view received, triaged, archived, trashed, marked-read, and unsubscribed metrics for a selected date range. [Evidence](phases/13-analytics-export-and-integrated-product-verification/13-VERIFICATION.md#requirement-evidence)
- [x] **ANLY-02**: User can group analytics by day, week, or month and filter by account and action type. [Evidence](phases/13-analytics-export-and-integrated-product-verification/13-VERIFICATION.md#requirement-evidence)
- [x] **ANLY-03**: User can compare the selected range with the immediately preceding equivalent range. [Evidence](phases/13-analytics-export-and-integrated-product-verification/13-VERIFICATION.md#requirement-evidence)
- [x] **ANLY-04**: User can view received-versus-cleaned volume over time and drill into the source rows for a bucket. [Evidence](phases/13-analytics-export-and-integrated-product-verification/13-VERIFICATION.md#requirement-evidence)
- [x] **ANLY-05**: User can view top senders by received volume and open the corresponding filtered sender queue. [Evidence](phases/13-analytics-export-and-integrated-product-verification/13-VERIFICATION.md#requirement-evidence)
- [x] **ANLY-06**: User can view action outcomes, review coverage, cleanup coverage, and unsubscribe completion with explicit denominators. [Evidence](phases/13-analytics-export-and-integrated-product-verification/13-VERIFICATION.md#requirement-evidence)
- [x] **ANLY-07**: User can view inbox reduction, cleanup rate, median triage delay, time-to-decision distribution, and confidence retention. [Evidence](phases/13-analytics-export-and-integrated-product-verification/13-VERIFICATION.md#requirement-evidence)
- [x] **ANLY-08**: User sees unavailable or incomplete historical metrics labeled honestly instead of inferred from current totals. [Evidence](phases/13-analytics-export-and-integrated-product-verification/13-VERIFICATION.md#requirement-evidence)

### Export And Snapshot

- [x] **EXPT-01**: Historically delivered in Phase 13: user could export filtered Senders, Unread, Unsubscribe, Review history, or Analytics views as CSV. **Status: superseded for user-facing product behavior by Phase 15 D-06; authenticated workspace pages must not render or advertise export controls.** Backend code may remain undiscoverable. [Historical evidence](phases/13-analytics-export-and-integrated-product-verification/13-VERIFICATION.md#requirement-evidence)
- [x] **EXPT-02**: Historically delivered in Phase 13: exports used the same server-side filters and account scope as the visible page. **Status: superseded for user-facing product behavior by Phase 15 D-06; no authenticated workspace export entry point is discoverable.** Retained backend behavior remains subject to its existing scope/security tests. [Historical evidence](phases/13-analytics-export-and-integrated-product-verification/13-VERIFICATION.md#requirement-evidence)
- [x] **EXPT-03**: Historically delivered in Phase 13: user could download a sanitized analytics snapshot without tokens, message bodies, attachment data, or hidden raw headers. **Status: superseded for user-facing product behavior by Phase 15 D-06; authenticated workspace pages must not render or advertise snapshot/download controls.** Retained backend privacy tests remain authoritative for undiscoverable code. [Historical evidence](phases/13-analytics-export-and-integrated-product-verification/13-VERIFICATION.md#requirement-evidence)

**Latest contract:** Phase 15 D-06 is the current product requirement for EXPT-01..03 discoverability. Phase 13 completion remains historical evidence, not permission to expose CSV, snapshot, download, or export affordances in the authenticated workspace.

### Quality And Safety

- [x] **QUAL-01**: User receives no automated unsubscribe request to loopback, private, link-local, non-HTTPS, or redirect-escaped destinations. [Evidence](phases/11-safe-unsubscribe-and-protection/11-VERIFICATION.md#requirement-evidence)
- [ ] **QUAL-02**: User can use all five workspace pages at 375px without page-level horizontal scrolling and with 44px touch targets.
- [x] **QUAL-03**: User can understand every badge, score, risk, recommendation, metric, and action through visible labels or accessible help text. [Evidence](phases/10-sender-and-unread-decision-surfaces/10-VERIFICATION.md#requirement-evidence)
- [x] **QUAL-04**: User sees workspace shells within 1 second and indexed filtered results within 2 seconds on the supported localhost dataset. [Evidence](phases/09-metadata-index-and-action-foundations/09-VERIFICATION.md)
- [x] **QUAL-05**: User can sync at least 500 Gmail metadata records without serial per-message page latency or duplicate rows. [Evidence](phases/09-metadata-index-and-action-foundations/09-VERIFICATION.md)
- [x] **QUAL-06**: User can complete a real localhost Gmail cycle from sync through action, review or immediate apply, result, analytics, and undo before any public deployment. [Evidence](phases/13-analytics-export-and-integrated-product-verification/13-VERIFICATION.md#requirement-evidence)

### Latest Design Contract Alignment

- [x] **UX-01**: User selects one owned active account from the shared left rail and can start Gmail add-account from the same selector. [Evidence](phases/13-analytics-export-and-integrated-product-verification/13-DRAFT-CONTRACT-ALIGNMENT.md)
- [x] **UX-02**: User can log out without deleting data or explicitly confirm complete user-data deletion before logout. [Evidence](phases/13-analytics-export-and-integrated-product-verification/13-DRAFT-CONTRACT-ALIGNMENT.md)
- [x] **UX-03**: User can use quiet category-accordion workflows for Bulk Archive/Delete and Unread, including safe user-category assignment from Other. [Evidence](phases/13-analytics-export-and-integrated-product-verification/13-DRAFT-CONTRACT-ALIGNMENT.md)
- [x] **UX-04**: User can process Bulk Unsubscribe as an individual sender workflow with category, exact time range, search, fallback setting, and reusable stats modal. [Evidence](phases/13-analytics-export-and-integrated-product-verification/13-DRAFT-CONTRACT-ALIGNMENT.md)
- [x] **UX-05**: User sees Review as a LIFO ledger ordered by queued time and sequence with exact selected counts and delete-specific warnings. [Evidence](phases/13-analytics-export-and-integrated-product-verification/13-DRAFT-CONTRACT-ALIGNMENT.md)
- [x] **UX-06**: User can filter one-account Analytics by period and intelligent grouping across metric tabs, rankings, size cards, and archived-versus-deleted results. [Evidence](phases/13-analytics-export-and-integrated-product-verification/13-DRAFT-CONTRACT-ALIGNMENT.md)
- [x] **UX-07**: User actions execute only from exact materialized message IDs while protected-sender and override auditing remain enforced. [Evidence](phases/13-analytics-export-and-integrated-product-verification/13-DRAFT-CONTRACT-ALIGNMENT.md)
- [x] **UX-08**: User sees a loaded denominator only when the active provider declares it reliable, with Gmail filtered scopes falling back to loaded-count wording. [Evidence](phases/13-analytics-export-and-integrated-product-verification/13-DRAFT-CONTRACT-ALIGNMENT.md)
- [ ] **UX-09**: User sees text-only left-rail navigation labels that exactly match the page titles, without numeric status badges on Unread Only or Review.
- [ ] **UX-10**: User sees Unread Only category copy that is concise, user-friendly, and free of stale explanatory notes above the category list.
- [ ] **UX-11**: User can stage Bulk Archive/Delete and Unread Only actions to Review without a persistent generic `Ready to stage for Review` status panel on the cleanup pages.
- [ ] **UX-12**: User sees cleanup category sections rendered as compact mock-aligned rows, without a broad pill/card enclosure, and each category header shows the mock-required sender count plus size or unread total.
- [ ] **UX-13**: User sees Bulk Archive/Delete and Unread Only sender rows with mock-aligned spacing, button rounding, density, hierarchy, and professional visual weight.
- [ ] **UX-14**: User can correct sender categories through the mock-aligned category-change/create-category controls, with user-created categories visibly marked `User generated`.
- [ ] **UX-15**: User receives a page-by-page authenticated walkthrough report comparing the running app against `tidymail-ux-rehaul-draft/tidymail-ux-rehaul-draft.design` and its Build Notes before remediation is closed.

## v1.2 Requirements — Trustworthy Data & Maintainable Core

**Milestone:** v1.2 Trustworthy Data & Maintainable Core
**Defined:** 2026-08-13
**Core Value:** Fresh data the user can trust, classification good enough to act on, and a maintainable core — proven by user-journey tests, not phase-named boilerplate.

Requirements are written as user/maintainer journeys per the project's testing convention. The maintainer is a first-class "user" for this milestone because half its value is code health.

### Test Foundation (Phase 18)

- [x] **TEST-01**: Maintainer can run the full integration suite against real PostgreSQL via Testcontainers, against `src/main/resources/db/migration` directly, with no separate H2 test-migration set and no `h2` dialect branch remaining in repository code. *(Root-fixes C-1, C-2, C-3.)*
- [x] **TEST-02**: Maintainer can read the primary suite as named user journeys (e.g. `userConnectsGmailAndSeesTheirNoisiestSenders`, `userBulkArchivesAndUndoesExactly`) that exercise real HTTP routes and real DB rows, rather than phase-numbered, mock-heavy tests. *(Addresses I-11.)*
- [x] **TEST-03**: Maintainer confirms the export subsystem no longer exists on the wire — no `/*.csv` or snapshot endpoint is reachable by any authenticated user, and the controller, service, `export/` package, `ExportProperties`, the `tidymail.export.max-rows` property, and the dead `exportUrl` field are removed. *(Fixes C-5 and removes the C-4 streaming-transaction path.)*
- [x] **TEST-04**: Maintainer sees no green test that could pass on H2 while production Postgres SQL differs; a shared test harness/base removes copy-pasted datasource/Flyway/`user_accounts` fixtures.
- [x] **TEST-05**: Maintainer has a large, PII-stripped synthetic dataset (≥10,000 messages, ideally larger) derived from the shape of the real ~119,811-row mailbox — with addresses, names, subjects, and IDs anonymized — that seeds Testcontainers-Postgres so E2E and performance journeys run against realistic volume, not a 100-row toy inbox.

### Mailbox Freshness & First-Run Onboarding (Phase 19)

- [ ] **FRESH-01**: User sees a newly arrived Gmail message reflected in the tool within one sync cycle, without waiting for historical backfill to finish.
- [ ] **FRESH-02**: User's large-mailbox historical backfill continues in the background without starving new-mail ingestion (new-mail sync and backfill no longer share one blocking pagination cursor).
- [ ] **FRESH-03**: User's incremental sync advances from the Gmail History API (`historyId`) for change detection, falling back safely to a bounded rescan when history is expired or unavailable.
- [ ] **FRESH-04**: User sees honest freshness state — last successful sync point, current lag, and whether a full rescan is in progress — with no fabricated "up to date" claim.
- [ ] **FRESH-05**: User's Gmail OAuth access token is refreshed through a managed client manager and the refreshed token is persisted, instead of a network refresh on every client build; null timestamps no longer coerce a token to already-expired. *(Fixes W-14.)*
- [ ] **FRESH-06**: A brand-new user (e.g. a second household account) sees populated senders/categories within seconds of first sign-in — a first batch of ~100–250 messages is ingested, classified, and rendered before full history arrives — never a blank screen. *(Addresses the accidental `MAX_PAGES=5 × PAGE_SIZE=100` cold-start behavior in `MessageMetadataSyncService`.)*
- [ ] **FRESH-07**: User can open a dedicated sync/activity page (separate from Analytics) that shows live background-ingestion progress — messages indexed so far, reliable total/known remaining, current phase, and an ETA only when supportable — while durable server-side synchronization continues after navigation or browser closure; active and queued states explicitly say the page can be closed and never instruct the user to keep a page, tab, or window open.
- [ ] **FRESH-08**: User's entire mailbox is progressively indexed with no GB/storage cap (per DECISIONS.md §11); an optional maintainer-only message-count ceiling (default unlimited) exists purely as a safety bound and is never surfaced as a Gmail-storage figure.
- [ ] **FRESH-09**: User sees one honest, consistently-labeled unread definition — Inbox-unread vs All-Mail-unread are computed and labeled distinctly, so the same message counted under multiple Gmail labels no longer produces conflicting totals (e.g. 8,200 vs 7,600). *(Extends the v1.1 Inbox-only unread work with an explicit All-Mail contrast and a single source of truth.)*
- [ ] **FRESH-10**: Maintainer has end-to-end evidence that unread state, attachment presence, and size-estimate are extracted from `format=METADATA`, ingested, stored, and displayed correctly against a real mailbox — verified by a journey test, not assumed. *(Audits `GmailMailService.toMailMessage` extraction vs `MessageMetadata` storage vs UI display.)*

### Gmail Rules & Scope Expansion (Phase 22)

- [ ] **SCOPE-01**: User's Gmail OAuth consent requests `gmail.settings.basic` and `contacts.readonly` in addition to the existing scopes; the app degrades gracefully (feature hidden, no handshake failure) until the maintainer has added the scopes in Google Cloud Console. *(Per DECISIONS.md §12.)*
- [ ] **RULE-01**: User can turn a cleanup decision into a persistent Gmail filter/rule and/or label created inside their own Gmail (e.g. "always archive future mail from this sender"), matching the inbox-zero capability TidyMail currently lacks, with the rule visible and reversible.
- [ ] **RULE-02**: User can open a materialized group of messages (a sender or category selection) directly in the Gmail web UI from within TidyMail, using the exact scoped message set.

### Bulk Action Execution Feedback (Phase 22)

- [ ] **PERF-01**: User taking a slow multi-step action (bulk unsubscribe across several senders, or a large multi-action Review approval) sees a live progress indicator with per-item status and a time estimate, instead of a blocked request with no feedback. *(Archive/Trash/Mark-read already execute as a single sub-second `batchModify` and only need honest confirmation, not a long-running bar.)*
- [ ] **PERF-02**: A long-running action executes on a background worker with the request returning immediately; the user can watch it progress and see partial-failure detail, and the result is durably recorded even if the browser closes mid-run.

### Classification Quality (Phase 20)

- [ ] **CLASS-01**: Maintainer can measure classification quality against a labeled ground-truth benchmark of senders/messages, so "accuracy" is a number, not a vibe.
- [ ] **CLASS-02**: User receives sender and message classifications enriched by deeper deterministic signals (header cadence, reply ratios, ESP/domain heuristics) beyond the shallow v1.1 header checks.
- [ ] **CLASS-03**: User's classifications are produced by an embedded, on-device ML classifier trained on their own metadata inside the JVM, with **no LLM call and no external AI-API call** at train or inference time (per DECISIONS.md §10).
- [ ] **CLASS-04**: User can still see a plain-English reason for every recommendation, and the deterministic rule set remains a first-class, user-correctable fallback when the model is unavailable or a correction is applied.
- [ ] **CLASS-05**: Maintainer only ships the ML path where it measurably beats the deterministic rules baseline on the CLASS-01 benchmark; otherwise the rules baseline stands.
- [ ] **CLASS-06**: When a user re-categorizes a sender (e.g. moves 20 Costco messages from Transactional to Lists/Newsletters), that correction persists and is automatically re-applied to messages ingested in later backfill/sync batches, so the same sender is not re-misclassified as new history arrives. *(Builds on the v1.1 account-local classification corrections; extends them to survive progressive ingestion.)*

### Architectural Remediation (Phase 21)

- [ ] **ARCH-01**: Maintainer works with a decomposed classification/unsubscribe layer — `UnsubscribeCandidateService`'s ~169-line `classify` and 887-line responsibility spread are split into focused components; the duplicated selection-preparation skeleton is extracted. *(W-1, W-2, W-4, W-5.)*
- [ ] **ARCH-02**: Maintainer works with an `AnalyticsService` that no longer uses `null` beans as feature flags and no longer holds a transaction across streaming response I/O. *(W-3; C-4 already removed with export in Phase 18.)*
- [ ] **ARCH-03**: Cleanup staging no longer relies on an in-JVM `ReentrantLock` for cross-instance mutual exclusion; it uses `pg_advisory_xact_lock` or the existing DB row locks, and no per-account lock map leaks memory. *(Fixes C-6.)*
- [ ] **ARCH-04**: Maintainer sees controller input validated through Bean Validation and errors rendered through a shared `@ControllerAdvice`, replacing the copy-pasted per-controller validators; `currentAccount`/`resolveProvider` helpers are consolidated. *(W-10, W-11, W-12.)*
- [ ] **ARCH-05**: Side-effecting/external-calling GET endpoints are moved to POST so unsafe writes are CSRF-protected and HTTP semantics are honest. *(W-13.)*
- [ ] **ARCH-06**: Maintainer confirms the housekeeping sweep is done — one `Sha256` utility, defensive `byte[]` copies and no public `id` setter on entities, injected `Clock` everywhere, GONE/no-op stubs and `remove_rule_interactions.py` removed, consistent enum persistence and controller return types. *(W-6..W-9, I-1..I-10, I-12.)*
- [ ] **ARCH-07**: Maintainer records an explicit jOOQ decision — adopt with a single generated schema, or consciously defer now that the dual-dialect risk is already removed — with the rationale captured. *(Closes the strategic finding.)*

## Future Requirements

### Provider Parity

- **OUTL-01**: User can use the complete v1.1 workspace with a linked Outlook account after the skipped Outlook provider implementation is completed.

### Advanced Workspace

- **VIEW-01**: User can save named filter and sort presets.
- **SHARE-01**: User can create an expiring, access-controlled analytics link.
- **PUSH-01**: User can receive provider push updates instead of scheduled polling.

## Out of Scope

| Feature | Reason |
|---------|--------|
| Message-body or attachment ingestion | Violates the metadata-only privacy contract |
| AI classification, summaries, drafts, or chat | Product remains deterministic and explainable |
| Permanent delete | Soft trash plus undo is the safe product boundary |
| Public snapshot links or user-facing snapshot downloads | Phase 15 D-06 removes workspace discoverability; any retained backend implementation remains undiscoverable and separately privacy-tested |
| Billing, teams, and public signup | Personal self-hosted product scope |
| New SPA frontend | Thymeleaf and htmx cover the required interactions |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| DATA-01 | Phase 9 | Complete |
| DATA-02 | Phase 9 | Complete |
| DATA-03 | Phase 9 | Complete |
| DATA-04 | Phase 9 | Complete |
| DATA-05 | Phase 9 | Complete |
| DATA-06 | Phase 9 | Complete |
| DATA-07 | Phase 9 | Complete |
| WORK-01 | Phase 9 | Complete |
| WORK-02 | Phase 10 | Complete ([evidence](phases/10-sender-and-unread-decision-surfaces/10-VERIFICATION.md#requirement-evidence)) |
| WORK-03 | Phase 9 | Complete |
| WORK-04 | Phase 9 | Complete |
| WORK-05 | Phase 13 | Complete ([evidence](phases/13-analytics-export-and-integrated-product-verification/13-VERIFICATION.md#requirement-evidence)) |
| SEND-01 | Phase 10 | Complete ([evidence](phases/10-sender-and-unread-decision-surfaces/10-VERIFICATION.md#requirement-evidence)) |
| SEND-02 | Phase 10 | Complete ([evidence](phases/10-sender-and-unread-decision-surfaces/10-VERIFICATION.md#requirement-evidence)) |
| SEND-03 | Phase 10 | Complete ([evidence](phases/10-sender-and-unread-decision-surfaces/10-VERIFICATION.md#requirement-evidence)) |
| SEND-04 | Phase 10 | Complete ([evidence](phases/10-sender-and-unread-decision-surfaces/10-VERIFICATION.md#requirement-evidence)) |
| SEND-05 | Phase 10 | Complete ([evidence](phases/10-sender-and-unread-decision-surfaces/10-VERIFICATION.md#requirement-evidence)) |
| SEND-06 | Phase 12 | Complete ([evidence](phases/12-review-dual-execution-history-undo/12-VERIFICATION.md#requirement-evidence)) |
| SEND-07 | Phase 10 | Complete ([evidence](phases/10-sender-and-unread-decision-surfaces/10-VERIFICATION.md#requirement-evidence)) |
| UNRD-01 | Phase 10 | Complete ([evidence](phases/10-sender-and-unread-decision-surfaces/10-VERIFICATION.md#requirement-evidence)) |
| UNRD-02 | Phase 10 | Complete ([evidence](phases/10-sender-and-unread-decision-surfaces/10-VERIFICATION.md#requirement-evidence)) |
| UNRD-03 | Phase 10 | Complete ([evidence](phases/10-sender-and-unread-decision-surfaces/10-VERIFICATION.md#requirement-evidence)) |
| UNRD-04 | Phase 10 | Complete ([evidence](phases/10-sender-and-unread-decision-surfaces/10-VERIFICATION.md#requirement-evidence)) |
| UNRD-05 | Phase 10 | Complete ([evidence](phases/10-sender-and-unread-decision-surfaces/10-VERIFICATION.md#requirement-evidence)) |
| UNRD-06 | Phase 10 | Complete ([evidence](phases/10-sender-and-unread-decision-surfaces/10-VERIFICATION.md#requirement-evidence)) |
| UNRD-07 | Phase 12 | Complete ([evidence](phases/12-review-dual-execution-history-undo/12-VERIFICATION.md#requirement-evidence)) |
| UNSB-01 | Phase 11 | Complete ([evidence](phases/11-safe-unsubscribe-and-protection/11-VERIFICATION.md#requirement-evidence)) |
| UNSB-02 | Phase 11 | Complete ([evidence](phases/11-safe-unsubscribe-and-protection/11-VERIFICATION.md#requirement-evidence)) |
| UNSB-03 | Phase 11 | Complete ([evidence](phases/11-safe-unsubscribe-and-protection/11-VERIFICATION.md#requirement-evidence)) |
| UNSB-04 | Phase 11 | Complete ([evidence](phases/11-safe-unsubscribe-and-protection/11-VERIFICATION.md#requirement-evidence)) |
| UNSB-05 | Phase 11 | Complete ([evidence](phases/11-safe-unsubscribe-and-protection/11-VERIFICATION.md#requirement-evidence)) |
| UNSB-06 | Phase 11 | Complete ([evidence](phases/11-safe-unsubscribe-and-protection/11-VERIFICATION.md#requirement-evidence)) |
| UNSB-07 | Phase 12 | Complete ([evidence](phases/12-review-dual-execution-history-undo/12-VERIFICATION.md#requirement-evidence)) |
| UNSB-08 | Phase 11 | Complete ([evidence](phases/11-safe-unsubscribe-and-protection/11-VERIFICATION.md#requirement-evidence)) |
| ACTN-01 | Phase 12 | Complete ([evidence](phases/12-review-dual-execution-history-undo/12-VERIFICATION.md#requirement-evidence)) |
| ACTN-02 | Phase 9 → Phase 15 | Phase 9 exact-preview foundation complete; Phase 15 supersedes the separate pre-queue preview page only for Bulk Archive/Delete and Unread Only with inline exact staging plus Review-before-execution. Other surfaces retain applicable preview behavior. |
| ACTN-03 | Phase 9 | Complete |
| ACTN-04 | Phase 12 | Complete ([evidence](phases/12-review-dual-execution-history-undo/12-VERIFICATION.md#requirement-evidence)) |
| ACTN-05 | Phase 12 | Complete ([evidence](phases/12-review-dual-execution-history-undo/12-VERIFICATION.md#requirement-evidence)) |
| ACTN-06 | Phase 12 | Complete ([evidence](phases/12-review-dual-execution-history-undo/12-VERIFICATION.md#requirement-evidence)) |
| ACTN-07 | Phase 12 | Complete ([evidence](phases/12-review-dual-execution-history-undo/12-VERIFICATION.md#requirement-evidence)) |
| ACTN-08 | Phase 12 | Complete ([evidence](phases/12-review-dual-execution-history-undo/12-VERIFICATION.md#requirement-evidence)) |
| ACTN-09 | Phase 12 | Complete ([evidence](phases/12-review-dual-execution-history-undo/12-VERIFICATION.md#requirement-evidence)) |
| ACTN-10 | Phase 12 | Complete ([evidence](phases/12-review-dual-execution-history-undo/12-VERIFICATION.md#requirement-evidence)) |
| ANLY-01 | Phase 13 | Complete ([evidence](phases/13-analytics-export-and-integrated-product-verification/13-VERIFICATION.md#requirement-evidence)) |
| ANLY-02 | Phase 13 | Complete ([evidence](phases/13-analytics-export-and-integrated-product-verification/13-VERIFICATION.md#requirement-evidence)) |
| ANLY-03 | Phase 13 | Complete ([evidence](phases/13-analytics-export-and-integrated-product-verification/13-VERIFICATION.md#requirement-evidence)) |
| ANLY-04 | Phase 13 | Complete ([evidence](phases/13-analytics-export-and-integrated-product-verification/13-VERIFICATION.md#requirement-evidence)) |
| ANLY-05 | Phase 13 | Complete ([evidence](phases/13-analytics-export-and-integrated-product-verification/13-VERIFICATION.md#requirement-evidence)) |
| ANLY-06 | Phase 13 | Complete ([evidence](phases/13-analytics-export-and-integrated-product-verification/13-VERIFICATION.md#requirement-evidence)) |
| ANLY-07 | Phase 13 | Complete ([evidence](phases/13-analytics-export-and-integrated-product-verification/13-VERIFICATION.md#requirement-evidence)) |
| ANLY-08 | Phase 13 | Complete ([evidence](phases/13-analytics-export-and-integrated-product-verification/13-VERIFICATION.md#requirement-evidence)) |
| EXPT-01 | Phase 13 → Phase 15 | Phase 13 complete historically; user-facing discoverability superseded by Phase 15 D-06 ([historical evidence](phases/13-analytics-export-and-integrated-product-verification/13-VERIFICATION.md#requirement-evidence)) |
| EXPT-02 | Phase 13 → Phase 15 | Phase 13 complete historically; user-facing discoverability superseded by Phase 15 D-06 ([historical evidence](phases/13-analytics-export-and-integrated-product-verification/13-VERIFICATION.md#requirement-evidence)) |
| EXPT-03 | Phase 13 → Phase 15 | Phase 13 complete historically; user-facing discoverability superseded by Phase 15 D-06 ([historical evidence](phases/13-analytics-export-and-integrated-product-verification/13-VERIFICATION.md#requirement-evidence)) |
| QUAL-01 | Phase 11 | Complete ([evidence](phases/11-safe-unsubscribe-and-protection/11-VERIFICATION.md#requirement-evidence)) |
| QUAL-02 | Phase 15 | Pending; five-page responsive acceptance planned in 15-08 |
| QUAL-03 | Phase 10 | Complete ([evidence](phases/10-sender-and-unread-decision-surfaces/10-VERIFICATION.md#requirement-evidence)) |
| QUAL-04 | Phase 9 | Complete |
| QUAL-05 | Phase 9 | Complete |
| QUAL-06 | Phase 13 | Complete ([evidence](phases/13-analytics-export-and-integrated-product-verification/13-VERIFICATION.md#requirement-evidence)) |
| UX-01 | Phase 13 | Complete |
| UX-02 | Phase 13 | Complete |
| UX-03 | Phase 13 | Complete |
| UX-04 | Phase 13 | Complete |
| UX-05 | Phase 13 | Complete |
| UX-06 | Phase 13 | Complete |
| UX-07 | Phase 13 | Complete |
| UX-08 | Phase 13 | Complete |
| UX-09 | Phase 16 | Pending |
| UX-10 | Phase 16 | Pending |
| UX-11 | Phase 16 | Pending |
| UX-12 | Phase 16 | Pending |
| UX-13 | Phase 16 | Pending |
| UX-14 | Phase 16 | Pending |
| UX-15 | Phase 16 | Pending |
| TEST-01 | Phase 18 | Planned |
| TEST-02 | Phase 18 | Planned |
| TEST-03 | Phase 18 | Planned |
| TEST-04 | Phase 18 | Planned |
| TEST-05 | Phase 18 | Planned |
| FRESH-01 | Phase 19 | Planned |
| FRESH-02 | Phase 19 | Planned |
| FRESH-03 | Phase 19 | Planned |
| FRESH-04 | Phase 19 | Planned |
| FRESH-05 | Phase 19 | Planned |
| FRESH-06 | Phase 19 | Planned |
| FRESH-07 | Phase 19 | Planned |
| FRESH-08 | Phase 19 | Planned |
| FRESH-09 | Phase 19 | Planned |
| FRESH-10 | Phase 19 | Planned |
| CLASS-01 | Phase 20 | Planned |
| CLASS-02 | Phase 20 | Planned |
| CLASS-03 | Phase 20 | Planned |
| CLASS-04 | Phase 20 | Planned |
| CLASS-05 | Phase 20 | Planned |
| CLASS-06 | Phase 20 | Planned |
| ARCH-01 | Phase 21 | Planned |
| ARCH-02 | Phase 21 | Planned |
| ARCH-03 | Phase 21 | Planned |
| ARCH-04 | Phase 21 | Planned |
| ARCH-05 | Phase 21 | Planned |
| ARCH-06 | Phase 21 | Planned |
| ARCH-07 | Phase 21 | Planned |
| SCOPE-01 | Phase 22 | Planned |
| RULE-01 | Phase 22 | Planned |
| RULE-02 | Phase 22 | Planned |
| PERF-01 | Phase 22 | Planned |
| PERF-02 | Phase 22 | Planned |

**Coverage:**

- v1.1 requirements: 76 total, 76 mapped, 0 unmapped
- v1.2 requirements: 33 total (TEST ×5, FRESH ×10, CLASS ×6, ARCH ×7, SCOPE ×1, RULE ×2, PERF ×2), 33 mapped, 0 unmapped
- v1.2 phase distribution: Phase 18 ×5, Phase 19 ×10, Phase 20 ×6, Phase 21 ×7, Phase 22 ×5

---
*Requirements defined: 2026-08-09*
*Last updated: 2026-08-13 — v1.2 expanded (FRESH-06..10, TEST-05, CLASS-06, SCOPE-01, RULE-01/02, PERF-01/02) from functionality & performance feedback; Phase 22 added*
