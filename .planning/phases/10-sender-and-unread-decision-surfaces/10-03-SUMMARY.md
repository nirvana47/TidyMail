---
phase: 10-sender-and-unread-decision-surfaces
plan: "03"
subsystem: sender-unread-web-surfaces
tags: [spring-mvc, thymeleaf, accessibility, provider-links, postgres, h2]
requires:
  - phase: 10-01
    provides: canonical classified sender/unread repository queries
  - phase: 10-02
    provides: exact protected preview materialization
provides:
  - canonical full-page and result-fragment sender/unread GET surfaces
  - strict local Gmail deep links with no provider interaction
  - accessible metadata-only filters, metrics, tables, pagination, reasons, and previews
  - MVC privacy, provider-isolation, responsive, and 10,000-row performance evidence
affects: [phase-12, phase-13]
tech-stack:
  added: []
  patterns:
    - raw provider identity converted to a validated final URI before the view model
    - one canonical query record drives full pages, fragments, links, and preview forms
key-files:
  created:
    - src/main/java/com/tidymail/dto/SenderPageModel.java
    - src/main/java/com/tidymail/dto/UnreadPageModel.java
    - src/main/java/com/tidymail/service/ProviderDeepLinkService.java
    - src/main/resources/templates/fragments/senders-results-frag.html
    - src/main/resources/templates/fragments/unread-results-frag.html
    - src/main/resources/templates/fragments/selection-strip-frag.html
    - src/test/java/com/tidymail/Phase10SenderUnreadWebTest.java
  modified:
    - src/main/java/com/tidymail/controller/WorkspaceController.java
    - src/main/java/com/tidymail/controller/SelectionPreviewController.java
    - src/main/java/com/tidymail/repository/DecisionSurfaceQueryRepository.java
    - src/main/resources/templates/senders.html
    - src/main/resources/templates/unread.html
    - src/main/resources/static/css/workspace.css
key-decisions:
  - "Provider message/thread IDs stay inside the repository/service boundary; templates receive only a validated final Gmail URI."
  - "Result fragments use the same controller builders and canonical typed queries as full pages."
  - "Selection modes use separate forms so individual row keys cannot contaminate visible-page or all-matching requests."
requirements-completed:
  - WORK-02
  - SEND-01
  - SEND-02
  - SEND-03
  - SEND-04
  - SEND-05
  - SEND-07
  - UNRD-01
  - UNRD-02
  - UNRD-03
  - UNRD-04
  - UNRD-05
  - UNRD-06
  - QUAL-03
coverage:
  - id: D1
    description: "Canonical full and fragment GETs fail closed and render real sender/unread decision data."
    requirement: WORK-02
    verification:
      - kind: integration
        ref: "Phase10SenderUnreadWebTest#work02CanonicalFullAndFragmentGetsPreserveEveryRecognizedValue"
        status: pass
      - kind: integration
        ref: "Phase10SenderUnreadWebTest#work02InvalidDuplicateUnknownAndForeignValuesFailClosed"
        status: pass
    human_judgment: false
  - id: D2
    description: "Sender and unread filters, metrics, facts, reasons, and exact preview forms are complete and metadata-only."
    requirement: SEND-04
    verification:
      - kind: integration
        ref: "Phase10SenderUnreadWebTest#send01ThroughSend04RenderFiltersMetricsFactsReasonsAndPagination"
        status: pass
      - kind: integration
        ref: "Phase10SenderUnreadWebTest#unrd01ThroughUnrd04RenderMetadataOnlyFactsMetricsReasonsAndSafeGmailLink"
        status: pass
      - kind: integration
        ref: "Phase10SelectionMaterializationTest"
        status: pass
    human_judgment: false
  - id: D3
    description: "Strict HTTPS Gmail links prefer thread identity, encode exact segments, and require no provider call."
    requirement: UNRD-06
    verification:
      - kind: integration
        ref: "Phase10SenderUnreadWebTest#unrd06ProviderLinksPreferThreadEncodeExactSegmentsAndFailClosed"
        status: pass
    human_judgment: false
  - id: D4
    description: "Responsive/accessibility declarations and warm 10,000-row full/fragment budgets are enforced."
    requirement: QUAL-03
    verification:
      - kind: integration
        ref: "Phase10SenderUnreadWebTest#qual03TemplatesAndCssLockSemanticResponsivePrivacyContracts"
        status: pass
      - kind: integration
        ref: "Phase10SenderUnreadWebTest#d14WarmTenThousandRowFullAndFragmentRequestsStayUnderTwoSeconds"
        status: pass
      - kind: integration
        ref: "Phase10PostgresMigrationTest"
        status: pass
    human_judgment: true
    rationale: "Static CSS and MockMvc prove structure and declarations, but actual 375px layout, keyboard focus flow, and 200% zoom require browser judgment."
duration: 18 min
completed: 2026-08-10
status: complete
---

# Phase 10 Plan 03: Sender And Unread Web Surfaces Summary

**Real canonical sender/unread decision pages with strict Gmail links, exact preview forms, accessible local styling, and provider-free MVC coverage**

## Performance

- **Duration:** 18 min
- **Started:** 2026-08-10T00:18:00Z
- **Completed:** 2026-08-10T00:35:45Z
- **Tasks:** 6
- **Files created/modified:** 20

## Accomplishments

- Replaced both Phase 9 placeholders with complete server-rendered filters, metrics, semantic result tables, stable reasons, pagination, and zero-result states.
- Extended the sole root-route `WorkspaceController` with shared canonical full-page/result-fragment paths and bounded out-of-range-page rejection.
- Added exact encoded Gmail thread/message links without provider calls or raw identity fields in the view model.
- Integrated individual, this-page, all-matching, cap, protected exclusion, and audited override previews without queue/apply controls.
- Added local responsive/accessibility CSS and exhaustive rendered privacy, isolation, PostgreSQL parity, and warm 10,000-row request tests.

## Task Commits

None. The user explicitly requested no commit.

## Verification

- Focused Phase 9/10 H2 plus PostgreSQL 16.14 matrix: **65 tests, 0 failures, 0 errors, 0 skipped**.
- Full Maven suite: **130 tests, 0 failures, 0 errors, 0 skipped**.
- PostgreSQL production V1-V7 migration, Hibernate validation, query/materialization parity: passed.
- Warm 10,000-row Senders/Unread full-page and result-fragment requests: each below two seconds.
- Full/fragment/preview provider mocks: zero interactions.
- `git diff --check`: passed.

All Maven commands used `JAVA_HOME=$(/usr/libexec/java_home -v 21)`. The host selector
resolves to installed Java 26.0.1.

## Deviations from Plan

### Auto-fixed Issues

1. **[Rule 1 - Bug] Empty pages rendered selection controls**
   - Thymeleaf processed `th:replace` before the host `th:if`.
   - Moved the condition to an outer `th:block`; zero-result pages now omit preview controls.

2. **[Rule 2 - Missing Critical] Preview POST accepted unknown non-query fields**
   - Added a strict surface-specific form-parameter allowlist before materialization.

3. **[Rule 1 - Bug] Positive pages beyond the filtered range returned empty results**
   - Added bounded HTTP 400 rejection after the canonical count query.

**Total deviations:** 3 auto-fixed (2 bugs, 1 missing fail-closed validation).
**Impact:** All fixes enforce the approved URL/selection contract without expanding scope.

## Residual Manual UAT Risks

- Actual 375px page overflow and long-value wrapping need browser measurement.
- Keyboard-only focus after optional htmx replacement and focus return need browser testing.
- 200% zoom, screen-reader announcement quality, and sticky-strip obstruction need human judgment.
- Gmail deep links are structurally exact but require a signed-in browser account to validate provider-side opening behavior.

## User Setup Required

None.

## Next Phase Readiness

Phase 10 is complete. Phase 12 can deliberately adopt the new scope kinds for execution;
Phase 13 retains integrated visual and assistive-technology UAT.

---
*Phase: 10-sender-and-unread-decision-surfaces*
*Completed: 2026-08-10*
