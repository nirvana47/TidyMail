package com.tidymail.controller;

import com.tidymail.dto.UnsubscribeQuery;
import com.tidymail.dto.WorkspaceRoute;
import com.tidymail.service.UnsubscribeStatsService;
import com.tidymail.service.WorkspaceModelService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class UnsubscribeStatsController {
    private final WorkspaceModelService workspace;
    private final UnsubscribeStatsService stats;

    public UnsubscribeStatsController(
            WorkspaceModelService workspace,
            UnsubscribeStatsService stats) {
        this.workspace = workspace;
        this.stats = stats;
    }

    @GetMapping("/unsubscribe/senders/{senderRowKey}/stats")
    public String stats(
            @PathVariable String senderRowKey,
            @RequestParam MultiValueMap<String, String> parameters,
            @RequestHeader(name = "HX-Request", required = false) String htmx,
            OAuth2AuthenticationToken authentication,
            Model model) {
        UnsubscribeQuery query = UnsubscribeQuery.parse(parameters);
        var page = workspace.build(WorkspaceRoute.UNSUBSCRIBE, query.account(), authentication);
        model.addAttribute("workspace", page);
        model.addAttribute(
            "emailStats", stats.model(page.selectedAccountIds(), query, senderRowKey));
        return "true".equalsIgnoreCase(htmx)
            ? "fragments/email-stats-modal-frag :: modal"
            : "unsubscribe-stats";
    }
}
