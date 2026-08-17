# Phase 16: Reconcile UI With Mocks - Pattern Map

**Generated:** 2026-08-12
**Scope:** Planning artifact only. No application code changes.

This map is based on `16-CONTEXT.md`, `16-RESEARCH.md`, the Phase 16 design
references named there, and the closest implementation/test analogs needed for
planning. Phase 16 is primarily an authenticated workspace template/CSS/JS
reconciliation pass. Backend changes should be limited to preserving existing
server-owned facts or exposing already-supported category data when required by
the mock contract.

## Files Extracted From Context And Research

### Planning Artifact To Create

| File | Role | Data flow / planner note |
|---|---|---|
| `.planning/phases/16-reconcile-ui-with-mocks/16-PATTERNS.md` | New pattern map for planner. | Read-only code analysis distilled into implementation guidance. This file is the only file created by the pattern-mapping task. |

### Primary Implementation Files Likely Modified

| File | Role | Data flow |
|---|---|---|
| `src/main/resources/templates/fragments/workspace-rail.html` | Shared authenticated left rail. | Receives `WorkspacePageModel` from every workspace controller route. Renders route links, account selector, session controls, and currently visible Unread/Review count badges. |
| `src/main/resources/templates/senders.html` | Bulk Archive/Delete page shell. | Receives `SenderPageModel`; renders rail, page header, Settings/Expand controls, sender result fragment, exact staging bulk form, status panel inclusion, and dialogs. |
| `src/main/resources/templates/unread.html` | Unread Only page shell. | Receives `UnreadPageModel`; renders rail, locked header copy target, unread result fragment, exact staging bulk form, status panel inclusion, and dialogs. |
| `src/main/resources/templates/fragments/senders-results-frag.html` | Sender category summaries and row chunks. | Receives sender categories, canonical query, cleanup action, category URLs. Renders `<details>` categories, initial bounded rows, Other tools, per-row staging forms, and correction controls. |
| `src/main/resources/templates/fragments/unread-results-frag.html` | Unread group summaries and row chunks. | Receives unread groups and lazy category URLs. Renders stale notes, collapsed categories, row chunks, per-row staging forms, and correction controls. |
| `src/main/resources/templates/fragments/cleanup-action-result-frag.html` | Persistent cleanup staging panel and OOB Review count fragment. | Included by sender/unread pages; JS expects `#cleanup-action-status` for success/error feedback. Phase 16 should remove visible panel dependency from cleanup pages. |
| `src/main/resources/static/js/cleanup-categories.js` | Progressive enhancement for category toggles, lazy loads, dialogs, delete confirmation, and post-stage reconciliation. | Reads `data-*` hooks in templates; posts HTMX forms to `/api/cleanup-actions`; currently mutates persistent status panel and staged rows. |
| `src/main/resources/static/css/workspace.css` | Shared workspace UI styles. | Owns rail, category, row, action, dialog, staging, accessibility, and responsive rules. Phase 16 should keep shared tokens and narrow-target guarantees while reducing heavy card treatment. |
| `src/main/java/com/tidymail/service/WorkspaceModelService.java` | Server-owned rail/account facts. | Builds `WorkspacePageModel`, account options, selected account label, queued action count, and Inbox unread count. Counts should remain even if visual badges disappear. |
| `src/main/java/com/tidymail/service/CleanupActionService.java` | Exact cleanup staging service. | Materializes exact sender/unread scopes, persists staged Review actions, returns `StageResult` JSON for HTMX row reconciliation. |
| `src/main/java/com/tidymail/service/SenderDecisionQueryService.java` | Sender category summaries and bounded category pages. | Maps repository sender facts to `SenderCategoryGroup` and `CategoryPage<SenderDecisionRow>`. Current groups are predefined semantic groups only. |
| `src/main/java/com/tidymail/service/UnreadDecisionQueryService.java` | Unread category summaries and bounded unread sender rows. | Streams Inbox-unread facts, groups by sender, builds overlapping `UnreadCategoryGroup` summaries and rows. |
| `src/main/java/com/tidymail/service/SenderCategoryService.java` | Account-owned category creation/assignment and unread category paging helper. | Owns user-created category persistence, assignment, ownership checks, and 5/+5/load-all paging for unread groups. |
| `src/main/java/com/tidymail/repository/DecisionSurfaceQueryRepository.java` | SQL repository for sender/unread facts, summaries, rows, counts, and materialization. | Provides `senderCategorySummaries`, `senderCategoryRows`, `countUnread`, and `streamUnread`. |
| `src/main/java/com/tidymail/repository/DecisionSurfaceSql.java` | Shared SQL CTE builder and predicates. | Centralizes sender/unread predicates. `unread_base` already requires `m.unread = true` and `INBOX` label via dialect-aware `hasLabel`. |

### Audit-Only Or Guardrail Files

| File | Role | Planner note |
|---|---|---|
| `src/main/resources/templates/unsubscribe.html` | Bulk Unsubscribe shell. | Visual/interaction audit only. Preserve individual sender workflow. |
| `src/main/resources/templates/fragments/unsubscribe-results-frag.html` | Bulk Unsubscribe sender rows. | Closest local analog for compact table-like row hierarchy and overflow actions. Do not convert to grouped cleanup categories. |
| `src/main/resources/templates/review.html` and review fragments | Review destination for staged exact scopes. | Ensure discoverability and no export controls; do not weaken LIFO/exact count/warning behavior. |
| `src/main/resources/templates/analytics.html` and analytics fragments | Analytics workspace page. | Audit for no export/snapshot/download affordances; do not reopen analytics backend. |

### Tests Likely Added Or Updated

| File | Role | Change direction |
|---|---|---|
| `src/test/java/com/tidymail/Phase16WorkspaceFidelityTest.java` | Likely new focused structural contract test. | Prefer adding a Phase 16 test class for no rail badges, exact Unread copy, no stale notes, no persistent status panel, separate category meta, new category-management copy, user-generated tags, and no exports. |
| `src/test/java/com/tidymail/Phase14WorkspaceFidelityTest.java` | Existing static rail/category/CSS guard. | Invert assertion that currently expects `class="rail-nav-badge"`; retain server-owned account/count model tests. |
| `src/test/java/com/tidymail/Phase15WorkspaceWebTest.java` | Existing MockMvc/template tests for cleanup shell. | Invert assertions for `cleanup-action-status`, `Correct classification`, stale notes, and status fragment inclusion. Preserve ownership and paging tests. |
| `src/test/java/com/tidymail/Phase10SenderUnreadWebTest.java` | Existing sender/unread web route contract. | Update old copy/assertions while preserving route ownership, canonical params, exact staging, and no Selection Preview. |
| `src/test/java/com/tidymail/Phase15UnreadSemanticsTest.java` | Inbox-unread semantic guard. | Preserve as non-negotiable safety coverage. |
| `src/test/java/com/tidymail/Phase15StagingTest.java` | Exact staging guard. | Preserve exact-ID, ownership, duplicate/reuse, delete acknowledgement, and no provider mutation assertions. |
| `src/test/java/com/tidymail/Phase15CategoryPagingTest.java` | Category paging guard. | Preserve bounded 5/+5/load-all behavior and stable ordering. |
| `src/test/java/com/tidymail/Phase13WorkspaceResponsiveTest.java` and `Phase14InteractionFidelityTest.java` | No-export and responsive/fallback guards. | Keep no-export scans across workspace templates; avoid false positives from backend export tests. |

## Cross-Cutting Patterns

### Keep Server Facts, Remove Visual Rail Badges

Closest analog: `WorkspaceModelService` already owns counts and unavailable states.

Current rail badge output:

```html
<a th:href="@{/unread}"
     th:attr="aria-current=${workspace.route.value == 'unread' ? 'page' : null},
              aria-label=${workspace.inboxUnreadCountAvailable()
                  ? 'Unread Only, ' + workspace.inboxUnreadCount + ' Inbox unread messages'
                  : 'Unread Only'}">
      <span>Unread Only</span>
      <span class="rail-nav-badge" aria-hidden="true"
            th:if="${workspace.inboxUnreadCountAvailable()}"
            th:text="${workspace.inboxUnreadCount}">0</span>
</a>
```

Server facts to preserve:

```java
Long queuedCount = actionRepository.countByAccountIdInAndStatus(selectedIds, "QUEUED");
Long inboxUnreadCount = decisionQueryRepository.countUnread(
    selectedIds, unreadQuery, clock.instant());
```

Planner guidance:

- Remove visible `.rail-nav-badge` spans from Unread Only and Review.
- Keep `WorkspacePageModel.queuedActionCount`, `queueCountAvailable()`,
  `inboxUnreadCount`, and `inboxUnreadCountAvailable()` intact.
- If counts remain in `aria-label`, ensure they are not visible clutter and do not
  cause tests to assert visible numeric pills.

### Row/Button Feedback Already Exists, But Is Coupled To A Panel

Closest analog: `cleanup-categories.js` already disables/mutes staged rows.

Current JS success path:

```js
result.senders.forEach((staged) => {
    document.querySelectorAll(".cleanup-row[data-sender-address]").forEach((row) => {
        if (row.dataset.senderAddress !== staged.senderAddress) return;
        row.classList.add("is-staged");
        row.querySelectorAll("button, input[type='checkbox']").forEach((control) => {
            control.disabled = true;
        });
        const action = row.querySelector(".cleanup-action");
        if (action) action.textContent = staged.reused ? "Already staged" : "Staged";
    });
});
```

Current coupling to remove:

```js
const status = document.getElementById("cleanup-action-status");
if (!(status instanceof HTMLElement) || !Array.isArray(result.senders)) return;
...
status.focus();
```

Service result shape to keep:

```java
public record StageResult(
    UUID requestToken,
    PreviewAction action,
    List<SenderStage> senders,
    int distinctMessageCount,
    long reviewCount,
    String recoveryNotice
) {}
```

Planner guidance:

- Keep the row disabling and `Staged`/`Already staged` behavior as the base.
- Replace `#cleanup-action-status` dependency with a compact accessible live
  region or row/form-local target.
- Keep error feedback accessible; do not reintroduce a persistent result panel.
- Do not use Review navigation, Selection Preview, or provider mutation as staging
  feedback.

### Category Summaries Have The Needed Metrics

Closest analogs: sender and unread category DTOs already expose separate values.

Sender group DTO:

```java
public record SenderCategoryGroup(
    String key,
    String title,
    String description,
    long senderCount,
    Long estimatedSize,
    boolean userGenerated,
    boolean other,
    CategoryPage<SenderDecisionRow> initialPage
) {}
```

Unread group DTO:

```java
public long senderCount() {
    return exactSenderCount;
}

public long unreadCount() {
    return exactUnreadCount;
}
```

Current sender summary markup:

```html
<span>
    <strong th:text="${category.title}">Category</strong>
    <small th:text="${category.description}">Description</small>
</span>
<span class="category-count"
      th:text="|${category.senderCount} ${category.senderCount == 1 ? 'sender' : 'senders'}|">
    0 senders
</span>
```

Design/mock pattern:

```html
<span class="category-title">
  <strong>Lists and newsletters</strong>
  <span>Recurring lists and publication mail.</span>
</span>
<span class="category-meta">
  <span>84 senders</span>
  <span>About 2.4 GB</span>
</span>
```

Planner guidance:

- Use a left title/description block and a right meta block.
- Sender categories: render sender count plus size when `estimatedSize != null`;
  planner should choose hide versus estimated/pending wording for unavailable
  sizes.
- Unread categories: render sender count plus unread total as separate meta items.
- Do not use category totals as action scopes; row keys remain the action contract.

### User-Created Category Label Is DTO-Supported But Not Fully Emitted

Closest analog: `SenderCategoryService` owns categories and assignments, but
`SenderDecisionQueryService.categories` currently emits only predefined groups.

Current sender group construction:

```java
return List.of(
    group("lists", "Lists and newsletters", ..., summaries.get("lists"), false, ...),
    group("automated", "Automated notifications", ..., summaries.get("automated"), false, ...),
    group("transactional", "Receipts and transactions", ..., summaries.get("transactional"), false, ...),
    group("personal", "Personal senders", ..., summaries.get("personal"), false, ...),
    group("other", "Other", ..., summaries.get("other"), true, ...));
```

Current hard-coded `userGenerated` value:

```java
return new SenderCategoryGroup(
    key,
    title,
    description,
    senderCount,
    summary == null ? null : summary.estimatedSize(),
    false,
    other,
    categoryPage(...));
```

Account-owned category service:

```java
public SenderCategory create(String ownerKey, long accountId, String rawName) {
    requireOwnedAccount(ownerKey, accountId);
    SenderCategoryNameSanitizer.SanitizedName name = sanitizer.sanitize(rawName);
    if (categories.existsByAccountIdAndNameKey(accountId, name.key())) {
        throw new IllegalArgumentException("A category with this name already exists");
    }
    return categories.save(SenderCategory.create(accountId, name.value(), name.key()));
}
```

Planner guidance:

- Template-only label work is not enough if no user-generated groups are emitted.
- Planner should decide whether Phase 16 includes limited service/repository work
  to render account-owned categories as first-class `SenderCategoryGroup` records
  with `userGenerated=true`.
- Keep scope account-owned; do not create global learning or cross-user category
  rules.
- Use visible tag text exactly `User generated`.

### Category Management Should Reuse Existing Dialogs And Strict Boundaries

Closest analog: existing category correction and organize endpoints already split
semantic type overrides from custom category placement.

Current per-row control:

```html
<button type="button" class="plain-button correction-control"
        data-dialog-open="sender-correction-dialog"
        th:attr="data-sender-address=${row.senderAddress},
                 data-account-id=${row.accountId}"
        th:disabled="${row.senderAddress.empty}">
    Correct classification
</button>
```

Strict correction endpoint:

```java
private static final Set<String> CORRECTION_PARAMETERS = Set.of(
    "accountId", "senderAddress", "correctionTarget", "returnTo", "_csrf");

if (request.getQueryString() != null
        || parameters.keySet().stream()
            .anyMatch(name -> !CORRECTION_PARAMETERS.contains(name))) {
    throw bad("Invalid correction parameters");
}
```

Organize endpoint:

```java
if (categoryName != null && !categoryName.isBlank()) {
    SenderCategory created = categories.create(ownerKey, accountId, categoryName);
    target = created.getId();
}
if (!senders.isEmpty()) {
    if (target == null) {
        throw new IllegalArgumentException("A destination category is required");
    }
    categories.assign(ownerKey, accountId, target, senders);
}
```

Planner guidance:

- Replace visible `Correct classification` copy with user-facing mock-aligned
  wording such as `Change type`, `Move selected`, and `Create category`.
- Preserve existing dialog, CSRF, ownership, return route allowlist, and server
  sanitization patterns.
- Other category tools already have a dialog entry point; they need copy/selection
  alignment more than a new endpoint.

### Lazy Category Loading And Collapse Reset Are Established

Closest analog: `cleanup-categories.js` and `WorkspaceController.senderCategory`.

Current open/collapse pattern:

```js
category.addEventListener("toggle", () => {
    const content = category.querySelector(".category-page-content");
    const guidance = category.querySelector(".category-load-guidance");
    if (!(content instanceof HTMLElement)) return;
    if (!category.open) {
        content.replaceChildren();
        if (guidance instanceof HTMLElement) guidance.replaceChildren();
        category.dataset.loadedCount = "0";
        return;
    }
    if (!content.hasChildNodes()) loadInitialCategory(category, content);
});
```

Sender category route:

```java
@GetMapping("/senders/categories/{categoryKey}")
public String senderCategory(...) {
    ...
    var categoryPage = senderQueryService.categoryPage(
        workspace.selectedAccountIds(), query, analyticsScope,
        categoryKey, offset, loadAll);
    ...
    return "fragments/senders-results-frag :: categoryChunk";
}
```

Planner guidance:

- Keep closed-by-default categories and reset-on-collapse behavior.
- Keep 5-row chunks, `Load more`, and `Load all` thresholds.
- Ensure Unread's `data-category-url` pattern and Senders' default URL builder
  stay consistent if JS is generalized.

### Exact-ID Staging Is The Contract To Preserve

Closest analog: `CleanupActionService` wraps existing materialization and command
persistence without provider execution.

Sender staging flow:

```java
List<SelectionLocator> locators =
    normalizedSenderLocators(request.rowKeys(), authorizedAccountIds);
SelectionRequest selection = new SelectionRequest(
    SelectionSurface.SENDERS,
    SelectionMode.INDIVIDUAL,
    request.senderQuery(),
    null,
    locators.stream()
        .map(locator -> DecisionRowKey.sender(locator.accountId(), locator.target()))
        .toList(),
    request.action(),
    false,
    null);
prepared = previews.prepareCleanupStages(
    ownerKey, authorizedAccountIds, selection, locators);
```

Unread staging flow:

```java
List<UnreadSenderLocator> locators =
    normalizedUnreadLocators(request.rowKeys(), authorizedAccountIds);
prepared = previews.prepareUnreadCleanupStages(
    ownerKey, authorizedAccountIds, request.unreadQuery(),
    request.action(), locators);
```

Delete warning copy:

```java
public static final String TRASH_RECOVERY_NOTICE =
    "Messages moved to Gmail Trash can typically be restored for up to 30 days.";
```

Planner guidance:

- Phase 16 must not change action scope derivation to category totals, checkbox
  counts, sender counts, or estimated sizes.
- Keep `requestToken`, CSRF, canonical query hidden fields, delete acknowledgement,
  duplicate/reuse behavior, and no provider calls before Review approval.
- Use existing `StageResult` for UI reconciliation only.

### Unread Predicate Is Centralized And Must Stay Inbox-Only

Closest analog: `DecisionSurfaceSql.unread`.

Current base predicate:

```sql
WHERE m.account_id IN (:account_ids)
  AND m.unread = true
  AND %s
```

Dialect-aware label predicate:

```java
static String hasLabel(String column, String label, boolean h2) {
    return h2
        ? "ARRAY_CONTAINS(" + column + ", '" + label + "')"
        : "'" + label + "'=ANY(" + column + ")";
}
```

Planner guidance:

- Copy changes must not alter `m.unread = true` plus `INBOX` behavior.
- Existing `Phase15UnreadSemanticsTest.everyUnreadReadUsesInboxAndUnreadTogether`
  is the right service-level guard to preserve.
- Removing stale explanatory notes is UI cleanup, not semantics removal.

### CSS Should Reuse Tokens While Reducing Heavy Cards

Closest analog: existing `workspace.css` tokens and cleanup selectors.

Shared tokens:

```css
:root {
    --tm-bg: #f4f6fb;
    --tm-surface: #ffffff;
    --tm-border: #d9e0ee;
    --tm-primary: #3f5cff;
    --tm-attention: #ea580c;
    --shell-rail: 248px;
    --content-max: 1180px;
}
```

Current heavy category style:

```css
.cleanup-category {
    position: relative;
    overflow: hidden;
    border: 1px solid var(--tm-border);
    border-radius: var(--radius-md);
    background: linear-gradient(90deg, var(--category-tint), var(--tm-surface) 28%);
    box-shadow: var(--shadow-1);
}
```

Responsive/touch guard:

```css
@media (max-width: 720px) {
    .rail-navigation a,
    select,
    button,
    .primary-button { min-height: 44px; font-size: 14px; }
}
```

Planner guidance:

- Keep tokens, `--shell-rail: 248px`, focus styles, and 44px target guarantees.
- Reduce category pill/card heaviness where mocks show compact rows.
- Keep `.cleanup-row`, `.cleanup-row-head`, `[data-label]::before`, and responsive
  single-column behavior as the closest local row pattern.
- Remove or orphan-check `.cleanup-action-result` styling if the visible panel is
  removed from cleanup pages.

## Per-File Pattern Guidance

### `templates/fragments/workspace-rail.html`

Role: shared navigation and account selector.

Closest analog: same file plus `Phase14WorkspaceFidelityTest.sharedRailSolelyOwnsPrimaryNavigationAndAccountSelection`.

Data flow:

1. Every page calls `workspace-rail :: rail(${page.workspace})` or equivalent.
2. `WorkspaceModelService` supplies labels, route, counts, selected account.
3. Template renders visual navigation.

Required pattern:

- Text labels exactly: `Bulk Archive/Delete`, `Unread Only`, `Bulk Unsubscribe`,
  `Review`, `Analytics`.
- Remove visible `.rail-nav-badge`.
- Keep account selector and Add new account option in rail only.

Test impact:

- Change `.contains("class=\"rail-nav-badge\"")` to `.doesNotContain("rail-nav-badge")`.
- Retain tests for route labels, one account selector, CSRF logout controls, and no
  standalone queue footer.

### `templates/senders.html`

Role: Bulk Archive/Delete page shell.

Closest analog: current `unread.html` shares the same shell pattern; mock reference
is `by-sender-compact-suite.html`.

Current status inclusion:

```html
<div th:replace="~{fragments/cleanup-action-result-frag :: status}"></div>
```

Required pattern:

- Keep rail-only account selection and right-aligned Settings/Expand controls.
- Remove persistent cleanup status fragment from page content.
- Keep direct exact staging form and delete dialog.
- Rename visible internal category-management copy in dialogs/buttons only if the
  server boundary can remain unchanged.

### `templates/unread.html`

Role: Unread Only page shell.

Closest analog: `senders.html` shared shell and `UnreadDecisionQueryService` for
semantics.

Current header copy:

```html
<p>Open a group of Inbox-unread mail and correct sender classification when needed.</p>
```

Required pattern:

- Replace helper copy with exactly `Tidy up your unread emails`.
- Remove persistent cleanup status fragment.
- Preserve `surface=UNREAD`, exact row keys, delete warning dialog, and Settings/
  Expand controls.

### `templates/fragments/senders-results-frag.html`

Role: sender category summaries and chunks.

Closest analog: same fragment's `categoryChunk`; CSS row grid already supports
table-like structure.

Required pattern:

- Render category title/description separately from right-side meta.
- Add visible `User generated` tag when `category.userGenerated`.
- Replace visible `Correct classification` clutter with mock-aligned category/type
  management affordance.
- Keep row forms posting to `/api/cleanup-actions`; do not add Selection Preview.

Backend-adjacent risk:

- `category.userGenerated` cannot become visible unless user-generated groups are
  emitted. Current service always sets it to `false`.

### `templates/fragments/unread-results-frag.html`

Role: unread category summaries and chunks.

Closest analog: `senders-results-frag.html` plus unread DTO totals.

Current stale notes to remove:

```html
<p class="indexed-snapshot-note">
    Counts and rows reflect the last completed metadata sync; provider changes appear after the next sync.
</p>
<p class="category-contract-note">
    Unread groups are not mutually exclusive. A sender can appear in more than one group.
</p>
```

Required pattern:

- Remove both notes from above the category list.
- Keep overlapping group semantics in service/tests.
- Render sender count and unread total as separate meta values.
- Keep lazy category bodies and exact row-key staging.

### `templates/fragments/cleanup-action-result-frag.html`

Role: currently visible status panel plus OOB count fragment.

Closest analog: action/result fragments elsewhere use `aria-live` and HTMX
fallbacks, but Phase 16 should avoid persistent cleanup status panels.

Planner options:

- Remove page inclusion and leave file only if some HTMX response still needs
  `reviewCount`.
- Split visible panel from OOB count if planner keeps OOB review count updates.
- Delete or stop using the visible `status` fragment if no remaining page consumes it.

Do not keep `Ready to stage for Review` visible on cleanup pages.

### `static/js/cleanup-categories.js`

Role: progressive enhancement and post-stage reconciliation.

Closest analog: current `reconcileCleanupResult` is the starting point.

Required pattern:

- Success handling must not early-return because `#cleanup-action-status` is absent.
- Keep staged row mutation by `data-sender-address`.
- Refresh request token after successful HTMX staging.
- Error handling needs a compact accessible target independent of the old panel.
- Delete dialog preparation should continue requiring at least one selected sender.

### `static/css/workspace.css`

Role: shared UI contract.

Closest analog: cleanup selectors around lines 986-1320 and responsive rules.

Required pattern:

- Keep rail/account truncation, responsive breakpoints, focus, and 44px target rules.
- Rework `.cleanup-category`, `summary`, `.category-count`/new meta classes,
  `.cleanup-row`, `.cleanup-action`, `.category-tools`, and staged state.
- Remove status-panel styles only if unused; otherwise scope them away from cleanup
  pages.

### `WorkspaceModelService`

Role: server facts for rail and account scope.

Closest analog: current guarded `queuedCount` and `inboxUnreadCount` lookup.

Required pattern:

- No behavioral change required for badge removal.
- Preserve unavailable-count handling via `null`.
- Do not remove Review/Unread count calculations because other logic/tests may rely
  on them.

### `CleanupActionService`

Role: exact staging and Review queue persistence.

Closest analog: current service is already the correct boundary.

Required pattern:

- Treat as guardrail, not UI work.
- Preserve `StageResult` JSON shape unless JS/test planning requires a small
  accessible error/state addition.
- Do not add immediate provider execution.

### `SenderDecisionQueryService`

Role: sender groups and category pages.

Closest analog: `Phase15CategoryPagingTest` proves summary/page behavior.

Required pattern:

- Existing `estimatedSize` and `senderCount` support header metrics.
- Planner must decide whether to add user-created category groups here or via a
  small collaborating service.
- Preserve bounded page querying and stable ordering.

### `UnreadDecisionQueryService`

Role: unread groups and row data.

Closest analog: `Phase15UnreadSemanticsTest` proves Inbox-only and overlapping
group semantics.

Required pattern:

- Existing `senderCount()` and `unreadCount()` support header metrics.
- UI copy changes must not make groups mutually exclusive.
- Direct mail sorting by unread volume is already local pattern:

```java
Comparator<UnreadSenderRow> volumeOrder =
    Comparator.comparingLong(UnreadSenderRow::unreadCount).reversed()
        .thenComparingLong(UnreadSenderRow::accountId)
        .thenComparing(UnreadSenderRow::senderAddress);
```

### `SenderCategoryService` And `SenderCategoryController`

Role: account-owned categories, assignment, correction.

Closest analog: existing ownership checks and strict parameter allowlists.

Required pattern:

- Reuse, do not duplicate, category creation and assignment.
- Keep server sanitization and ownership.
- If UI adds Move selected controls, ensure selected sender addresses reach
  `/api/sender-categories/organize` as `senderAddress` values.

### `DecisionSurfaceQueryRepository` And `DecisionSurfaceSql`

Role: summary, row, count, and predicate source of truth.

Closest analog: current sender/unread methods.

Required pattern:

- Preserve `senderCategorySummaries` and `senderCategoryRows` for bounded sender
  category loading.
- Preserve unread `countUnread` and `streamUnread` behavior for rail and category
  grouping.
- Avoid parallel handwritten Inbox predicates.
- If user-created category groups are added, prefer repository/service methods that
  use account-owned assignment tables while keeping predefined group predicates
  stable.

### `unsubscribe.html` And `unsubscribe-results-frag.html`

Role: audit-only visual fidelity for Bulk Unsubscribe.

Closest analog: current unsubscribe fragment is already an individual-sender
workflow:

```html
<table class="unsubscribe-sender-table">
  <thead>
  <tr>
    <th scope="col">Sender / domain</th>
    <th scope="col">Email count</th>
    <th scope="col">Read %</th>
    <th scope="col">Action</th>
    <th scope="col">More</th>
  </tr>
  </thead>
</table>
```

Required pattern:

- Audit visual shell, controls, row density, and no-export state.
- Do not introduce grouped categories.
- Preserve unsubscribe safety, candidate inspection, honest outcome states, and
  stats/action HTMX targets.

## Test Patterns

### Static Template Tests

Closest analog: `Phase14WorkspaceFidelityTest` and `Phase13WorkspaceResponsiveTest`.

Use source reads for stable structural contracts:

```java
String rail = Files.readString(FRAGMENTS.resolve("workspace-rail.html"));
assertThat(rail)
    .contains("Bulk Archive/Delete", "Unread Only", "Bulk Unsubscribe", "Review", "Analytics")
    .doesNotContain("rail-nav-badge");
```

Recommended Phase 16 assertions:

- Rail labels present; `.rail-nav-badge` absent.
- `unread.html` contains `Tidy up your unread emails`.
- `unread-results-frag.html` does not contain the two stale notes.
- `senders.html` and `unread.html` do not include `cleanup-action-result-frag :: status`.
- Cleanup fragments do not contain visible `Correct classification` copy.
- No workspace templates contain `.csv`, `snapshot`, `download`, `workspace-download`,
  `analytics-download`, or `Export`.

### MockMvc Web Tests

Closest analog: `Phase10SenderUnreadWebTest` and `Phase15WorkspaceWebTest`.

Use authenticated page/fragment rendering to prove behavior with real DTOs:

```java
String body = mockMvc.perform(get("/unread")
        .param("account", account.getId().toString())
        .with(authentication(auth())))
    .andExpect(status().isOk())
    .andReturn().getResponse().getContentAsString();
```

Recommended Phase 16 assertions:

- Closed category summaries render metrics but not all lazy rows.
- Category chunks still return 5 rows and load controls.
- Row forms still include `data-cleanup-stage`, `surface`, `rowKey`, `requestToken`,
  and CSRF.
- No Selection Preview redirects/forms are reintroduced.
- User-generated category fixtures render `User generated` if service support is
  added.

### Service/Repository Guardrails To Preserve

Keep these existing test patterns unchanged where possible:

- `Phase15UnreadSemanticsTest.everyUnreadReadUsesInboxAndUnreadTogether`: archived
  unread All Mail must stay excluded.
- `Phase15StagingTest`: exact scopes, duplicate reuse, ownership, delete
  acknowledgement, and no provider calls.
- `Phase15CategoryPagingTest`: 5/+5/load-all boundary and 1,001 guard.
- No-export template scans from Phase 13/14.

## Planner Risks And Decisions

1. User-generated categories are the only likely non-trivial backend-adjacent item.
   The DTO and persistence exist, but sender category groups currently do not emit
   user-created categories or set `userGenerated=true`.
2. Removing the status panel requires JS accessibility planning. Success can be
   row/button-only, but errors still need a live region or row/form target.
3. Rail badge removal is a template/test inversion, not a service deletion.
4. Unread copy cleanup must not remove or weaken Inbox-only unread semantics.
5. Bulk Unsubscribe should remain an audit/remediation surface only; grouping it
   would conflict with the Build Notes.

## Pattern Mapping Complete
