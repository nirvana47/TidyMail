package com.tidymail.controller;

import com.tidymail.dto.DecisionPage;
import com.tidymail.dto.SenderDecisionRow;
import com.tidymail.dto.SenderCategoryOption;
import com.tidymail.dto.SenderPageModel;
import com.tidymail.dto.SenderQuery;
import com.tidymail.dto.AnalyticsSenderScope;
import com.tidymail.dto.UnreadDecisionRow;
import com.tidymail.dto.UnreadPageModel;
import com.tidymail.dto.UnreadQuery;
import com.tidymail.dto.WorkspacePageModel;
import com.tidymail.dto.WorkspaceRoute;
import com.tidymail.dto.HistoryQuery;
import com.tidymail.service.SenderDecisionQueryService;
import com.tidymail.service.SenderCategoryService;
import com.tidymail.service.AnalyticsSenderScopeResolver;
import com.tidymail.service.UnreadDecisionQueryService;
import com.tidymail.service.WorkspaceModelService;
import com.tidymail.service.ActionHistoryService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The main workspace controller — serves the core pages of the app: Senders,
 * Unread and Review, plus their htmx result
 * fragments and category drill-downs.
 *
 * <p>
 * Product functionality supported: the primary <em>workspace tabs</em>.
 * Each page is built from a query (filters/sort/paging) resolved against the
 * user's accounts, and rendered either as a full page or a results fragment.
 * It also reads retained legacy action history.
 * </p>
 */
@Controller
public class WorkspaceController {

    private final WorkspaceModelService workspaceModelService;
    private final SenderDecisionQueryService senderQueryService;
    private final UnreadDecisionQueryService unreadQueryService;
    private final ActionHistoryService actionHistory;
    private final AnalyticsSenderScopeResolver analyticsSenderScopes;
    private final SenderCategoryService senderCategories;

    public WorkspaceController(
            WorkspaceModelService workspaceModelService,
            SenderDecisionQueryService senderQueryService,
            UnreadDecisionQueryService unreadQueryService,
            ActionHistoryService actionHistory,
            AnalyticsSenderScopeResolver analyticsSenderScopes,
            SenderCategoryService senderCategories) {
        this.workspaceModelService = workspaceModelService;
        this.senderQueryService = senderQueryService;
        this.unreadQueryService = unreadQueryService;
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
        SenderQuery query = SenderQuery.parse(withoutCategoryPaging(parameters));
        var resolvedScope = query.analyticsKey().isBlank()
                ? null
                : analyticsSenderScopes.resolve(authentication, query);
        AnalyticsSenderScope analyticsScope = resolvedScope == null ? null : resolvedScope.scope();
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
            model.addAttribute("nextCategoryUrl", categoryPage.hasMore()
                    ? senderCategoryUrl(
                            query, categoryKey, categoryPage.loadedCount(), false)
                    : "");
            model.addAttribute("loadAllCategoryUrl", categoryPage.loadAllAvailable()
                    ? senderCategoryUrl(
                            query, categoryKey, categoryPage.loadedCount(), true)
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
     * Renders a single unread category's rows, paginated, with
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
        UnreadQuery query = UnreadQuery.parse(withoutCategoryPaging(parameters));
        WorkspacePageModel workspace = workspaceModelService.build(WorkspaceRoute.UNREAD, query.account(),
                authentication);
        var groups = unreadQueryService.categories(workspace.selectedAccountIds(), query);
        try {
            var categoryPage = senderCategories.unreadCategoryPage(groups, categoryKey, offset, loadAll);
            model.addAttribute("categoryPage", categoryPage);
            model.addAttribute("nextCategoryUrl", categoryPage.hasMore()
                    ? unreadCategoryUrl(
                            query, categoryKey, categoryPage.loadedCount(), false)
                    : "");
            model.addAttribute("loadAllCategoryUrl", categoryPage.loadAllAvailable()
                    ? unreadCategoryUrl(
                            query, categoryKey, categoryPage.loadedCount(), true)
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
     * Renders the Unread page for the given query.
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

    /** Reads retained legacy action history for the selected account. */
    @GetMapping("/review")
    public String review(
            @RequestParam MultiValueMap<String, String> parameters,
            OAuth2AuthenticationToken authentication,
            Model model) {
        if ("queued".equalsIgnoreCase(parameters.getFirst("view"))) {
            throw new ResponseStatusException(HttpStatus.GONE, "review_queue_retired");
        }
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
        SenderQuery query = SenderQuery.parse(parameters);
        var resolvedScope = query.analyticsKey().isBlank()
                ? null
                : analyticsSenderScopes.resolve(authentication, query);
        AnalyticsSenderScope analyticsScope = resolvedScope == null ? null : resolvedScope.scope();
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
                analyticsScope == null ? null
                        : "Showing the exact sender received from "
                                + analyticsScope.startInclusive() + " through "
                                + analyticsScope.endExclusive() + " UTC.",
                categories,
                categoryOptions(authentication, workspace),
                workspace.selectedAccountIds().getFirst());
    }

    /**
     * Builds the Unread page model: resolves the query, builds
     * the workspace chrome, computes category snapshots, and assembles the
     * page's URLs and available actions.
     */
    private UnreadPageModel unreadPage(
            MultiValueMap<String, String> parameters,
            OAuth2AuthenticationToken authentication) {
        UnreadQuery query = UnreadQuery.parse(parameters);
        WorkspacePageModel workspace = workspaceModelService.build(WorkspaceRoute.UNREAD, query.account(),
                authentication);
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
        var categorySnapshots = unreadQueryService.categories(workspace.selectedAccountIds(), query);
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
                categories,
                unreadCategoryUrls(query, categorySnapshots),
                categoryOptions(authentication, workspace),
                workspace.selectedAccountIds().getFirst());
    }

    /**
     * Lists the sender-category options available to the user across their
     * selected accounts, for the category-assignment UI.
     */
    private List<SenderCategoryOption> categoryOptions(
            OAuth2AuthenticationToken authentication, WorkspacePageModel workspace) {
        String ownerKey = SenderCategoryController.ownerKey(authentication);
        return workspace.selectedAccountIds().stream()
                .flatMap(accountId -> senderCategories.list(ownerKey, accountId).stream()
                        .map(category -> new SenderCategoryOption(
                                category.getId(), accountId, category.getName())))
                .toList();
    }

    /**
     * Returns a copy of the parameters with category paging state
     * ({@code offset}, {@code loadAll}) removed.
     */
    private static MultiValueMap<String, String> withoutCategoryPaging(
            MultiValueMap<String, String> parameters) {
        LinkedMultiValueMap<String, String> copy = new LinkedMultiValueMap<>(new LinkedMultiValueMap<>(parameters));
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
        if ("true".equals(value))
            return true;
        if ("false".equals(value))
            return false;
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
        if (values == null)
            return defaultValue;
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
                                page.query(), category.key(),
                                category.initialPage().loadedCount(), false)
                        : ""));
        return Map.copyOf(urls);
    }

    /**
     * Builds the URL for paging/loading a sender category, preserving the
     * query.
     */
    private static String senderCategoryUrl(
            SenderQuery query,
            String categoryKey,
            long offset,
            boolean loadAll) {
        LinkedHashMap<String, String> parameters = new LinkedHashMap<>(query.resetPage().canonicalParameters());
        parameters.put("offset", Long.toString(offset));
        parameters.put("loadAll", Boolean.toString(loadAll));
        return url("/senders/categories/" + categoryKey, parameters);
    }

    /**
     * Builds the URL map for each unread category.
     */
    private static Map<String, String> unreadCategoryUrls(
            UnreadQuery query,
            List<com.tidymail.dto.UnreadCategoryGroup> categories) {
        LinkedHashMap<String, String> urls = new LinkedHashMap<>();
        categories.forEach(category -> urls.put(
                category.key(),
                unreadCategoryUrl(query, category.key(), 0, false)));
        return Map.copyOf(urls);
    }

    /**
     * Builds the URL for paging/loading an unread category.
     */
    private static String unreadCategoryUrl(
            UnreadQuery query,
            String categoryKey,
            long offset,
            boolean loadAll) {
        LinkedHashMap<String, String> parameters = new LinkedHashMap<>(query.resetPage().canonicalParameters());
        parameters.put("offset", Long.toString(offset));
        parameters.put("loadAll", Boolean.toString(loadAll));
        return url("/senders/categories/unread-" + categoryKey, parameters);
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
