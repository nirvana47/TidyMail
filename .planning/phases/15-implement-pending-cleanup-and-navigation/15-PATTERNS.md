# Phase 15 Pattern Mapping

**Mapped:** 2026-08-11  
**Mode:** Codebase pattern mapping only; this artifact is not an implementation plan.

## Pattern Summary

Phase 15 should extend the repository's existing server-owned Spring MVC, service,
JDBC/JPA, Thymeleaf, and HTMX boundaries:

- `WorkspaceController` remains the owner of authenticated cleanup GET routes and
  page/fragment model assembly.
- A dedicated cleanup POST controller/facade should mirror
  `ActionCommandController`'s strict request parsing and HTMX/native branching while
  reusing `SelectionPreviewTransactionService`'s exact-scope safeguards and forcing
  `ActionCommandService` to `STAGED`.
- `DecisionSurfaceSql` and `DecisionSurfaceQueryRepository` remain the shared source
  of sender/unread predicates. Category summaries, bounded category pages, Inbox-only
  unread, and selected-sender materialization must all derive from those predicates.
- Account-owned sender type feedback should mirror the entity/repository/service and
  PostgreSQL/H2 migration patterns used by `SenderCategoryAssignment`.
- Existing semantic `<details>`, `<dialog>`, category accents, focus styles, CSRF
  fields, and full-page fallbacks should remain. HTMX should replace only category
  bodies or staged row state, never make browser state authoritative.
- Unsubscribe optimization belongs in `UnsubscribeCandidateRepository` and
  `UnsubscribeCandidateService`; its URI parsing, eligibility, SSRF protections, and
  outcome semantics are existing safety boundaries and are not replacement targets.

## Likely File Surface

### New Files

| Likely file | Role and data flow | Closest analog and pattern guidance |
|---|---|---|
| `src/main/java/com/tidymail/entity/SenderTypeOverride.java` | JPA persistence for one normalized sender type per account. Controller input flows through an ownership-validating service; query SQL joins the fact by `(account_id, sender_address)`. | `SenderCategoryAssignment`: private constructor, static `create`, scalar account ID rather than a traversable account relation, normalized address, `@PrePersist`, and explicit mutation method for upsert updates. Add `updatedAt` because corrections can change. |
| `src/main/java/com/tidymail/repository/SenderTypeOverrideRepository.java` | Account-scoped CRUD/upsert lookup for manual semantic type. It must never expose an address-only lookup. | `SenderCategoryAssignmentRepository`: methods shaped as `findByAccountIdAndSenderAddress`, `findAllByAccountIdAndSenderAddressIn`, and account-scoped delete. |
| `src/main/java/com/tidymail/service/SenderTypeOverrideService.java` | Validates authenticated ownership, normalizes the sender, validates the four allowed types, and creates/updates/removes the override transactionally. | `SenderCategoryService`: call `UserAccountRepository.findByIdAndOwnerKey` first; normalize with `Rfc5322Util.extractAddrSpec`; reject controls/overlength; resolve every submitted account/category/sender fact server-side. |
| `src/main/java/com/tidymail/controller/CleanupActionController.java` | Strict staged-action POST boundary. Parses surface, action, canonical query, account-bound row keys, idempotency token, and delete acknowledgement; invokes cleanup staging; returns an HTMX fragment or same-route redirect. | `ActionCommandController`: fixed parameter allowlist, reject query-string parameters, require single values, use `ActionCommandController.ownerKey(authentication)`, branch on `HX-Request`. Unlike the analog, never accept `executionMode`; pass `STAGED` internally. |
| `src/main/java/com/tidymail/service/CleanupActionService.java` | Transaction/facade joining authorization, exact selected-sender materialization, immutable scope creation, confirmation issuance, staged command submission, duplicate rejection, and affected-row result construction. | `SelectionPreviewTransactionService.createPreview` for repeatable-read locking, stale/foreign checks, protected exclusion/audit, 1,001 guard, scope summaries, and confirmation bindings; `ActionCommandService.submit(..., "STAGED", false)` for persistence without provider execution. |
| `src/main/java/com/tidymail/dto/CleanupActionRequest.java` | Typed request after strict controller parsing: surface, action, canonical query, row keys, request token, and delete acknowledgement. | `SelectionRequest`: preserve enum parsing and canonical query reconstruction. Do not carry counts, sizes, metadata IDs, owner keys, or execution mode from the browser. |
| `src/main/java/com/tidymail/dto/CleanupActionResult.java` | Render-safe staged result: affected row identities, queue count, error/state copy, and route/category state. | `ActionCommandService.CommandResult` plus `SelectionScopePreview`, but expose no provider message IDs or raw confirmation tokens back to row markup. |
| `src/main/java/com/tidymail/dto/CategoryPage.java` | Shared bounded category response containing key, rows, loaded count, exact total, `hasMore`, and whether `Load all` is eligible. | `DecisionPage<T>` for immutable page data and canonical links; use category-local offset/limit rather than global page state. |
| `src/main/resources/templates/fragments/cleanup-category-body-frag.html` | Optional shared HTMX fragment for sender/unread headings, rows, and load controls. Request flows from an opened `<details>` to a category GET and swaps only its body. | Existing `senders-results-frag.html` and `unread-results-frag.html`. Keep surface-specific row fragments if sharing introduces condition-heavy markup; the stable boundary is one category body, not the whole page. |
| `src/main/resources/templates/fragments/cleanup-action-result-frag.html` | HTMX response that disables/mutes every affected visible action and updates Review badge state without navigation. | `fragments/action-command-result-frag.html` for command feedback and review fragments using stable DOM IDs. Prefer `hx-swap-oob` for the Review badge and affected row action containers. |
| `src/main/resources/db/migration/V14__sender_type_overrides_and_cleanup_indexes.sql` | PostgreSQL schema for account-owned type override and only measured supporting indexes. | `V12__shared_draft_foundations.sql`: `BIGSERIAL`, FK `ON DELETE CASCADE`, named unique/check constraints, normalized-address check, timestamps, and account-leading indexes. |
| `src/test/resources/db/test-migration/V14__sender_type_overrides_and_cleanup_indexes.sql` | H2 behaviorally equivalent migration. | H2 twin of V12: `BIGINT GENERATED BY DEFAULT AS IDENTITY`, `TIMESTAMP(6) WITH TIME ZONE`, equivalent constraints; omit PostgreSQL-only partial index syntax if necessary. |
| `src/test/java/com/tidymail/Phase15DecisionQueryTest.java` | H2 repository/service proof for summaries, 5/+5/rest paging, stable ties, overlap, overrides, account isolation, and Inbox-only unread. | `Phase10DecisionQueryTest`: Spring Boot H2 integration, Flyway test migrations, direct fixtures, no provider dependency, deterministic ordering assertions. |
| `src/test/java/com/tidymail/Phase15CleanupActionTest.java` | Exact staging proof: persisted IDs/actions, staged-only behavior, cap, protected/stale/foreign input, deduplication, idempotency, delete acknowledgement, and zero provider calls. | `Phase10SelectionMaterializationTest` plus Phase 12 action-state tests. Assert `action_scopes.message_ids` and `user_actions`; verify no `MailProvider` interaction. |
| `src/test/java/com/tidymail/Phase15WorkspaceWebTest.java` | MockMvc/rendered HTML proof for rail, account switch, category fragments, row disabling, warning copy, fallback redirects, and authorization. | `Phase10SenderUnreadWebTest` and `Phase11UnsubscribeWebTest`: authenticated `MockMvc`, CSRF POSTs, full-page versus fragment assertions, route-owner checks. |
| `src/test/java/com/tidymail/Phase15PostgresMigrationTest.java` | V13-to-V14 upgrade, constraints/cascade/indexes, Hibernate validation, array membership, production SQL plans, and large fixtures. | `Phase10PostgresMigrationTest`: PostgreSQL 16 Testcontainers, Flyway target upgrade, 10k fixtures, `ANALYZE`, `EXPLAIN`, and application-context validation. |
| `src/test/java/com/tidymail/Phase15UnsubscribePerformanceTest.java` | Production-shaped query-count, timing, allocation/plan evidence for default and visible filters. | `Phase13PerformanceTest`: warm/cold observations and `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`; `Phase11CandidateQueryTest` for all-sort stability and safety semantics. |
| `src/test/java/com/tidymail/Phase15TemplateContractTest.java` | Cheap static contract guard for no export affordances, no Selection Preview forms, control count/order, note placement, headings, and row columns. | `Phase14WorkspaceFidelityTest`: read source files and assert required/forbidden markup. Restrict the no-export scan to workspace templates so login privacy prose is not a false positive. |

Names above follow local naming. `CleanupAction*`, `CategoryPage`, and shared fragment
files are decomposition candidates; equivalent narrowly scoped names are consistent
with the codebase.

### Existing Java Files Likely Modified

| File | Role and data flow | Closest analog / concrete guidance |
|---|---|---|
| `dto/WorkspacePageModel.java` | Carries authorized rail facts to every workspace template. Add nullable `inboxUnreadCount` and `inboxUnreadCountAvailable()`. | Existing nullable `queuedActionCount` plus `queueCountAvailable()`. Unavailable is `null`, not fabricated zero. |
| `dto/WorkspaceRoute.java` | Central route names and shared copy. | Replace obsolete titles/rail titles with exact labels; remove export-oriented Analytics copy. Keep `value/path/parse` unchanged. |
| `dto/AccountScopeOption.java` | Rail option label source if labels are not changed solely in service. | Preserve value/selected state; label should be email only. |
| `dto/SenderPageModel.java`, `dto/UnreadPageModel.java` | Carry category summaries/pages and canonical category URLs; remove UI-only export/Selection Preview fields where no longer consumed. | Existing immutable records and canonical parameter maps. Category state must be account/category-local and server-generated. |
| `dto/SenderCategoryGroup.java`, `dto/UnreadCategoryGroup.java` | Change from fully populated groups to exact summary plus optional bounded page state. | Keep stable keys/titles/descriptions; add loaded/total/has-more facts rather than inferring totals from `rows.size()`. |
| `dto/CleanupAction.java` | Settings labels and action conversion. | Values should render `Archive`, `Delete`, `Mark as Read`; retain enum-based allowlisting and action mapping. |
| `controller/WorkspaceController.java` | Full pages and GET fragments. Add category-body endpoints or parameters, build summary-only initial pages, and preserve account-only route switching. | Existing pairs `/senders` + `/senders/results` and `/unread` + `/unread/results`. A category endpoint should resolve workspace ownership before querying and return `fragments/... :: categoryBody`. Do not invoke providers. |
| `controller/SenderCategoryController.java` | Category/type correction POST boundary. It may delegate built-in semantic types to override service and custom IDs to category service. | Preserve `returnTo` allowlist and `RedirectView` fallback. Replace loose binding where needed with strict allowlists; never trust hidden account/category ownership. |
| `service/WorkspaceModelService.java` | Resolves one owned account, queue count, Inbox-unread count, labels, and availability. | Mirror queued count's guarded lookup. Reuse the same repository Inbox predicate as unread queries. `accountLabel` should return email only. |
| `service/SenderDecisionQueryService.java` | Maps exact category summaries and bounded sender pages to render DTOs. | Replace `streamSenders(...).collect/sort` in `categories(...)` with repository summary/page methods. Keep `DecisionClassifier` mapping and stable row-key encoding. |
| `service/UnreadDecisionQueryService.java` | Maps overlapping unread summaries/pages and sender-level action identities. | Replace whole-message accumulation with SQL summaries/pages. Preserve independent membership predicates; do not force categories to be mutually exclusive. |
| `service/DecisionRuleSet.java` | Java inference and reason codes. | Keep SQL/Java parity. Add `MANUAL_SENDER_TYPE_OVERRIDE`; test inference separately with no override. Type override precedes risk/recommendation and unread list/direct grouping. |
| `service/DecisionClassifier.java` | Enforces SQL/Java classification parity and creates rows. | Compare effective SQL type against Java inference plus optional override. Never silently suppress parity failures. |
| `service/SelectionMaterializationService.java` and/or `service/SelectionPreviewTransactionService.java` | Existing exact-ID engine reused by direct cleanup staging. Add sender-level unread selection rather than browser metadata-row accumulation. | Preserve account locks, stale locator checks, protected audits, distinct IDs, and 1,001 guard. For one sender populate `ActionScopeTargetSummary.senderAddress`; multi-sender identity must remain explicit enough for disabled/queued reconciliation. |
| `service/ActionCommandService.java` | Persists staged Review actions. | Existing behavior already avoids execution when mode is `STAGED`. Cleanup facade supplies the mode internally and adds active-equivalent/idempotency handling before duplicate creation. |
| `repository/DecisionSurfaceSql.java` | Shared base CTEs for sender and unread classification/filtering. Join overrides once at normalized sender identity and add Inbox membership to `unread_base`. | Keep one CTE chain so summaries, rows, and materialization share predicates. Effective type should be `COALESCE(override.sender_type, inferred_type)` before risk/recommendation. |
| `repository/DecisionSurfaceQueryRepository.java` | Exact summaries, bounded category pages, Inbox count, and selected-sender materialization. | Existing `senders/unread` use count + `ORDER BY ... LIMIT/OFFSET`; existing materialization uses distinct IDs and a 1,001 guard. Category query ordering should end with `account_id ASC, sender_key ASC`. |
| `repository/AnalyticsMailboxRepository.java` or a shared SQL helper | Established H2/PostgreSQL label membership. | Reuse/extract `h2 ? "ARRAY_CONTAINS(labels, 'INBOX')" : "'INBOX'=ANY(labels)"`; do not duplicate subtly different Inbox predicates. |
| `repository/UserActionRepository.java` or cleanup-specific query repository | Active-equivalent action/idempotency check and selected-account queue refresh. | Follow account-leading finder/count methods. Duplicate detection must be transactional and server-side; disabled HTML is not a lock. |
| `service/UnsubscribeCandidateService.java` | Orchestrates only the requested page's sender summaries, candidate sources, outcomes, and cleanup history. | Current `candidatesBySender` and sender `while` loops are the anti-pattern. Preserve `senderRow`, `classify`, `matches`, and safety logic but feed them a bounded page set. |
| `repository/UnsubscribeCandidateRepository.java` | Push account/date/category/search/protection/default volume ordering/count into SQL before `LIMIT/OFFSET`; page-bound candidate/outcome/history joins. | `ReviewActionQueryRepository` demonstrates count plus stable SQL paging. Replace dynamic cleanup-target OR expansion with relational joins/`EXISTS`. Keep complete tie-breakers. |

### Existing Templates and Assets Likely Modified

| File | Role and data flow | Pattern guidance |
|---|---|---|
| `templates/fragments/workspace-rail.html` | Shared navigation, rail-only account selection, badges, and session controls. | Brand becomes `/senders`; exact labels; Review and Unread badges live inside links; remove footer queue row and submit button. Existing choice change should submit the GET form except `add:google`, which navigates to connect. Keep email title plus CSS truncation. |
| `templates/senders.html` | Quiet Bulk Archive/Delete shell and dialogs. | Remove export, toolbar explanatory card, preview slot, page filters/selectors. Render exactly two adjacent right-aligned controls. Settings copy is `Choose the default action`. Keep CSRF and centered `<dialog>`. |
| `templates/unread.html` | Same shared shell plus overlap note before categories. | Same as sender shell; place non-mutual-exclusivity copy before category list. |
| `templates/unsubscribe.html` | Preserves distinct filters/default action while aligning shell and typography. | Remove export only; retain category, exact dates, sender/domain search, and Archive/Delete setting. Do not turn it into grouped categories. |
| `templates/fragments/senders-results-frag.html` | Summary disclosures and sender row rendering. | Keep closed `<details>` and accent mapping. Initial HTML should not contain unrequested rows. Every category gets checkbox/correction controls. Column order: identity, domain, message count, size, action. Replace `/api/selection-preview` with staged cleanup endpoint. |
| `templates/fragments/unread-results-frag.html` | Overlapping category disclosures and sender-level actions. | Same bounded body pattern. Remove trailing overlap note. Every action submits authorized sender row key plus canonical query, not accumulated metadata IDs or `ALL_MATCHING`. |
| `templates/fragments/unsubscribe-results-frag.html` | Individual unsubscribe candidates and sender actions. | Preserve candidate inspection/action HTMX targets and row action menu. Align row hierarchy only; safety and honest state copy remain. |
| `templates/analytics.html`, `templates/analytics-source.html`, `templates/fragments/review-history-frag.html` | No-export workspace contract. | Remove anchors and empty wrappers containing `.csv`, snapshot, download, or Export affordances. Backend routes may remain. |
| `static/js/cleanup-categories.js` | Progressive enhancement for expand/collapse, category lazy load/reset, dialogs, and account-aware row selection. | Keep `DOMContentLoaded`, `data-*` hooks, and native `<dialog>`. On open, request first five if body is absent; on collapse, discard/reset body state. Rebind through event delegation or HTMX lifecycle because swapped controls do not exist at initial load. |
| `static/css/workspace.css` | Shared rail, category, row, dialog, staged, loading, responsive, and focus styling. | Preserve `--shell-rail: 248px`, category accent variables, `:focus-visible`, 44px narrow targets, and no global `overflow-x:hidden`. Replace `.cleanup-toolbar` card styling with a simple action row. Add subtle centered load controls and muted disabled staged actions. Remove orphaned download styles if unused. |

### Existing Tests Likely Modified

| File | Contract change |
|---|---|
| `Phase10DecisionQueryTest.java` | Seed labels and assert Inbox unread excludes archived unread; replace full-category materialization expectations with exact summaries and 5/+5/rest pages. |
| `Phase10PostgresMigrationTest.java` | Add `labels` to unread fixtures and retain PostgreSQL plan coverage; Phase 15 migration specifics can live in the new Phase 15 class. |
| `Phase10SelectionMaterializationTest.java` | Exercise sender-level unread materialization and archived-unread exclusion. |
| `Phase10SenderUnreadWebTest.java` | Replace Selection Preview/full-row assertions with category fragments, direct staging, disabled state, corrected copy/order, and note placement. |
| `Phase11CandidateQueryTest.java` | Keep all safety and stable-sort assertions; add evidence that only requested page senders/candidates/outcomes are loaded. |
| `Phase11PostgresMigrationTest.java` | Preserve unsubscribe safety schema/plan checks; adapt only if repository SQL shape changes. |
| `Phase11UnsubscribeWebTest.java` | Remove export expectation and retain category/date/search/default-action and candidate safety flows. |
| `Phase14InteractionFidelityTest.java` | Reverse obsolete download assertions; retain native/HTMX progressive fallback checks and Trash recovery copy. |
| `Phase14WorkspaceFidelityTest.java` | Replace old labels, standalone queue, Switch account, and Selection Preview assertions with canonical labels, badges, auto-submit, and staged forms. |
| `Phase13AnalyticsWebTest.java`, `Phase13ExportTest.java`, `Phase13IntegratedProductTest.java` | Workspace render tests must no longer require export links. Backend export tests may remain valid under D-06 if they do not assert discoverability. |

## Layer Patterns and Excerpts

### Controllers

Use one route owner and explicit full/fragment responses:

```java
@GetMapping("/senders/results")
public String senderResults(..., Model model) {
    model.addAttribute("page", senderPage(parameters, authentication));
    return "fragments/senders-results-frag :: results";
}
```

For new POSTs, copy the strict boundary from `ActionCommandController`:

```java
private static final Set<String> PARAMETERS = Set.of(/* exact fields */, "_csrf");

if (request.getQueryString() != null) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query_parameters_forbidden");
}
rejectUnknown(parameters);
String ownerKey = ActionCommandController.ownerKey(authentication);
```

HTMX and native responses must share the same service call:

```java
return "true".equalsIgnoreCase(hxRequest)
    ? "fragments/cleanup-action-result-frag :: result"
    : "redirect:/senders?account=" + authorizedAccountId;
```

The fallback route is the originating cleanup page with canonical account state, not
Selection Preview or Review.

### Services

Ownership before mutation:

```java
if (ownerKey == null || ownerKey.isBlank() || accountId <= 0
        || accounts.findByIdAndOwnerKey(accountId, ownerKey).isEmpty()) {
    throw new IllegalArgumentException("Account is not owned");
}
```

Exact staging should wrap, not duplicate, the existing materialization engine:

```java
MaterializationResult materialized = queryRepository.materialize...(...);
ActionScopePreview scope = actionScopeService.createOrGetRequired(
    accountId, action.name(), "SENDER_SELECTION",
    account.messageIds(), targetSummary, riskSummary);
String confirmation = confirmationIntents.issue(ownerKey, actionType, source, bindings);
commands.submit(ownerKey, authorizedIds, confirmation, "STAGED", false);
```

Retain the current exactness invariants:

```sql
SELECT DISTINCT account_id, provider_message_id
...
ROW_NUMBER() OVER (
    PARTITION BY account_id ORDER BY provider_message_id
)
WHERE row_number <= :guard_limit -- 1001
```

Delete staging adds server-validated acknowledgement and visible copy mentioning
Gmail Trash's typical 30-day recovery window. A JavaScript `confirm()` alone is not a
valid service boundary.

### Repositories and SQL

Bound category work before returning rows:

```sql
WITH ...,
category_facts AS (
  SELECT ...
  FROM classified_facts
  WHERE <authorized canonical filters>
    AND <category predicate>
)
SELECT <row columns>
FROM category_facts
ORDER BY total_count DESC, account_id ASC, sender_key ASC
LIMIT :limit OFFSET :offset
```

Run exact summaries separately without returning bodies:

```sql
SELECT COUNT(*) AS sender_count,
       COALESCE(SUM(total_count), 0) AS message_count,
       CASE WHEN COUNT(estimated_size) = COUNT(*)
            THEN SUM(estimated_size) ELSE NULL END AS estimated_size
FROM category_facts
```

Inbox membership follows the existing dialect switch:

```java
return h2
    ? "ARRAY_CONTAINS(" + column + ", 'INBOX')"
    : "'INBOX'=ANY(" + column + ")";
```

Apply it in `unread_base`:

```sql
WHERE m.account_id IN (:account_ids)
  AND m.unread = true
  AND <hasLabel(m.labels, INBOX)>
```

That base must feed browsing, category summaries/pages, sender-level materialization,
and rail count. Avoid separate handwritten predicates.

Manual type precedence belongs before risk and recommendation:

```sql
inferred_facts AS (...),
typed_facts AS (
  SELECT facts.*,
         COALESCE(override.sender_type, facts.inferred_sender_type) AS sender_type,
         CASE WHEN override.sender_type IS NULL THEN 0 ELSE 1 END AS manual_override
  FROM inferred_facts facts
  LEFT JOIN sender_type_overrides override
    ON override.account_id = facts.account_id
   AND override.sender_address = facts.sender_key
)
```

Custom category assignment remains a separate fact. It controls sender-page placement;
the effective semantic type controls unread list/direct membership.

### Migrations

Use the V12 paired migration shape:

```sql
CREATE TABLE sender_type_overrides (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    sender_address VARCHAR(320) NOT NULL,
    sender_type VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_sender_type_overrides_account_sender
        UNIQUE (account_id, sender_address),
    CONSTRAINT sender_type_overrides_address_check CHECK (
        sender_address <> ''
        AND sender_address = lower(trim(sender_address))
        AND char_length(sender_address) <= 320
    ),
    CONSTRAINT sender_type_overrides_type_check CHECK (
        sender_type IN ('LIST', 'AUTOMATED', 'TRANSACTIONAL', 'PERSONAL')
    )
);
CREATE INDEX idx_sender_type_overrides_account_type_sender
    ON sender_type_overrides (account_id, sender_type, sender_address);
```

The H2 twin changes identity/timestamp syntax only. Any Inbox partial index is
evidence-driven: add it only when PostgreSQL `EXPLAIN (ANALYZE, BUFFERS)` demonstrates
use on production-shaped data.

### Thymeleaf and HTMX

Retain semantic disclosures and server-owned totals:

```html
<details class="cleanup-category"
         th:attr="data-category-key=${category.key},
                  hx-get=${category.initialPageUrl}"
         hx-trigger="toggle[this.open] once"
         hx-target="find .cleanup-category-body"
         hx-swap="innerHTML">
  <summary>
    <span>
      <strong th:text="${category.title}">Category</strong>
      <small th:text="${category.description}">Description</small>
    </span>
    <span class="category-count" th:text="${category.senderCount}">0</span>
  </summary>
  <div class="cleanup-category-body"></div>
</details>
```

The precise HTMX trigger may differ, but these invariants hold:

- initial/full-page HTML does not contain unrequested sender rows;
- category GET is authorized and reconstructs canonical query state;
- `Load more` requests the next five; `Load all` is explicit and may return at most
  1,000 cumulative sender rows for the category;
- a category total above 1,000 returns HTTP 422 with guidance to continue five-row
  paging, preserves rendered rows, and does not execute an all-row query;
- collapse clears the body so reopening starts at five;
- category totals never become action scopes.

Staged forms use row keys:

```html
<form method="post" action="/api/cleanup-actions"
      hx-post="/api/cleanup-actions"
      hx-target="#cleanup-action-status" hx-swap="innerHTML">
  <input type="hidden" name="surface" value="SENDERS">
  <input type="hidden" name="rowKey" th:value="${row.rowKey}">
  <input type="hidden" name="requestToken" th:value="${page.requestToken}">
  <!-- canonical filter fields and CSRF -->
</form>
```

Do not render `executionMode`, message IDs, counts-as-scope, or Selection Preview
fields. Trash confirmation should be a centered native `<dialog>` with a required
acknowledgement submitted to the server.

### CSS and JavaScript

Preserve existing design-system contracts:

```css
--shell-rail: 248px;
.cleanup-category { --category-accent: #64748b; }
:focus-visible { /* existing visible focus treatment */ }
```

New states should be narrowly named:

```css
.cleanup-page-actions {
    display: flex;
    justify-content: flex-end;
    gap: var(--space-2);
}
.category-load-actions {
    display: flex;
    justify-content: center;
    gap: var(--space-2);
}
.cleanup-action[disabled],
.cleanup-action.is-staged {
    opacity: .55;
    cursor: not-allowed;
}
```

Use event delegation because HTMX introduces category bodies after
`DOMContentLoaded`. On `toggle` close, clear the category body and loaded-count data;
on open, request the initial fragment if empty. Continue to use `data-dialog-open`,
`data-dialog-close`, and `HTMLDialogElement.showModal()`.

Account switching remains a GET:

```js
select.addEventListener("change", () => {
    if (select.value === "add:google") {
        window.location.assign("/accounts/connect/google");
        return;
    }
    select.form.requestSubmit();
});
```

Since only `account` is present in the rail form, incompatible category/query/selection
state resets naturally on the current route.

### Tests

Repository fixtures should distinguish:

```text
Inbox + unread       => included
Inbox + read         => excluded
archived + unread    => excluded
Spam + unread        => excluded
Trash + unread       => excluded
```

Bounded category assertions need more than DOM size:

```java
assertThat(first.rows()).hasSize(5);
assertThat(second.rows()).hasSize(5);
assertThat(first.rows()).doesNotContainAnyElementsOf(second.rows());
assertThat(all.rows()).hasSize(total);
```

Also capture repository calls or SQL statement counts so a five-row fragment cannot
hide an all-row backend scan.

Exact staging assertions should inspect persistence:

```java
assertThat(scope.getMessageIds()).containsExactlyInAnyOrder(/* exact qualifying IDs */);
assertThat(action.getStatus()).isEqualTo("QUEUED");
verifyNoInteractions(gmailProvider, outlookProvider);
```

PostgreSQL performance follows the established pattern:

```java
for (int i = 0; i < 10_000; i++) { /* production-shaped fixture */ }
statement.execute("ANALYZE message_metadata");
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) ...
```

No-export static scan should include:

```text
senders.html
unread.html
unsubscribe.html
analytics.html
analytics-source.html
fragments/review-history-frag.html
```

and reject `.csv`, `snapshot`, `download`, `workspace-download`,
`analytics-download`, and visible `Export`, while excluding `login.html`.

## Data Flow Maps

### Bounded Category Read

```text
GET current cleanup route
  -> WorkspaceController resolves owned account
  -> WorkspaceModelService builds rail facts
  -> Sender/UnreadDecisionQueryService loads exact summaries only
  -> page renders closed <details> with empty bodies
  -> category open GET carries canonical account/query/category/offset
  -> controller re-resolves ownership
  -> service calls repository categoryPage(..., limit=5)
  -> Thymeleaf category body fragment swaps into that disclosure
  -> +5/load-all repeats for that category only
  -> collapse discards browser body state
```

### Manual Classification Feedback

```text
POST account + sender row key + built-in type/custom category
  -> strict controller parsing + authenticated owner key
  -> service decodes row key and validates account ownership
  -> built-in type: upsert SenderTypeOverride
  -> custom category: validate category ownership and upsert assignment
  -> next query joins both facts
  -> custom assignment controls sender-page placement
  -> effective override/inference controls semantic type and unread grouping
```

### Direct Review Staging

```text
POST surface + action + canonical query + sender row key(s)
  -> strict controller; no execution mode accepted
  -> cleanup service resolves current owned account and locks materialization
  -> duplicate row keys normalize to unique sender identities in canonical order
  -> repository rebuilds displayed predicates and selects each sender's exact IDs
  -> distinct union enforces the separate 1,000 action-ID cap before writes
  -> protected/cap/stale/foreign/idempotency checks
  -> one immutable scope + account-local Review action per unique sender
  -> active-equivalent actions are reused; incompatible actions retain conflicts
  -> all new actions persist atomically with deterministic Review sequences
  -> ActionCommandService submit(STAGED, false) for each missing sender action
  -> no provider call
  -> HTMX returns sender/action reconciliation, muted disabled rows + Review badge
     or native POST redirects to same cleanup route
```

### Bounded Unsubscribe Read

```text
GET /unsubscribe with account/date/category/search/sort/page
  -> owned account resolution
  -> SQL count + sender-summary page applies visible filters/order first
  -> candidate source metadata fetched for page sender identities only
  -> latest outcomes fetched for page fingerprints only
  -> cleanup history resolved by relational EXISTS/join
  -> existing Java parser/classifier handles bounded candidates
  -> existing safety/result DTOs render unchanged semantics
```

## Avoided Anti-Patterns

- Do not call `streamSenders`/`streamUnread` and then trim to five in Java.
- Do not preload complete category bodies and hide rows with CSS/JS.
- Do not store manual type feedback on `message_metadata`.
- Do not merge custom category assignment and semantic sender type into one fact.
- Do not trust account IDs, category IDs, sender addresses, message IDs, counts, or
  execution modes merely because they arrived in hidden inputs.
- Do not create a second exact-scope engine outside the existing materialization and
  action-scope services.
- Do not implement duplicate prevention only by disabling a button.
- Do not use All Mail unread semantics for rail counts, categories, or actions.
- Do not mask unsubscribe latency with a spinner while retaining unbounded scans.
- Do not weaken unsubscribe destination parsing, SSRF protection, eligibility, or
  outcome truthfulness while changing query shape.
- Do not remove backend export code solely to satisfy D-06; remove workspace
  discoverability and update obsolete UI assertions.
