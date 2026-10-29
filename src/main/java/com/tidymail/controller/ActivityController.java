package com.tidymail.controller;

import com.tidymail.dto.ActivityPageModel;
import com.tidymail.dto.WorkspaceRoute;
import com.tidymail.service.ActivityQueryService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Renders the Activity page, which shows the user a live view of what
 * TidyMail has been doing for a mailbox: sync progress, queued/running
 * cleanup commands, and recent results.
 *
 * <p>Product functionality supported: the <em>Activity</em> workspace tab.
 * {@code activity()} serves the full page and {@code status()} serves the
 * htmx-refreshed status fragment that keeps the page's status panel current
 * without a full reload.</p>
 */
@Controller
public class ActivityController {

    @org.springframework.beans.factory.annotation.Value("${app.mvp.enabled:true}")
    private boolean mvpEnabled;
    private final ActivityQueryService activity;

    public ActivityController(ActivityQueryService activity) {
        this.activity = activity;
    }

    /**
     * Serves the full Activity page for the selected mailbox (or the user's
     * current account when none is specified).
     */
    @GetMapping("/activity")
    public String activity(
            @RequestParam(name = "account", required = false) String account,
            OAuth2AuthenticationToken authentication,
            Model model) {
        ActivityPageModel page = activity.pageModel(mvpEnabled ? "current" : account, authentication);
        model.addAttribute("page", page);
        model.addAttribute("mvpEnabled", mvpEnabled);
        return "activity";
    }

    /**
     * Serves just the status panel fragment (workspace, sync/command status,
     * provider name) for the Activity page. Used by the page's auto-refresh
     * (htmx polling) so the status stays live without re-rendering the whole
     * page.
     */
    @GetMapping("/activity/status")
    public String status(
            @RequestParam(name = "account", required = false) String account,
            OAuth2AuthenticationToken authentication,
            Model model) {
        ActivityPageModel page = activity.pageModel(mvpEnabled ? "current" : account, authentication);
        model.addAttribute("mvpEnabled", mvpEnabled);
        model.addAttribute("workspace", page.workspace());
        model.addAttribute("status", page.status());
        model.addAttribute("providerName", page.providerName());
        return "fragments/activity-status :: status";
    }
}
