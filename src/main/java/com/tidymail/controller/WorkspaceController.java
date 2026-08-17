package com.tidymail.controller;

import com.tidymail.dto.DecisionPage;
import com.tidymail.dto.CleanupAction;
import com.tidymail.dto.ActionScopeRiskSummary;
import com.tidymail.dto.ActionScopeTargetSummary;
import com.tidymail.dto.SelectionRequest.PreviewAction;
import com.tidymail.dto.SenderDecisionRow;
import com.tidymail.dto.SenderCategoryOption;
import com.tidymail.dto.SenderPageModel;
import com.tidymail.dto.SenderQuery;
import com.tidymail.dto.AnalyticsSenderScope;
import com.tidymail.dto.UnreadDecisionRow;
import com.tidymail.dto.UnreadPageModel;
import com.tidymail.dto.UnreadQuery;
import com.tidymail.dto.UnsubscribeInspection;
import com.tidymail.dto.UnsubscribePageModel;
import com.tidymail.dto.UnsubscribeQuery;
import com.tidymail.dto.WorkspacePageModel;
import com.tidymail.dto.WorkspaceRoute;
import com.tidymail.dto.ReviewQuery;
import com.tidymail.dto.HistoryQuery;
import com.tidymail.service.SenderDecisionQueryService;
import com.tidymail.service.SenderCategoryService;
import com.tidymail.service.AnalyticsSenderScopeResolver;
import com.tidymail.service.UnreadDecisionQueryService;
import com.tidymail.service.UnsubscribeCandidateService;
import com.tidymail.service.WorkspaceModelService;
import com.tidymail.service.ActionConfirmationIntentService;
import com.tidymail.service.ActionScopeService;
import com.tidymail.service.ReviewActionService;
import com.tidymail.service.ActionHistoryService;
import com.tidymail.action.ActionType;
import com.tidymail.action.DecisionSource;
import com.tidymail.unsubscribe.UnsubscribeIntentService;
import com.tidymail.unsubscribe.UnsubscribeMethod;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.net.URI;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The main workspace controller — serves the core pages of the app: Senders,
 * Unread (Reply Zero), Unsubscribe, and Review, plus their htmx result
 * fragments and category drill-downs.
 *
 * <p>Product functionality supported: the primary <em>workspace tabs</em>.
 * Each page is built from a query (filters/sort/paging) resolved against the
 * user's accounts, and rendered either as a full page or a results fragment.
 * It also handles unsubscribe candidate inspection (with per-method
 * confirmation intents), manual unsubscribe navigation (mailto/provider
 * redirects), and the Review queue/history views.</p>
 */
@Controller
public class WorkspaceController {

    private final WorkspaceModelService workspaceModelService;
    private final SenderDecisionQueryService senderQueryService;
    private final UnreadDecisionQueryService unreadQueryService;
    private final UnsubscribeCandidateService unsubscribeCandidateService;
    private final UnsubscribeIntentService unsubscribeIntentService;
    private final ActionScopeService actionScopeService;
    private final ActionConfirmationIntentService actionConfirmations;
    private final ReviewActionService reviewActions;
    private final ActionHistoryService actionHistory;
    private final AnalyticsSenderScopeResolver analyticsSenderScopes;
    private final SenderCategoryService senderCategories;

    public WorkspaceController(
            WorkspaceModelService workspaceModelService,
            SenderDecisionQueryService senderQueryService,
            UnreadDecisionQueryService unreadQueryService,
            UnsubscribeCandidateService unsubscribeCandidateService,
            UnsubscribeIntentService unsubscribeIntentService,
            ActionScopeService actionScopeService,
            ActionConfirmationIntentService actionConfirmations,
            ReviewActionService reviewActions,
            ActionHistoryService actionHistory,
            AnalyticsSenderScopeResolver analyticsSenderScopes,
            SenderCategoryService senderCategories) {
        this.workspaceModelService = workspaceModelService;
        this.senderQueryService = senderQueryService;
        this.unreadQueryService = unreadQueryService;
        this.unsubscribeCandidateService = unsubscribeCandidateService;
        this.unsubscribeIntentService = unsubscribeIntentService;
        this.actionScopeService = actionScopeService;
        this.actionConfirmations = actionConfirmations;
        this.reviewActions = reviewActions;
        this.actionHistory = actionHistory;
        this.analyticsSenderScopes = analyticsSenderScopes;
        this.senderCategories = senderCategories;
    }

    /**
     * Renders the Senders page (sender aggregation dashboard) for the given
     * query.
     */
    @GetMapping("/senders")
    public String senders(
            @RequestParam MultiValueMap<String, String> parameters,
            OAuth2AuthenticationToken authentication,
            Model model) {
        SenderPageModel page = senderPage(parameters, authentication);
        model.addAttribute("page", page);
        model.addAttribute("categoryUrls", senderCategoryUrls(page));
        return "senders";
    }

    /**
     * Renders just the Senders results fragment (htmx), for in-place updates
     * of the sender list.
     */
    @GetMapping("/senders/results")
    public String senderResults(
            @RequestParam MultiValueMap<String, String> parameters,
            OAuth2AuthenticationToken authentication,
            Model model) {
        SenderPageModel page = senderPage(parameters, authentication);
        model.addAttribute("page", page);
        model.addAttribute("categoryUrls", senderCategoryUrls(page));
        return "fragments/senders-results-frag :: results";
    }

    /**
     * Renders a single sender category's rows (paginated), either as a fresh
     * category chunk or additional rows. Unread-prefixed keys delegate to the
     * unread category rendering.
     */
    @GetMapping("/senders/categories/{categoryKey}")
    public String senderCategory(
            @PathVariable String categoryKey,
            @RequestParam MultiValueMap<String, String> parameters,
            OAuth2AuthenticationToken authentication,
            HttpServletResponse response,
            Model model) {
        if (categoryKey.startsWith("unread-")) {
            return unreadCategory(
                categoryKey.substring("unread-".length()),
                parameters,
                authentication,
                response,
                model);
        }
        long offset = categoryOffset(parameters);
        boolean loadAll = categoryLoadAll(parameters);
        CleanupAction cleanupAction = cleanupAction(parameters, CleanupAction.ARCHIVE);
        SenderQuery query = SenderQuery.parse(withoutCategoryPaging(parameters));
        var resolvedScope = query.analyticsKey().isBlank()
            ? null : analyticsSenderScopes.resolve(authentication, query);
        AnalyticsSenderScope analyticsScope =
            resolvedScope == null ? null : resolvedScope.scope();
        WorkspacePageModel workspace = resolvedScope == null
            ? workspaceModelService.build(
                WorkspaceRoute.SENDERS, query.account(), authentication)
            : workspaceModelService.buildResolved(
                WorkspaceRoute.SENDERS, query.account(),
                resolvedScope.accountIds(), authentication);
        try {
            var categoryPage = senderQueryService.categoryPage(
                workspace.selectedAccountIds(), query, analyticsScope,
                categoryKey, offset, loadAll);
            model.addAttribute("categoryPage", categoryPage);
            model.addAttribute("canonicalQuery", query.canonicalParameters());
            model.addAttribute("cleanupAction", cleanupAction);
            model.addAttribute("nextCategoryUrl", categoryPage.hasMore()
                ? senderCategoryUrl(
                    query, cleanupAction, categoryKey, categoryPage.loadedCount(), false)
                : "");
            model.addAttribute("loadAllCategoryUrl", categoryPage.loadAllAvailable()
                ? senderCategoryUrl(
                    query, cleanupAction, categoryKey, categoryPage.loadedCount(), true)
                : "");
            return offset > 0
                ? "fragments/senders-results-frag :: categoryRows"
                : "fragments/senders-results-frag :: categoryChunk";
        } catch (ResponseStatusException error) {
            if (error.getStatusCode() != HttpStatus.UNPROCESSABLE_ENTITY) {
                throw error;
            }
            response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
            response.setHeader("HX-Retarget", "#category-error-" + categoryKey);
            response.setHeader("HX-Reswap", "innerHTML");
            model.addAttribute("categoryGuidance", error.getReason());
            return "fragments/senders-results-frag :: categoryError";
        }
    }

    /**
     * Renders a single unread (Reply Zero) category's rows, paginated, with
     * htmx error handling for stale/invalid category state.
     */
    private String unreadCategory(
            String categoryKey,
            MultiValueMap<String, String> parameters,
            OAuth2AuthenticationToken authentication,
            HttpServletResponse response,
            Model model) {
        long offset = categoryOffset(parameters);
        boolean loadAll = categoryLoadAll(parameters);
        CleanupAction cleanupAction = cleanupAction(parameters, CleanupAction.MARK_READ);
        UnreadQuery query = UnreadQuery.parse(withoutCategoryPaging(parameters));
        WorkspacePageModel workspace =
            workspaceModelService.build(WorkspaceRoute.UNREAD, query.account(), authentication);
        var groups = unreadQueryService.categories(workspace.selectedAccountIds(), query);
        try {
            var categoryPage =
                senderCategories.unreadCategoryPage(groups, categoryKey, offset, loadAll);
            model.addAttribute("categoryPage", categoryPage);
            model.addAttribute("canonicalQuery", query.canonicalParameters());
            model.addAttribute("cleanupAction", cleanupAction);
            model.addAttribute("nextCategoryUrl", categoryPage.hasMore()
                ? unreadCategoryUrl(
                    query, cleanupAction, categoryKey, categoryPage.loadedCount(), false)
                : "");
            model.addAttribute("loadAllCategoryUrl", categoryPage.loadAllAvailable()
                ? unreadCategoryUrl(
                    query, cleanupAction, categoryKey, categoryPage.loadedCount(), true)
                : "");
            return offset > 0
                ? "fragments/unread-results-frag :: categoryRows"
                : "fragments/unread-results-frag :: categoryChunk";
        } catch (ResponseStatusException error) {
            if (error.getStatusCode() != HttpStatus.UNPROCESSABLE_ENTITY) {
                throw error;
            }
            response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
            response.setHeader("HX-Retarget", "#category-error-" + categoryKey);
            response.setHeader("HX-Reswap", "innerHTML");
            model.addAttribute("categoryGuidance", error.getReason());
            return "fragments/unread-results-frag :: categoryError";
        }
    }

    /**
     * Renders the Unread (Reply Zero) page for the given query.
     */
    @GetMapping("/unread")
    public String unread(
            @RequestParam MultiValueMap<String, String> parameters,
            OAuth2AuthenticationToken authentication,
            Model model) {
        model.addAttribute("page", unreadPage(parameters, authentication));
        return "unread";
    }

    /**
     * Renders just the Unread results fragment (htmx).
     */
    @GetMapping("/unread/results")
    public String unreadResults(
            @RequestParam MultiValueMap<String, String> parameters,
            OAuth2AuthenticationToken authentication,
            Model model) {
        model.addAttribute("page", unreadPage(parameters, authentication));
        return "fragments/unread-results-frag :: results";
    }

    /**
     * Renders the Unsubscribe page (sender candidates) for the given query.
     */
    @GetMapping("/unsubscribe")
    public String unsubscribe(
            @RequestParam MultiValueMap<String, String> parameters,
            OAuth2AuthenticationToken authentication,
            Model model) {
        model.addAttribute("page", unsubscribePage(parameters, authentication));
        return "unsubscribe";
    }

    /**
     * Renders just the Unsubscribe results fragment (htmx).
     */
    @GetMapping("/unsubscribe/results")
    public String unsubscribeResults(
            @RequestParam MultiValueMap<String, String> parameters,
            OAuth2AuthenticationToken authentication,
            Model model) {
        model.addAttribute("page", unsubscribePage(parameters, authentication));
        return "fragments/unsubscribe-results-frag :: results";
    }

    /**
     * Inspects an unsubscribe candidate and prepares per-method confirmation
     * intents so the user can proceed with an automated or manual unsubscribe.
     */
    @GetMapping("/unsubscribe/candidates/{candidateKey}")
    public String unsubscribeCandidate(
            @PathVariable String candidateKey,
            @RequestParam MultiValueMap<String, String> parameters,
            @RequestHeader(name = "HX-Request", required = false) String htmx,
            OAuth2AuthenticationToken authentication,
            Model model) {
        UnsubscribePageModel page = unsubscribePage(parameters, authentication);
        UnsubscribeInspection inspection = unsubscribeCandidateService.inspect(
            page.workspace().selectedAccountIds(), candidateKey);
        Map<String, String> intents = new LinkedHashMap<>();
        for (UnsubscribeInspection.MethodFact method : inspection.methods()) {
            if (method.eligible() && method.method() != null) {
                UnsubscribeMethod selected = UnsubscribeMethod.valueOf(method.method());
                if (selected.automated()) {
                    var candidate = unsubscribeCandidateService.executionCandidate(
                        page.workspace().selectedAccountIds(), candidateKey, selected);
                    var scope = actionScopeService.createOrGet(
                        candidate.accountId(), ActionType.UNSUBSCRIBE.name(),
                        "UNSUBSCRIBE_CANDIDATE",
                        List.of(candidate.providerMessageId()),
                        new ActionScopeTargetSummary(
                            candidate.safeLabel(), candidate.senderAddress(),
                            null, 1),
                        new ActionScopeRiskSummary(0, 0, List.of()));
                    intents.put(method.method(), actionConfirmations.issue(
                        ActionCommandController.ownerKey(authentication),
                        ActionType.UNSUBSCRIBE, DecisionSource.UNSUBSCRIBE,
                        List.of(new ActionConfirmationIntentService.ScopeBinding(
                            candidate.accountId(), scope.scopeHash(),
                            candidate.candidateFingerprint(),
                            candidate.destinationFingerprint(),
                            selected.name()))));
                } else {
                    intents.put(method.method(), unsubscribeIntentService.issue(
                        page.workspace().selectedAccountIds(), candidateKey, selected));
                }
            }
        }
        inspection = inspection.withIntents(intents);
        model.addAttribute("page", page);
        model.addAttribute("inspection", inspection);
        return "true".equalsIgnoreCase(htmx)
            ? "fragments/unsubscribe-inspection-frag :: inspection"
            : "unsubscribe";
    }

    /**
     * Redirects the user to the mailto: unsubscribe link for a candidate's
     * chosen method (manual unsubscribe navigation).
     */
    @GetMapping("/unsubscribe/candidates/{candidateKey}/mailto/{methodIndex}")
    public ResponseEntity<Void> unsubscribeMailto(
            @PathVariable String candidateKey,
            @PathVariable int methodIndex,
            @RequestParam MultiValueMap<String, String> parameters,
            OAuth2AuthenticationToken authentication) {
        UnsubscribePageModel page = unsubscribePage(parameters, authentication);
        var navigation = unsubscribeCandidateService.manualNavigation(
            page.workspace().selectedAccountIds(),
            candidateKey,
            UnsubscribeCandidateService.NavigationKind.MAILTO,
            methodIndex);
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create(navigation.target()))
            .build();
    }

    /**
     * Redirects the user to the provider's unsubscribe flow for a candidate
     * (manual unsubscribe navigation).
     */
    @GetMapping("/unsubscribe/candidates/{candidateKey}/provider")
    public ResponseEntity<Void> unsubscribeProvider(
            @PathVariable String candidateKey,
            @RequestParam MultiValueMap<String, String> parameters,
            OAuth2AuthenticationToken authentication) {
        UnsubscribePageModel page = unsubscribePage(parameters, authentication);
        var navigation = unsubscribeCandidateService.manualNavigation(
            page.workspace().selectedAccountIds(),
            candidateKey,
            UnsubscribeCandidateService.NavigationKind.PROVIDER,
            0);
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create(navigation.target()))
            .build();
    }

    /**
     * Renders the Review page: either the action-history view or the staged
     * action queue, resolving &quot;current&quot; account to the user's
     * selected account.
     */
    @GetMapping("/review")
    public String review(
            @RequestParam MultiValueMap<String, String> parameters,
            OAuth2AuthenticationToken authentication,
            Model model) {
        if ("history".equalsIgnoreCase(parameters.getFirst("view"))) {
            HistoryQuery query = HistoryQuery.parse(parameters);
            WorkspacePageModel workspace = workspaceModelService.build(
                WorkspaceRoute.REVIEW, query.account(), authentication);
            if ("current".equalsIgnoreCase(query.account())
                    || "default".equalsIgnoreCase(query.account())) {
                query = new HistoryQuery(
                    workspace.selectedAccountQuery(), query.from(), query.to(),
                    query.action(), query.status(), query.target(), query.page());
            }
            model.addAttribute("workspace", workspace);
            model.addAttribute("history", actionHistory.history(workspace, query));
            return "review";
        }
        ReviewQuery query = ReviewQuery.parse(parameters);
        WorkspacePageModel workspace = workspaceModelService.build(
            WorkspaceRoute.REVIEW, query.account(), authentication);
        if ("current".equalsIgnoreCase(query.account())
                || "default".equalsIgnoreCase(query.account())) {
            query = new ReviewQuery(
                query.view(), workspace.selectedAccountQuery(), query.action(),
                query.state(), query.target(), query.page());
        }
        model.addAttribute("workspace", workspace);
        model.addAttribute("page", reviewActions.queue(
            ActionCommandController.ownerKey(authentication), workspace, query));
        return "review";
    }

    /**
     * Renders the workspace state fragment (active tab, account switcher)
     * used by the shared workspace chrome.
     */
    @GetMapping("/workspace/state")
    public String state(
            @RequestParam String route,
            @RequestParam(required = false) String account,
            OAuth2AuthenticationToken authentication,
            Model model) {
        WorkspaceRoute workspaceRoute = WorkspaceRoute.parse(route);
        model.addAttribute(
            "workspace", workspaceModelService.build(workspaceRoute, account, authentication));
        return "fragments/workspace-state :: state";
    }

    /**
     * Renders a workspace page for the given route and account.
     */
    private String render(
            WorkspaceRoute route,
            String account,
            OAuth2AuthenticationToken authentication,
            Model model) {
        model.addAttribute(
            "workspace", workspaceModelService.build(route, account, authentication));
        return route.value();
    }

    /**
     * Builds the Senders page model: resolves the query and analytics scope,
     * builds the workspace chrome, computes the category list, and assembles
     * the page's URLs and available actions.
     */
    private SenderPageModel senderPage(
            MultiValueMap<String, String> parameters,
            OAuth2AuthenticationToken authentication) {
        CleanupAction cleanupAction = cleanupAction(parameters, CleanupAction.ARCHIVE);
        SenderQuery query = SenderQuery.parse(withoutCleanupAction(parameters));
        var resolvedScope = query.analyticsKey().isBlank()
            ? null : analyticsSenderScopes.resolve(authentication, query);
        AnalyticsSenderScope analyticsScope =
            resolvedScope == null ? null : resolvedScope.scope();
        WorkspacePageModel workspace = resolvedScope == null
            ? workspaceModelService.build(
                WorkspaceRoute.SENDERS, query.account(), authentication)
            : workspaceModelService.buildResolved(
                WorkspaceRoute.SENDERS, query.account(),
                resolvedScope.accountIds(), authentication);
        if (query.page() != 0) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Invalid page query parameter");
        }
        DecisionPage<SenderDecisionRow> results = new DecisionPage<>(
            List.of(),
            0,
            new DecisionPage.Metrics(0, 0, 0, 0),
            0,
            SenderQuery.PAGE_SIZE,
            0,
            Map.of(),
            Map.of());
        var categories = senderQueryService.categories(
            workspace.selectedAccountIds(), query, analyticsScope);
        SenderQuery clear = SenderQuery.parse(Map.of("account", List.of(query.account())));
        return new SenderPageModel(
            workspace,
            workspace.unreadScopeCounts(),
            query,
            results,
            query.canonicalParameters(),
            "Window " + query.window().value() + ", type " + query.type().value()
                + ", risk " + query.risk().value() + ", protected "
                + query.protection().value(),
            url("/senders", query.canonicalParameters()),
            url("/senders", clear.canonicalParameters()),
            url("/senders", results.previousQuery()),
            url("/senders", results.nextQuery()),
            List.of(PreviewAction.ARCHIVE, PreviewAction.TRASH, PreviewAction.MARK_READ,
                PreviewAction.KEEP, PreviewAction.PROTECT, PreviewAction.REVIEW),
            analyticsScope == null ? null
                : "Showing the exact sender received from "
                    + analyticsScope.startInclusive() + " through "
                    + analyticsScope.endExclusive() + " UTC.",
            cleanupAction,
            categories,
            categoryOptions(authentication, workspace),
            workspace.selectedAccountIds().getFirst());
    }

    /**
     * Builds the Unread (Reply Zero) page model: resolves the query, builds
     * the workspace chrome, computes category snapshots, and assembles the
     * page's URLs and available actions.
     */
    private UnreadPageModel unreadPage(
            MultiValueMap<String, String> parameters,
            OAuth2AuthenticationToken authentication) {
        CleanupAction cleanupAction = cleanupAction(parameters, CleanupAction.MARK_READ);
        UnreadQuery query = UnreadQuery.parse(withoutCleanupAction(parameters));
        WorkspacePageModel workspace =
            workspaceModelService.build(WorkspaceRoute.UNREAD, query.account(), authentication);
        if (query.page() != 0) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Invalid page query parameter");
        }
        DecisionPage<UnreadDecisionRow> results = new DecisionPage<>(
            List.of(),
            0,
            new DecisionPage.Metrics(0, 0, 0, 0),
            0,
            UnreadQuery.PAGE_SIZE,
            0,
            Map.of(),
            Map.of());
        var categorySnapshots =
            unreadQueryService.categories(workspace.selectedAccountIds(), query);
        var categories = categorySnapshots.stream()
            .map(com.tidymail.dto.UnreadCategoryGroup::withoutRows)
            .toList();
        UnreadQuery clear = UnreadQuery.parse(Map.of("account", List.of(query.account())));
        return new UnreadPageModel(
            workspace,
            query,
            results,
            query.canonicalParameters(),
            "Type " + query.type().value() + ", age " + query.age().value()
                + ", waiting " + query.waiting().value() + ", provider "
                + query.provider().value(),
            url("/unread", query.canonicalParameters()),
            url("/unread", clear.canonicalParameters()),
            url("/unread", results.previousQuery()),
            url("/unread", results.nextQuery()),
            List.of(PreviewAction.ARCHIVE, PreviewAction.TRASH, PreviewAction.MARK_READ,
                PreviewAction.KEEP, PreviewAction.REVIEW),
            cleanupAction,
            categories,
            unreadCategoryUrls(query, cleanupAction, categorySnapshots),
            categoryOptions(authentication, workspace),
            workspace.selectedAccountIds().getFirst());
    }

    /**
     * Lists the sender-category options available to the user across their
     * selected accounts, for the category-assignment UI.
     */
    private List<SenderCategoryOption> categoryOptions(
            OAuth2AuthenticationToken authentication, WorkspacePageModel workspace) {
        String ownerKey = ActionCommandController.ownerKey(authentication);
        return workspace.selectedAccountIds().stream()
            .flatMap(accountId -> senderCategories.list(ownerKey, accountId).stream()
                .map(category -> new SenderCategoryOption(
                    category.getId(), accountId, category.getName())))
            .toList();
    }

    /**
     * Reads the {@code cleanupAction} query parameter, defaulting to the given
     * value and rejecting malformed input.
     */
    private static CleanupAction cleanupAction(
            MultiValueMap<String, String> parameters, CleanupAction defaultValue) {
        List<String> values = parameters.get("cleanupAction");
        if (values == null) return defaultValue;
        if (values.size() != 1 || values.getFirst() == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Invalid cleanupAction query parameter");
        }
        return CleanupAction.parse(values.getFirst(), defaultValue);
    }

    /**
     * Returns a copy of the parameters with the {@code cleanupAction} entry
     * removed, so the underlying query can be parsed cleanly.
     */
    private static MultiValueMap<String, String> withoutCleanupAction(
            MultiValueMap<String, String> parameters) {
        LinkedMultiValueMap<String, String> copy = new LinkedMultiValueMap<>(parameters);
        copy.remove("cleanupAction");
        return copy;
    }

    /**
     * Returns a copy of the parameters with category paging state
     * ({@code offset}, {@code loadAll}) removed.
     */
    private static MultiValueMap<String, String> withoutCategoryPaging(
            MultiValueMap<String, String> parameters) {
        LinkedMultiValueMap<String, String> copy =
            new LinkedMultiValueMap<>(withoutCleanupAction(parameters));
        copy.remove("offset");
        copy.remove("loadAll");
        return copy;
    }

    /**
     * Reads the category paging offset, defaulting to 0 and rejecting
     * non-numeric values.
     */
    private static long categoryOffset(MultiValueMap<String, String> parameters) {
        String value = singleCategoryParameter(parameters, "offset", "0");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Invalid category paging state");
        }
    }

    /**
     * Reads the {@code loadAll} flag for category paging, rejecting values
     * other than true/false.
     */
    private static boolean categoryLoadAll(MultiValueMap<String, String> parameters) {
        String value = singleCategoryParameter(parameters, "loadAll", "false");
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Invalid category paging state");
    }

    /**
     * Reads a single category-paging parameter, defaulting when absent and
     * rejecting duplicate values.
     */
    private static String singleCategoryParameter(
            MultiValueMap<String, String> parameters, String name, String defaultValue) {
        List<String> values = parameters.get(name);
        if (values == null) return defaultValue;
        if (values.size() != 1 || values.getFirst() == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Invalid category paging state");
        }
        return values.getFirst();
    }

    /**
     * Builds the &quot;load more&quot; URL map for each sender category.
     */
    private static Map<String, String> senderCategoryUrls(SenderPageModel page) {
        LinkedHashMap<String, String> urls = new LinkedHashMap<>();
        page.categories().forEach(category -> urls.put(
            category.key(),
            category.initialPage().hasMore()
                ? senderCategoryUrl(
                    page.query(), page.cleanupAction(), category.key(),
                    category.initialPage().loadedCount(), false)
                : ""));
        return Map.copyOf(urls);
    }

    /**
     * Builds the URL for paging/loading a sender category, preserving the
     * query and cleanup action.
     */
    private static String senderCategoryUrl(
            SenderQuery query,
            CleanupAction cleanupAction,
            String categoryKey,
            long offset,
            boolean loadAll) {
        LinkedHashMap<String, String> parameters =
            new LinkedHashMap<>(query.resetPage().canonicalParameters());
        parameters.put("cleanupAction", cleanupAction.parameterValue());
        parameters.put("offset", Long.toString(offset));
        parameters.put("loadAll", Boolean.toString(loadAll));
        return url("/senders/categories/" + categoryKey, parameters);
    }

    /**
     * Builds the URL map for each unread category.
     */
    private static Map<String, String> unreadCategoryUrls(
            UnreadQuery query,
            CleanupAction cleanupAction,
            List<com.tidymail.dto.UnreadCategoryGroup> categories) {
        LinkedHashMap<String, String> urls = new LinkedHashMap<>();
        categories.forEach(category -> urls.put(
            category.key(),
            unreadCategoryUrl(query, cleanupAction, category.key(), 0, false)));
        return Map.copyOf(urls);
    }

    /**
     * Builds the URL for paging/loading an unread category.
     */
    private static String unreadCategoryUrl(
            UnreadQuery query,
            CleanupAction cleanupAction,
            String categoryKey,
            long offset,
            boolean loadAll) {
        LinkedHashMap<String, String> parameters =
            new LinkedHashMap<>(query.resetPage().canonicalParameters());
        parameters.put("cleanupAction", cleanupAction.parameterValue());
        parameters.put("offset", Long.toString(offset));
        parameters.put("loadAll", Boolean.toString(loadAll));
        return url("/senders/categories/unread-" + categoryKey, parameters);
    }

    /**
     * Builds the Unsubscribe page model: resolves the query, builds the
     * workspace chrome, queries the unsubscribe candidates, and assembles the
     * page's URLs and sort label.
     */
    private UnsubscribePageModel unsubscribePage(
            MultiValueMap<String, String> parameters,
            OAuth2AuthenticationToken authentication) {
        UnsubscribeQuery query = UnsubscribeQuery.parse(parameters);
        WorkspacePageModel workspace = workspaceModelService.build(
            WorkspaceRoute.UNSUBSCRIBE, query.account(), authentication);
        DecisionPage<com.tidymail.dto.UnsubscribeCandidateRow> results =
            unsubscribeCandidateService.query(workspace.selectedAccountIds(), query);
        requireExistingPage(query.page(), results.totalPages());
        UnsubscribeQuery clear = UnsubscribeQuery.parse(
            Map.of("account", List.of(query.account())));
        return new UnsubscribePageModel(
            workspace,
            query,
            results,
            query.canonicalParameters(),
            "Category " + query.category().value() + ", exact range "
                + query.from() + " to " + query.to() + ", default "
                + query.defaultAction().value(),
            unsubscribeSortLabel(query.sort()),
            url("/unsubscribe", query.canonicalParameters()),
            url("/unsubscribe", clear.canonicalParameters()),
            url("/unsubscribe", results.previousQuery()),
            url("/unsubscribe", results.nextQuery()));
    }

    /**
     * Maps an unsubscribe sort key to its human-readable label.
     */
    private static String unsubscribeSortLabel(UnsubscribeQuery.Sort sort) {
        return switch (sort) {
            case VOLUME_DESC -> "Email count, highest first";
            case READ_DESC -> "Read percentage, highest first";
            case CONFIDENCE_DESC -> "Confidence, highest first";
            case METHOD -> "Method";
            case NEWEST -> "Newest received";
            case OLDEST -> "Oldest received";
            case REVIEW -> "Review need";
            case SENDER -> "Sender";
            case STATE -> "Latest outcome";
        };
    }

    /**
     * Builds a URL from a path and query parameters, or empty when there are
     * none.
     */
    private static String url(String path, Map<String, String> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "";
        }
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(path);
        parameters.forEach(builder::queryParam);
        return builder.build().encode().toUriString();
    }

    /**
     * Rejects a requested page that is beyond the available total pages.
     */
    private static void requireExistingPage(int page, int totalPages) {
        if (page > 0 && page >= totalPages) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Invalid page query parameter");
        }
    }
}
