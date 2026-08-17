# Phase 2 Decisions & Trade-offs

These are decisions made during Phase 2 execution — both pre-planned (from 02-01-PLAN.md §Decisions Made) and in-sprint corrections surfaced during implementation bugs B6–B8. For project-level architectural decisions, see `docs/DECISIONS.md`.

---

## Planned Decisions (from PLAN.md §Decisions Made)

---

## D2.1 Programmatic ClientRegistrationRepository over Spring Boot property-declared registrations

- **Decision**: Build `ClientRegistration` instances programmatically inside a custom `ClientRegistrationRepository` @Bean, guarded by `if (StringUtils.hasText(clientId) && hasText(clientSecret))` checks — rather than declaring `spring.security.oauth2.client.registration.google.client-id=${GOOGLE_CLIENT_ID:}` style entries in `application.properties`.
- **Trade-off**:
  - **Programmatic approach (chosen)**: 15–25 lines of builder code per provider in `SecurityConfig`. Unconfigured providers are simply not registered; hitting `/oauth2/authorization/google` when Google is unconfigured returns a natural 404 instead of a hard 500.
  - **Property-declared approach (rejected)**: 5 trivial lines in `application.properties`. But Spring Boot's `OAuth2ClientProperties` validator throws a non-recoverable startup failure `Client id of registration 'google' must not be empty` for every registration key present — even with an empty-string default. This was confirmed in Phase 1 D1.3 as a hard blocker.
  - **ConditionalOnProperty / profile split (rejected)**: Idiomatic Spring but adds profile complexity and still needs careful "partial configure" (Google only, Outlook only, both, or neither) branching logic.
- **Why this choice**: Directly mitigates PITFALLS.md §J2 (empty-registration startup crash) and aligns with the login-page UX pattern of rendering provider buttons only when configured. The same `isGoogleConfigured()` / `isMicrosoftConfigured()` boolean gate is reused for both the `ClientRegistrationRepository` bean AND the login page Thymeleaf model attributes — single source of truth, no duplicated checks. Trade-off cost (extra builder lines) is paid once and amortises across future provider additions.

---

## D2.2 OAuthProviderAvailability: @Value-injected @Component over @ConfigurationProperties

- **Decision**: `OAuthProviderAvailability` as a plain `@Component` with 5 raw String fields injected via `@Value("${GOOGLE_CLIENT_ID:}")`, `@Value("${MICROSOFT_TENANT_ID:common}")` etc. — NOT a `@ConfigurationProperties(prefix = "app.oauth")` class.
- **Trade-off**:
  - **@Value component (chosen)**: Directly binds to UPPER_SNAKE_CASE OS-level env vars (`GOOGLE_CLIENT_ID`) AND spring-dotenv `.env` file keys transparently. Empty-string `: ` defaults give the exact "no value → skip registration" semantics.
  - **@ConfigurationProperties (rejected)**: Expects relaxed-binding kebab-case property names like `app.oauth.google-client-id`, which do NOT directly map to raw OS env var names `GOOGLE_CLIENT_ID`. Would require an intermediate bridge layer or `SPRING_APPLICATION_JSON` trickery to shim OS env vars into the property-prefix namespace — non-obvious to the next developer reading the code.
- **Why this choice**: The real contract is "read these 5 exact env var names, empty means not configured". `@Value` spells that contract out literally on each field — grep-able, self-documenting, no hidden binding layers. @ConfigurationProperties would be the right tool if we owned the property namespace (e.g. `app.oauth.google.*` in YAML), but here the env var names are dictated by Google/Microsoft developer console convention and the user's `.env.example`, not by Spring's relaxed-binding rules.

---

## D2.3 Thymeleaf server-rendered with inline <style> blocks over React and over external static CSS

- **Decision**: Login page + dashboard rendered as server-side Thymeleaf templates (per PROJECT.md §DECISIONS §1). All CSS lives inside inline `<style>` blocks in each template file — no external `/static/css/app.css` file yet.
- **Trade-off**:
  - **Inline <style> blocks (chosen)**: Each template is self-contained. No need to wire and test `/static/**` resource-handler permission rules in `SecurityFilterChain` during Phase 2 smoke runs. No extra files to create.
  - **External static CSS (deferred)**: DRYer — no CSS duplication between login.html and dashboard.html. Would require testing that `/static/css/**` is correctly permitAll'd in the filter chain AND that the classpath resource handler resolves during disconnected smoke runs.
  - **React SPA (rejected)**: Explicitly ruled out by DECISIONS.md §1. Would add npm build step, Vite config, CORS concerns, and a second technology stack — premature for Phase 2's "prove login works" tracer slice.
- **Why this choice**: Phase 2's only visual deliverables are two simple pages (login card + dashboard nav + placeholder body) with ~30 lines of shared CSS each. Duplication cost is trivial and obvious to fix later in Phase 6 when layouts get richer (multiple mail-provider cards, bulk-action forms, etc.). Keeping everything inline reduces the number of things that can break during the V-2.3 / V-2.4 smoke-start curl tests — each template's rendering depends solely on Thymeleaf, not external static resource resolution.

---

## D2.4 CSRF enabled globally (Spring Security 6.x default), including logout POST

- **Decision**: Keep CSRF protection enabled for all routes (Spring Security 6 default). MockMvc tests for logout POST use `SecurityMockMvcRequestPostProcessors.csrf()` to add the required token.
- **Trade-off**:
  - **CSRF ON (chosen)**: Default-secure posture — matches PITFALLS.md §J2 warning about not disabling CSRF globally. Any future Phase 6+ API routes (bulk-delete actions, etc.) that sit behind the same session cookie will automatically inherit CSRF protection without retrofitting.
  - **CSRF OFF or session-less stateless (rejected)**: Simpler MockMvc wiring (no csrf() post-processor needed) but opens cookie-authenticated session endpoints to cross-site request forgery. For a mail application with destructive bulk actions, this is unacceptable regardless of Phase 2 scope.
- **Why this choice**: The cost of wiring `csrf()` in MockMvc tests is a single static import and one method call per test. The cost of a CSRF vulnerability on a bulk-delete endpoint is data loss. The asymmetry is enormous. Even though Phase 2 only has a logout POST, keeping the default-on posture means the security config shape never regresses as features are added.

---

## D2.5 Default in-memory JSESSIONID session cookie now; JDBC session store deferred to Phase 3

- **Decision**: Use Spring Session's default in-memory `HashMap`-backed session store for Phase 2. No `spring-session-jdbc` dependency or `@EnableJdbcHttpSession` yet.
- **Trade-off**:
  - **In-memory session (chosen)**: Zero extra dependencies or configuration. Works with the disconnected smoke-test pattern (no Postgres needed).
  - **JDBC-backed session now (rejected)**: Would require Postgres to be reachable during Phase 2 V-2.3/V-2.4 smoke runs — contradicts the "run without Postgres for login tracer" pattern. Also adds schema migration concern (Spring Session tables need Flyway migration scripts) before Phase 3's `UserAccount` entity work.
- **Why this choice**: Phase 2's scope is "prove the OAuth login flow works end-to-end in a tracer test". Session persistence across restarts is not in scope — Phase 3 adds the `UserAccount` JPA entity AND encrypted refresh-token persistence together in one coherent pass. Losing OAuth tokens on restart during Phase 2 is acceptable; it's the exact limitation that Phase 3 exists to fix.

---

## D2.6 Spring's default InMemory OAuth2AuthorizedClientService; JPA-encrypted custom impl deferred to Phase 3

- **Decision**: Let Spring auto-wire its default in-memory `OAuth2AuthorizedClientService` bean. Do NOT register a custom JPA-backed `AuthorizedClientService` in Phase 2.
- **Trade-off**:
  - **Default InMemory service (chosen)**: Zero code. Works out-of-the-box with the `@RegisteredOAuth2AuthorizedClient` parameter injection in `LoginController.dashboard()`.
  - **Custom JPA service now (rejected)**: Requires `UserAccount` + `OAuthToken` entities (Phase 3 work), encrypted column converters, and a custom `RegisteredClientRepository` implementation — all orthogonal to Phase 2's "login button tracer" goal.
- **Why this choice**: Tokens live in memory and are lost on restart, same as D2.5. This is the correct behaviour for Phase 2 — the tracer slice proves "login → dashboard displays identity → logout works", not "tokens survive restart". PITFALLS.md §J1 plaintext-refresh-token concern is explicitly deferred to Phase 3, where the JPA-encrypted custom service implementation is scoped.

---

## In-Sprint Decisions (surfaced by bugs B6–B8 during execution)

---

## D2.7 No-op anonymous ClientRegistrationRepository instead of InMemoryClientRegistrationRepository when provider list is empty

- **Decision**: When both Google and Microsoft are unconfigured (provider list is empty), the `clientRegistrationRepository` @Bean returns an anonymous `ClientRegistrationRepository` implementation whose `findByRegistrationId(String id)` always returns `null` — rather than `new InMemoryClientRegistrationRepository(Collections.emptyList())`.
- **Trade-off**:
  - **No-op anonymous impl (chosen)**: Zero dependency on `InMemoryClientRegistrationRepository` constructor being callable with an empty list. More importantly, Spring Security's OAuth2ClientConfigurer performs internal checks on the repository type; an empty InMemory-backed one can still trigger filter-registration side effects (attempting to resolve redirect URIs, loading lazy registrations) that surface subtle startup WARN logs or test flakiness. The pure no-op impl is inert by construction.
  - **Empty InMemoryClientRegistrationRepository (rejected)**: Works functionally (empty list means no registrations → 404 on authorization endpoints) but carries unnecessary implementation weight. Future Spring Security versions may change `InMemoryClientRegistrationRepository` behaviour with an empty list.
- **Why this choice**: The "no providers configured" case is a legitimate first-run user experience (V-2.3 smoke test verifies it). Returning a truly inert repository implementation makes this case as simple and predictable as possible. No lazy loading, no map initialization, no stream() on an empty collection — just a single method returning null. It's the minimal correct shape for "nothing is configured" and eliminates an entire category of edge-case bugs around empty-collection handling in Spring's OAuth configurer.

---

## D2.8 Single authorizeHttpRequests block; OAuth2 paths always permitAll; conditional .oauth2Login() block guarded by availability flag

- **Decision**: Refactor `SecurityFilterChain` to:
  1. Use exactly ONE `authorizeHttpRequests(auth -> auth...)` lambda block with all matchers listed inside it in correct specificity order (most specific first).
  2. Always include `.requestMatchers("/oauth2/authorization/**", "/login/oauth2/code/**").permitAll()` in that single block, regardless of whether any providers are currently configured.
  3. Guard the entire `.oauth2Login(oauth2 -> oauth2.loginPage("/login").defaultSuccessUrl("/dashboard", true))` customizer behind a runtime availability check (only attach the oauth2Login DSL if at least one provider is configured).
- **Trade-off**:
  - **Single block + always-permit OAuth paths + conditional oauth2Login (chosen)**: A single `authorizeHttpRequests` block avoids the pitfall of multiple `.authorizeHttpRequests()` calls where later blocks override earlier ones (a common Spring Security DSL footgun). Always permitting the OAuth2 paths means they're reachable even in a "both providers configured at runtime after first deployment" scenario without restarting the filter-chain evaluation order. Guarding `.oauth2Login()` by availability flag means Spring Security skips registering the `OAuth2LoginAuthenticationFilter` entirely when no providers exist — preventing the filter chain from trying to resolve a `ClientRegistrationRepository` that would be empty/no-op and potentially throw at filter-init time.
  - **Multiple authorizeHttpRequests blocks (rejected)**: Each `.authorizeHttpRequests()` call on the HttpSecurity builder replaces the previous one, so having two calls would silently drop the first block's rules. This was the root cause of B7 (dashboard access leaking unauthenticated or login page 403-ing intermittently).
  - **Always configuring .oauth2Login() regardless of availability (rejected)**: When the repo is a no-op (D2.7), the OAuth2LoginConfigurer can still try to eagerly resolve registrations during filter init — leading to NPE or "no registration found" WARN logs at startup, or MockMvc tests seeing unexpected 500 instead of 404 on `/oauth2/authorization/google`.
- **Why this choice**: B6–B7 surfaced three concrete bugs from the initial naive split-block approach: (i) duplicate authorizeHttpRequests overriding rules, (ii) oauth2Login filter complaining when repo was empty, (iii) test flakiness between "no providers" and "one provider" scenarios. Consolidating to a single matchers block with clear specificity order (static assets → actuator → login → OAuth endpoints → authenticated catch-all) eliminates class (i). Always permitAll-ing OAuth path matchers regardless of config eliminates class (ii) confusion about whether those paths need re-auth. Conditionally attaching the oauth2Login DSL only when at least one provider exists eliminates class (iii) by making the filter-chain shape match the actual configured capability.

---

## D2.9 Class-level constructor injection of OAuthProviderAvailability in LoginController vs MVC handler-method parameter injection

- **Decision**: `LoginController` receives `OAuthProviderAvailability` via constructor injection (stored as a `private final` field) and reuses the same instance across both `GET /login` and `GET /dashboard` handler methods — rather than declaring `OAuthProviderAvailability props` as a parameter on each `@GetMapping` method and relying on Spring MVC argument resolution.
- **Trade-off**:
  - **Constructor injection, final field (chosen)**: Standard Spring best practice. The dependency is visible in the class's public API (constructor signature = wiring contract), the field is `final` (immutable after construction, no null-assignment bugs), and the same cached instance is reused for both handlers without re-resolving. Easy to unit-test the controller by `new LoginController(mockAvailability, ...)` without needing a mockMvc context or custom argument resolvers.
  - **Handler-method parameter injection (rejected)**: Would work functionally — Spring MVC CAN resolve `@Component` beans as handler arguments in a `@Controller`. But it's unconventional, makes the dependency invisible in the class signature (a reader scanning the constructor wouldn't know the controller depends on OAuth availability), and forces each handler method to repeat the parameter. More importantly, it makes pure unit testing of the controller harder — you can't just instantiate the class and call `login(model)`; you need the Spring MVC argument-resolver machinery.
- **Why this choice**: Bug B8 was a `NullPointerException` in `LoginController.dashboard()` when the handler-param injection silently returned null in a specific MockMvc + @WithMockUser test scenario where the argument resolver wasn't active for that particular test configuration style. Constructor injection eliminates the entire class of "param resolver decided not to inject" bugs — if the bean exists in the context, the controller is constructed with it; if not, context startup fails fast. This is Spring idiom 101 and the deviation (handler-param injection for a non-request-scoped bean) was an unnecessary novelty that didn't pay its complexity cost.

---

## Phase 2 Knowledge Carry-Forward (actionable for Phase 3)

1. **Single authorizeHttpRequests block rule.** Never call `.authorizeHttpRequests()` twice on the same `HttpSecurity` builder in the same filter chain — later calls silently overwrite earlier ones. Consolidate all matchers in one lambda.
2. **OAuth2Login DSL attach only when at least one provider exists.** Pattern: `if (props.isGoogleConfigured() || props.isMicrosoftConfigured()) { http.oauth2Login(...); }` — keep this guard when Phase 4/5 add more providers.
3. **No-op ClientRegistrationRepository for the empty case.** The anonymous-impl pattern (findByRegistrationId → null) is preferable to an empty `InMemoryClientRegistrationRepository` to avoid Spring OAuth configurer side effects on empty collections.
4. **OAuth2User email attribute fallback chain is mandatory.** Google returns `email` consistently; Microsoft id-tokens return `preferred_username` or `email` depending on tenant config. Phase 3 `UserAccount` entity save-on-first-login logic MUST reuse the 4-step fallback: `email → preferred_username → unique_name → "unknown"`.
5. **In-Memory session + AuthorizedClientService are Phase-2-only.** Phase 3 delivers: `spring-session-jdbc` + JDBC session tables migration, custom JPA-backed `OAuth2AuthorizedClientService` with column-encrypted refresh tokens per PITFALLS §J1.
6. **Inline <style> blocks phase out at Phase 6.** When bulk-action forms and multi-column layouts arrive, extract to `static/css/` — the ~30 line duplication between login.html / dashboard.html is not worth a refactor before then.
