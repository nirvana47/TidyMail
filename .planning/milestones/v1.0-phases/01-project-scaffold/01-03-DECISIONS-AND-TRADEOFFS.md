# Phase 1 Decisions & Trade-offs

These are decisions made **during** Phase 1 execution — in-the-trenches judgments with multiple reasonable options. For pre-planned project-level decisions, see `docs/DECISIONS.md`.

---

## D1.1 Package name: `com.tidymail` vs `com.example.inboxcleaner`

- **Question / fork point**: Package root namespace.
- **Options considered**:
  - **A) `com.tidymail`** — matches GitHub folder name, shorter, sounds product-y.
    - Pros: Terse, memorable, matches repo identity.
    - Cons: Textual drift from DECISIONS.md §5 ASCII tree.
  - **B) `com.example.inboxcleaner`** — matches DECISIONS.md §5 module layout ASCII tree which explicitly used package name `com.example.inboxcleaner`. More "tutorial-style", less like a real product.
    - Pros: Literal match to planning docs.
    - Cons: `com.example` signals "demo/placeholder", not a real product.
  - **C) `io.tidymail.{something}`** — reserved for if we eventually register a domain.
    - Pros: Canonical reverse-DNS if domain exists.
    - Cons: Premature; no domain registered yet.
- **Decision**: A) `com.tidymail`.
- **Why / Trade-offs**: Shorter, matches the folder name the user sees daily. DECISIONS.md §5 was an illustrative example using `com.example` as a convention placeholder, not a hard requirement. We'll keep the module layout class names from §5 (SecurityConfig, GmailMailProvider, etc.) — only the root package prefix changes. Trade-off: ~100 lines of textual drift from DECISIONS.md §5 ASCII tree package names. Low cost (search/replace in docs later).
- **Reversal trigger**: If we register tidymail.io or tidymail.com and want `io.tidymail` as the canonical package, update pom.xml groupId + move source to new package structure (one-time refactor, typically easy in IntelliJ).

---

## D1.2 Gmail / Microsoft SDK version source-of-truth: pinned GA from Maven Central solrsearch vs guesses from WebSearch cache.

- **Question / fork point**: Initial POM draft used version numbers from a timed-out WebSearch cache, which compiled into the pom.xml template but did not exist on Maven Central.
- **Options considered**:
  - **A) Write versions by heuristic/guess and let user fix on first build.**
    - Pros: Zero network calls during Phase 1.
    - Cons: User experience = build breaks immediately; bad first impression. 3+ iteration loop of guess → fail → lookup. ❌ Rejected.
  - **B) Call `curl search.maven.org/solrsearch/select?...wt=json&core=gav` directly via subprocess inside the build agent, parse with python3 for the top 5 results, pick the latest STABLE (non-alpha/beta/rc) version.**
    - Pros: Produces exact, resolvable versions. Deterministic builds first try.
    - Cons: Network dependency during scaffold phase. ~2 seconds for 6 HTTP calls.
  - **C) Depend on Spring Boot managed BOMs for Google/Microsoft libs.**
    - Pros: Handled by Spring Boot dependency management.
    - Cons: Spring Boot doesn't manage third-party Gmail/Graph SDK versions. Doesn't work. ❌ Rejected.
- **Decision**: B. Direct Maven Central solrsearch JSON API with 5-row history + manual stability inspection.
- **Results used**:
  - google-api-services-gmail → v1-rev20260525-2.0.0 (latest rev, matched version scheme v1-revYYYYMMDD-2.0.0)
  - google-oauth-client → 1.39.0 (skipping the google-oauth-client-jetty parent 1.35/1.36 etc.; matched 1.39.0 latest stable GA)
  - google-http-client → 1.47.0 (latest)
  - microsoft-graph → 6.42.0 (6.x active line GA)
  - azure-identity → 1.16.2 (latest stable, not the -beta.1 line)
  - flyway-core → 11.8.2 (latest 11.x)
- **Why / Trade-offs**: Build determinism = better user experience. 6 HTTP curl calls cost ~2 seconds total and produced 6 exact, resolvable versions. Avoids the "fabricate version, fail at mvn package, iterate 3 times" loop. Trade-off: Added a hard build-system dependency on network + search.maven.org availability during Phase 1. In future phase updates, re-run the same curl block to bump.
- **Reversal trigger**: If we move to a fully offline / air-gapped build machine, pin versions to those in `~/.m2/repository` cache and remove solrsearch step from workflow.

---

## D1.3 OAuth2 client properties: declared in application.properties (with empty defaults) vs conditionally wired via programmatic ClientRegistration in Phase 2 SecurityConfig.

- **Question / fork point**: How to handle GOOGLE_CLIENT_ID / MICROSOFT_CLIENT_ID being empty strings during Phase 1 scaffold / dev startup before user fills .env?
- **Options considered**:
  - **A) Put `spring.security.oauth2.client.registration.google.client-id=${GOOGLE_CLIENT_ID:}` in application.properties with empty default.**
    - Pros: 5 trivial property lines, minimal code.
    - Cons: Hard startup failure: `Client id of registration 'microsoft' must not be empty` because Spring Boot's OAuth2ClientProperties validates EVERY registration whose key exists, regardless of empty-string value. ❌ Rejected (tried; confirmed failure).
  - **B) Don't declare registrations in application.properties at all. Add a comment explaining the situation, and have Phase 2 SecurityConfig programmatically build ClientRegistration only when env vars are non-empty strings, building a custom InMemoryClientRegistrationRepository bean.**
    - Pros: Scaffold starts cleanly without .env filled in. Directly aligns with login page UX pattern (show provider button only if configured).
    - Cons: ~15-20 extra lines in Phase 2 SecurityConfig for programmatic registration.
  - **C) Use Spring Boot 3.5.x ConditionalOnProperty or OAuth2 auto-config exclude flags + re-enable per profile.**
    - Pros: Property-driven, idiomatic Spring.
    - Cons: Adds profile complexity; still needs careful logic for "partial configure" case.
- **Decision**: B. No empty-default OAuth2 properties in application.properties. Programmatic registration in Phase 2.
- **Why / Trade-offs**: Direct PITFALLS.md §J2 mitigation ("Spring Security endpoint leaks via auto-configuration + empty registration validation"). Prevents the scaffold from being broken-by-default for the user before they fill in .env. Trade-off: Phase 2 SecurityConfig has slightly more code (3-4 `if (StringUtils.hasText(clientId)) { ClientRegistration.withRegistrationId(...) }` blocks) vs 5 trivial properties lines. But this is the exact pattern needed for the "login button rendered only if provider configured" UX in the login page template, so code-reuse pays back quickly.
- **Reversal trigger**: If in Phase 2 we decide to NOT support "partial configure (one of Gmail/Outlook only or both or none)", switch back to property-based registration + require env vars always filled before startup, with clear startup error message guiding user to .env.example.

---

## D1.4 Hibernate dialect: explicit `spring.jpa.properties.hibernate.dialect=PostgreSQLDialect` in application.properties (per DECISIONS §5 hint to set it), vs auto-detection.

- **Question / fork point**: Hibernate 6 + Spring Boot 3.5 auto-detects dialect from the JDBC connection metadata, and warns if you set it explicitly ("HHH90000025: PostgreSQLDialect does not need to be specified explicitly"). But if there's NO JDBC connection (e.g., Postgres isn't running during a scaffold smoke test), dialect auto-detection fails catastrophically.
- **Options considered**:
  - **A) Always set dialect explicitly in application.properties.**
    - Pros: Clear signal of intended database even without JDBC probe.
    - Cons: Causes warning on every normal startup, which is noise and might mask real warnings. Hibernate team explicitly recommends against it in 6.x. ❌ Rejected.
  - **B) Never set dialect, rely on probe.**
    - Pros: Matches Hibernate 6 best practices. No spurious warnings.
    - Cons: Dialect auto-detection fails when JDBC URL can't connect (scaffold smoke-test edge case).
  - **C) CLI override for the one-off scaffold smoke test that runs without Postgres.**
    - Pros: Keeps canonical config clean; edge case handled externally.
    - Cons: One more flag to remember for disconnected runs.
- **Decision**: B + C combined. Dialect auto-detect in application.properties (matches Hibernate 6 best practices), CLI override ONLY during disconnected smoke tests.
- **Why / Trade-offs**: Matches the Hibernate team's intent. The warning was real; the auto-detection failure is a genuine edge case that only happens when the JDBC URL doesn't connect. By combining with Hikari tweaks (see D1.5), the disconnected smoke test scenario becomes fully supportable via CLI flags only. Trade-off: User reading application.properties won't immediately know "this app targets Postgres 16 specifically" from seeing the dialect declaration. But that info is in .env.example section 2, README, PROJECT.md, so redundancy isn't required here.
- **Reversal trigger**: If we add H2 as a secondary test datasource profile, dialect ambiguity could arise; re-enable explicit dialect then.

---

## D1.5 HikariCP Phase 1 smoke-test configuration: eager connections (default min-idle=5) vs lazy (min-idle=0 + init-fail-timeout=-1)

- **Question / fork point**: Smoke testing V-1.4 before Postgres is installed. Default Hikari behavior tries to create 5 connections on startup, which fails with connection refused and blocks Hibernate dialect probing (see D1.4).
- **Options considered**:
  - **A) Install Postgres (Docker or brew) before running Phase 1 smoke test.**
    - Pros: Real runtime parity.
    - Cons: Adds scope to Phase 1 which was supposed to be "pom.xml only". ❌ Out of scope.
  - **B) Configure Hikari in application.properties to be lazy.**
    - Pros: Works without Postgres always.
    - Cons: Carries forward into Phase 3 where real Postgres IS running, and causes first-request latency spikes while pool warms from 0. Changes production semantics. ❌ Rejected.
  - **C) CLI-only overrides for the smoke-test invocation: `--spring.datasource.hikari.minimum-idle=0` AND `--spring.datasource.hikari.initialization-fail-timeout=-1`.**
    - Pros: Clean separation — canonical config stays production-correct; edge case handled in one-off CLI invocation.
    - Cons: One more thing to document/remember for disconnected scaffold runs.
- **Decision**: C. CLI-only during disconnected smoke tests. Production/Phase 3 Hikari uses the application.properties values (min-idle=5, default init-fail-timeout=1 which means "fail fast on startup if DB unreachable" — correct behavior for production; if Postgres is down we WANT startup to fail, not silently run with a dead pool).
- **Why / Trade-offs**: Clean separation. Phase 3 onwards Postgres is always running; we get fast-fail correctness. Phase 1 disconnected testing uses isolated CLI overrides that don't pollute the canonical config. Trade-off: One more thing to remember when running the scaffold locally without Postgres. Documented in V-1.4 so it's not tribal knowledge.
- **Reversal trigger**: If we introduce a "smoke-test" Spring profile that bundles these three overrides + explicit dialect for dev tooling.

---

## D1.6 H2 in-memory database: scope=test ONLY (Phase 1 decision).

- **Question / fork point**: Should H2 be on the runtime classpath (so that users without Postgres could fall back to it)?
- **Options considered**:
  - **A) Runtime scope H2 + Spring profile "h2-memory".**
    - Pros: Users without Postgres can try the app immediately.
    - Cons: i) PROJECT.md non-goals explicitly disallow H2 as a production store (retention cascade delete semantics with H2's MVStore not tested; 50K messages per account triggers H2 file size growth). ii) Adding runtime H2 increases classpath surface and dialect confusion. ❌ Rejected.
  - **B) Test-only scope H2.**
    - Pros: Aligns with Project.md Postgres mandate. Standard Spring Boot convention for fast @DataJpaTest runs without Docker.
    - Cons: Potential H2-vs-Postgres SQL dialect differences in repository tests (mitigated with layered test strategy).
- **Decision**: B. Test only. pom.xml F6 section: `<scope>test</scope>`. JPA repositories in future phases will have @DataJpaTest integration tests using H2 (per Spring Boot testing patterns). Real application runtime always uses Postgres.
- **Why / Trade-offs**: Aligns with PROJECT.md "PostgreSQL 16 — serverless options (Neon / Supabase / Tembo free tier) or local Docker Postgres" database decision. H2 on test classpath is a standard Spring Boot convention for fast repository tests without requiring Docker/Postgres during TDD. Trade-off: There can be subtle SQL dialect differences between H2 (PostgreSQL compatibility mode) and real Postgres 16. E.g., Postgres `jsonb` operators, `GENERATED ALWAYS AS`, CTE materialization hints. Mitigation: Layer 2 tests — JPA-only tests with H2 for repository-level assertions, plus one @SpringBootTest against a Testcontainers Postgres in Phase 3+ for true parity. (Testcontainers dependency NOT yet added in Phase 1; added in Phase 3 if needed.)
- **Reversal trigger**: If we ever want to support a "quick try experience" with a file-backed in-process H2 database for users who refuse to run Postgres, that contradicts the stated goals and should require a new non-goals + risk review. Do NOT unilaterally change H2 scope without revisiting PROJECT.md §3 Non-Goals + Trust/Privacy model.

---

## D1.7 "commons-logging discovery in action with spring-jcl" WARN: resolve now or defer.

- **Question / fork point**: Log line "Standard Commons Logging discovery in action with spring-jcl: please remove commons-logging.jar from classpath in order to avoid potential conflicts" appearing twice in startup log (once before banner, once after context init).
- **Options considered**:
  - **A) Add `<exclusions>` block to every google-oauth-client / azure-identity / microsoft-graph dependency that drags in commons-logging.**
    - Pros: Clean startup log.
    - Cons: Immediate fix but tedious; requires tracing each transitive dependency via `mvn dependency:tree`. ~30 minutes of work for cosmetic benefit.
  - **B) Defer to Phase 2+.**
    - Pros: Phase 1 priority = working build, not pristine logs. Zero cost now.
    - Cons: Two WARN lines on every startup = slightly ugly logs. Possible minor distraction during Phase 2 OAuth debugging.
- **Decision**: B. Defer.
- **Why / Trade-offs**: This is a cosmetic WARN (not an error). Spring ships spring-jcl which routes commons-logging API calls to Logback/SLF4J correctly; the message just says "we saw the commons-logging artifact on the classpath and used the fallback discovery mechanism instead of the static SLF4J bridge". No functional impact. Cleaning up classpath noise is a good polishing task but Phase 1 priority is a working BUILD. Trade-off: Two WARN lines on every startup = slightly ugly logs. For users reading the startup log during Phase 2 OAuth debugging, it might be a minor distraction.
- **Reversal trigger**: If the startup log gets noisy enough to obscure real issues during the Phase 2 OAuth2 flow, spend 30 minutes adding mvn dependency:tree analysis + exclusions. Typically exclusion pattern is to add `commons-logging:commons-logging` exclusion to offending deps and route to `org.springframework:spring-jcl` instead.

---

## Phase 1 Knowledge Carry-Forward (actionable for Phase 2)

1. **Always build with JAVA_HOME exported explicitly.** macOS `/usr/bin/java` is a stub. The export command pair from 01-02-UAT.md Environment section should be copy-pasted into every new terminal session, or added to the user's `~/.zshrc` if they want it globally (this is a user preference — don't edit their shell dotfiles without asking).
2. **Empty OAuth2 registration properties = Spring Boot hard startup failure.** Lock it in: Phase 2 SecurityConfig MUST build registrations programmatically with `if (hasText(clientId))` guards. Don't backslide into property declarations.
3. **Maven Central solrsearch pattern is the fastest version pinning.** The curl+python3 one-liner from B1 is a snippet we can reuse to bump Gmail / Graph SDK versions quarterly.
4. **Hikari min-idle=0 + init-fail-timeout=-1 is the "run without Postgres" escape hatch.** Documented but not promoted — production should always have Postgres reachable.
5. **Google HTTP client version numbers are NOT in lockstep.** google-oauth-client (1.39.0) and google-http-client (1.47.0) use separate version streams. Don't try to "simplify" by forcing them onto a shared version property — Maven Central controls release cadence.
6. **Spring Boot 3.5.16 parent POM works correctly with all 23 dependencies.** No Jackson version collisions, no JAXB split-package issues on Java 21, no module-path conflicts with surefire `useModulePath=false`. This version matrix is validated.
