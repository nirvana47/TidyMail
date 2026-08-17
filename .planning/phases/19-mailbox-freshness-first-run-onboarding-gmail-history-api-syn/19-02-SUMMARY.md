---
phase: 19-mailbox-freshness-first-run-onboarding-gmail-history-api-syn
plan: 02
subsystem: oauth-managed-refresh
tags: [spring-security, oauth2, gmail, postgresql, testcontainers, concurrency]
requires:
  - phase: 19-mailbox-freshness-first-run-onboarding-gmail-history-api-syn
    provides: Durable mailbox state, OAuth lifecycle columns, and revision-aware metadata persistence from Plan 19-01
provides:
  - Nondestructive account-scoped OAuth invalidation and later reactivation
  - Managed Google access-token refresh with persisted same-account single flight
  - Complete Phase 19 OAuth and sync configuration defaults
affects: [phase-19-history-api, first-run-backfill, freshness-workers, oauth-recovery]
actuals:
  tokens: 11829
  tasks: 2
  commits: 2
tech-stack:
  added: []
  patterns:
    - Spring Security AuthorizedClientServiceOAuth2AuthorizedClientManager owns refresh and persistence
    - Short-lived per-provider-account CompletableFuture shares concurrent refresh results
key-files:
  created:
    - src/main/java/com/tidymail/security/oauth2/ManagedGoogleCredentialService.java
    - src/test/java/com/tidymail/OAuthAuthorizedClientRefreshTest.java
  modified:
    - src/main/java/com/tidymail/security/oauth2/JpaOAuth2AuthorizedClientService.java
    - src/main/java/com/tidymail/repository/UserAccountRepository.java
    - src/main/java/com/tidymail/security/SecurityConfig.java
    - src/main/java/com/tidymail/mail/gmail/GmailClientFactory.java
    - src/main/resources/application.properties
    - .env.example
    - src/test/java/com/tidymail/Phase4GmailProviderTest.java
key-decisions:
  - "Use provider plus providerAccountId as the managed authorization and single-flight identity; email never participates in background OAuth lookup."
  - "Treat invalid_grant and invalid_token as terminal REAUTH_REQUIRED outcomes while exposing all other provider failures only as RETRYABLE."
  - "Reload the authorized client from PostgreSQL after manager success so Gmail callers cannot proceed before refreshed credentials are persisted."
patterns-established:
  - "Nondestructive OAuth failure: invalidate credentials without deleting account, sync state, metadata, actions, settings, or activity."
  - "Managed Gmail credentials: GmailClientFactory receives only the persisted access token and performs no token endpoint work."
requirements-completed: [FRESH-05]
coverage:
  - id: D1
    description: "OAuth removal and terminal refresh failure retain the account and indexed mailbox rows while moving only the matching provider account to REAUTH_REQUIRED."
    requirement: FRESH-05
    verification:
      - kind: integration
        ref: "OAuthAuthorizedClientRefreshTest and Phase3PersistenceTest under Java 21/PostgreSQL 16/Testcontainers"
        status: pass
    human_judgment: false
  - id: D2
    description: "Managed refresh honors the expiry skew, persists omitted-refresh-token responses, and shares one token request across parallel Gmail client builds."
    requirement: FRESH-05
    verification:
      - kind: integration
        ref: "OAuthAuthorizedClientRefreshTest#userRefreshesOnlyAtOrInsideTheConfiguredExpirySkew and #parallelMailboxLanesBuildClientsFromOnePersistedRefresh"
        status: pass
    human_judgment: false
  - id: D3
    description: "GmailClientFactory no longer decrypts refresh tokens or performs per-client network refresh, and all six Phase 19 OAuth/sync configuration pairs are defined."
    requirement: FRESH-05
    verification:
      - kind: other
        ref: "forbidden-pattern and six-key configuration acceptance scans from 19-02 verification"
        status: pass
    human_judgment: false
duration: 6 min
completed: 2026-08-14
status: complete
---

# Phase 19 Plan 02: Managed OAuth Refresh Summary

**Spring Security now refreshes and persists Google credentials once per provider account while revocation preserves every indexed mailbox row**

## Performance

- **Duration:** 6 min
- **Started:** 2026-08-14T18:11:50Z
- **Completed:** 2026-08-14T18:18:14Z
- **Tasks:** 2
- **Files modified:** 9

## Accomplishments

- Replaced destructive authorized-client removal with account-scoped `REAUTH_REQUIRED` invalidation while preserving encrypted credentials, mailbox state, metadata, actions, settings, and activity.
- Added managed Google refresh with validated expiry skew, persisted success/failure handling, stable safe error classifications, and same-account single flight.
- Refactored Gmail client construction to consume only the persisted managed access token and documented the complete Phase 19 OAuth/sync configuration contract.

## Task Commits

1. **Task 1: Make authorized-client removal and null timestamps nondestructive** - `cd599b7`
2. **Task 2: Wire managed refresh, persistence, and same-account single flight** - `3cb6cac`

## Files Created/Modified

- `ManagedGoogleCredentialService.java` - Provider-account authorization, persistence barrier, safe classifications, and bounded single flight.
- `JpaOAuth2AuthorizedClientService.java` / `UserAccountRepository.java` - Nullable token times plus nondestructive invalidation/reactivation.
- `SecurityConfig.java` - Refresh provider, manager, success persistence, and terminal failure handler.
- `GmailClientFactory.java` - Managed persisted access-token adapter with existing transport and timeout behavior.
- `application.properties` / `.env.example` - Six Phase 19 OAuth/sync key pairs and corrected progressive-ingestion defaults.
- `OAuthAuthorizedClientRefreshTest.java` / `Phase4GmailProviderTest.java` - PostgreSQL, local token endpoint, concurrency, failure, and provider factory evidence.

## Decisions Made

- The provider account ID is the sole managed principal and lock identity, preventing email or cross-account substitution.
- Successful authorization is reloaded from PostgreSQL before Gmail construction so dependent work observes persisted credentials.
- Provider response descriptions are discarded; callers receive only `REAUTH_REQUIRED` or `RETRYABLE`, and persisted error codes are application-controlled.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

A success-handler adapter initially omitted Spring Security's attributes argument; the compile-time mismatch was corrected before verification. No open issues remain.

## User Setup Required

None - the new variables have safe defaults and no external service setup is required for this plan.

## Next Phase Readiness

Managed credentials are ready for Plan 19-03 Gmail History API freshness work. No Plan 19-03 or later orchestration was implemented here.

## Self-Check: PASSED

Both task commits and the created artifacts exist, all 18 focused Java 21/PostgreSQL tests pass, every acceptance criterion passed, and `.planning/STATE.md` plus `.planning/ROADMAP.md` remain unchanged.

---
*Phase: 19-mailbox-freshness-first-run-onboarding-gmail-history-api-syn*
*Completed: 2026-08-14*
