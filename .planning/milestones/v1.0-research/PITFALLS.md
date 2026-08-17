# Pitfalls Research
**Project:** TidyMail (Java/Spring Boot)
**Researched:** 2026-08-07
**Confidence:** HIGH
**Source:** Carried forward from InboxTriage + new Java-stack-specific pitfalls

## CARRIED FORWARD — CRITICAL PITFALLS (from InboxTriage/.planning/research/PITFALLS.md)

Pitfall 1: Re-skinning without changing the product model.
What goes wrong: UI looks nicer but users still don't understand page purpose.
Avoid: Lock nav + page purpose BEFORE detailed styling. (Phase 2/7)

Pitfall 2: Letting Review become complex again.
What goes wrong: Review page becomes a per-email workbench with nested controls.
Avoid: Review = confirmation ledger only (include/exclude/progress). (Phase 7)

Pitfall 3: Promising unsupported behaviors too early.
What goes wrong: UI shows Gmail rules/filters/attachments BEFORE API support validated.
Avoid: Placeholder pages with honest status. (Phase 7/8)

Pitfall 4: Breaking working behavior during remap.
What goes wrong: Route/template moves cause count mismatches, silent queue failures.
Avoid: Adapter-first; targeted verification after every remap.

Pitfall 5: Weak safety/progress signaling.
What goes wrong: Users don't trust destructive actions (scope/reversibility/progress unclear).
Avoid: Explicit counts, checked-by-default selections, EXECUTE wording, visible progress. (Phase 7)

## CARRIED FORWARD — SPECIFIC PAST BUGS (from Progress Check - mid-PRD.md section 3 What V1 got wrong)

Bug A1: Three surfaces before any one worked → Ship ONE loop end-to-end first. This is the #1 lesson.
Bug A2: Recommendation cards hard-coded order, not by impact → ALWAYS sort counts DESCENDING with visible arrow.
Bug A3: "Archive all N" archived MORE than N → Action query MUST equal UI query with NO exceptions. THIS IS NON-NEGOTIABLE. Add a backend assertion: size(affectedIds) == cardCount or rollback.
Bug A4: Undo navigates away and doesn't restore → Undo replays inverse IN PLACE. No redirect. htmx swap.
Bug A5: Duplicate URL params ?q=&q=&bucket= → One server-side source of truth for query state.
Bug A6: Action removes row in JS but doesn't refresh totals → After every action, fetch totals fragment from server.
Bug A7: Empty report tables (sender_stats no rows) → Build-time test fails if seed produces empty tables.
Bug A8: 375px viewport overflows 595px → Mobile-first CSS. Test 375px on every PR. QA-UX-AUDIT section 6.
Bug A9: 27 decisions before first usable build → CAP 10 decisions per release. Extras in issue tracker.
Bug A10: "Review all" auto-decided 143 → Buttons named "Review" must navigate, NEVER decide.
Bug A11: Free-tier-only caps at ~15 users/day → If opening signups, pick paid tier deliberately and document math.
Bug A12: Single Worker invocation handled whole pipeline (30s limit) → Java doesn't have 30s limit, but still use @Async lazy load + progress polling.
Bug A13: Hard cap decisions not communicated → Document caps in UI.
Bug A14: Actions not persisted → EVERY action writes UserAction row BEFORE showing success.
Bug A15: Cryptic naming ("Inbox Triage", "Clues", "Signals (6)") → Plain English everywhere.

## NEW PITFALLS — Java / Spring Boot / JVM specific

Pitfall J1: OAuth token storage in plaintext in JPA entity.
Risk: If DB is compromised, attackers get full Gmail/Outlook access via refresh token.
Mitigation: Use TOKEN_ENCRYPT_SECRET with AES/GCM, encrypt refresh_token + access_token fields transparently via @Converter or EntityListener. Never toString() those fields. Never log them at any level. Test: SELECT refresh_token FROM user_account WHERE ... returns base64 ciphertext, not JWT-looking plaintext.

Pitfall J2: Spring Security misconfiguration leaks endpoints.
Risk: /api/senders, /api/actions, /dev/seed accessible without auth.
Mitigation: SecurityConfig.anyRequest().authenticated(). Only "/", "/login/**", "/oauth2/**", "/error", "/static/**" permitAll. CSRF enabled (not disabled!). Add an integration test that asserts 401 on every protected endpoint without auth.

Pitfall J3: @Scheduled jobs running on multiple deployment instances.
Risk: If user scales to 2 JVMs behind load balancer, sync runs twice, double API calls, double Gmail quota burn.
Mitigation: Document single-instance deployment for personal/small-user phase. If multi-instance later, use ShedLock or DB-based advisory lock (SELECT ... FOR UPDATE on a single-row scheduler_lock table). For now, single instance = fine.

Pitfall J4: Hikari connection pool exhaustion under lazy sync + UI requests.
Risk: Background sync thread holds all 10 connections, UI requests block.
Mitigation: Configure spring.datasource.hikari.maximum-pool-size = 10-20 (size for load). Use a separate thread-pool for @Async (not ForkJoinPool.commonPool). Add spring.task.execution.pool.core-size/max-size. UI queries should be fast (< 50ms); sync queries longer.

Pitfall J5: Gmail List-Unsubscribe one-click URL fetch = SSRF vector.
Risk: User controls the header; attacker-controlled unsubscribe URL could hit http://169.254.169.254 (cloud metadata), localhost, or internal network.
Mitigation: Use a Java HTTP client with a custom DNS resolution blocklist. Reject: RFC1918, loopback, link-local, unique-local, any IPv6 ULA, cloud metadata IPs. Validate hostname resolves to public IP BEFORE connecting. Add timeout (5s connect, 10s read). Don't follow redirects to private IPs.

Pitfall J6: Cloudflare Tunnel OAuth redirect URI mismatch.
Risk: User sets GOOGLE_CLIENT redirect to localhost but runs behind tunnel → OAuth callback fails.
Mitigation: In .env, APP_BASE_URL is the canonical source; Spring OAuth redirect-uri uses ${APP_BASE_URL}/login/oauth2/code/google. In the login page, show a warning banner if APP_BASE_URL still starts with http://localhost AND env=prod.

Pitfall J7: @Async sync thread interrupted by JVM shutdown loses progress.
Risk: User Ctrl+C the JAR while 10,000-message ingest is at 60% → partial state.
Mitigation: Ingest is idempotent (INSERT OR REPLACE dedup by messageId). Resumable: store lastHistoryId / pageToken on UserAccount. On restart, pick up from last checkpoint, don't re-ingest from page 1.

Pitfall J8: Thread-safety of Google Gmail API client.
Risk: Gmail service object is NOT thread-safe. Sharing across threads = corrupted batches.
Mitigation: One Gmail service instance per request (build it from encrypted refresh token on-demand) OR use ThreadLocal caching. Never autowire a singleton Gmail. Same with Microsoft Graph client — confirm thread-safety in docs; if unsure, build per-request.

Pitfall J9: Thymeleaf templates rendering raw email content (subject, from) → XSS.
Risk: Attacker sends email with <script> in Subject line, rendered unescaped on dashboard.
Mitigation: Thymeleaf default is escaped (th:text escapes). Never use th:utext for user email content. Double-check List-Unsubscribe URLs are href-sanitized before rendering.

Pitfall J10: Postgres JSON arrays of message IDs → > 1GB rows on large inboxes.
Risk: UserAction with 50,000 messageIds stored as JSON array → TOAST table bloat, slow undo.
Mitigation: For actions > 1000 message IDs, split UserAction into multiple rows linked by actionGroupId (UUID). Undo iterates all rows in group. Page the bulk writes. Don't try to INSERT one row with 50k JSON array.

## UX PITFALLS carried with additional specifics

| Pitfall | User Impact | Mitigation |
|---------|-------------|------------|
| Too many destinations | Identity muddy | Nav = 4 items max for MVP: Dashboard / Senders / Review / Reply Zero. No more. |
| Repeating popups | Annoyed users | Dismiss-once, concise copy. |
| Weak color semantics | Ambiguous meaning | Green=safe/keep, Red=trash/danger, Amber=review/caution. Enums, not ad-hoc strings. |
| "Looks done but isn't" | User disappointment | Sidebar active state, Review=confirm-only, placeholders honest, counts accurate, actions wired through to real MailProvider. |

## "Looks Done But Isn't" Final Checklist (MVP)
- [ ] Sidebar active state consistent across all 4 pages
- [ ] Dashboard Top Senders sorted by count DESC (QA-UX-AUDIT 1.1)
- [ ] Archive N → exactly N archived (add backend assertion)
- [ ] Undo restores N without navigation
- [ ] Delete > 100 → extra confirm step fires
- [ ] Review page is confirmation ledger only, not workbench
- [ ] Placeholder pages honest about status
- [ ] Analytics defaults sensible with non-empty tables
- [ ] Existing working actions reachable through nav
- [ ] No horizontal scroll at 375px
- [ ] Numbers have commas; dates are relative
- [ ] URLs reflect query state (shareable)

## Recovery Strategies
| Pitfall | Recovery Cost | Steps |
| J1 plaintext tokens | HIGH | Add encryption converter, write migration to re-encrypt all existing rows, force users re-auth if key lost |
| J3 double scheduled | LOW-MEDIUM | Add ShedLock dependency or advisory lock in @Scheduled |
| J5 SSRF | HIGH | Refactor unsubscribe client to use IP-blocking HTTP client; add unit tests against metadata IPs |
| J10 huge JSON rows | MEDIUM | Add actionGroupId + chunking logic; backfill old rows |

Ready for phase planning: YES
