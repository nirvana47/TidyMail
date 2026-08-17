package com.tidymail.controller;

import com.tidymail.dto.AnalyticsPageModel;
import com.tidymail.dto.AnalyticsSourcePage;
import com.tidymail.service.AnalyticsQueryService;
import com.tidymail.service.AnalyticsQueryService.ResolvedAnalyticsQuery;
import com.tidymail.service.AnalyticsQueryService.ResolvedAnalyticsSourceQuery;
import com.tidymail.service.AnalyticsService;
import com.tidymail.service.AnalyticsLinkService;
import com.tidymail.service.WorkspaceModelService;
import com.tidymail.dto.WorkspacePageModel;
import com.tidymail.dto.WorkspaceRoute;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AnalyticsController {
    private final AnalyticsQueryService queryService;
    private final AnalyticsService analyticsService;
    private final WorkspaceModelService workspaceModelService;
    private final AnalyticsLinkService linkService;

    @Autowired
    public AnalyticsController(
            AnalyticsQueryService queryService,
            AnalyticsService analyticsService,
            WorkspaceModelService workspaceModelService,
            AnalyticsLinkService linkService) {
        this.queryService = queryService;
        this.analyticsService = analyticsService;
        this.workspaceModelService = workspaceModelService;
        this.linkService = linkService;
    }

    @GetMapping("/analytics")
    public String analytics(
            @RequestParam MultiValueMap<String, String> parameters,
            OAuth2AuthenticationToken authentication,
            Model model) {
        ResolvedAnalyticsQuery resolved =
            queryService.resolve(queryService.parse(parameters), authentication);
        WorkspacePageModel workspace = workspaceModelService.buildResolved(
            WorkspaceRoute.ANALYTICS, resolved.query().account(),
            resolved.accountIds(), authentication);
        AnalyticsPageModel page = linkService.attach(
            analyticsService.dashboard(workspace, resolved.query()), authentication);
        model.addAttribute("page", page);
        return "analytics";
    }

    @GetMapping("/analytics/source")
    public String source(
            @RequestParam MultiValueMap<String, String> parameters,
            OAuth2AuthenticationToken authentication,
            Model model) {
        ResolvedAnalyticsSourceQuery resolved =
            queryService.resolve(queryService.parseSource(parameters), authentication);
        WorkspacePageModel workspace = workspaceModelService.buildResolved(
            WorkspaceRoute.ANALYTICS,
            resolved.query().analytics().account(),
            resolved.accountIds(),
            authentication);
        AnalyticsSourcePage page = analyticsService.source(workspace, resolved.query());
        model.addAttribute("workspace", workspace);
        model.addAttribute("page", page);
        return "analytics-source";
    }

}
