package com.tidymail.controller;

import com.tidymail.dto.ActivityPageModel;
import com.tidymail.dto.WorkspaceRoute;
import com.tidymail.service.ActivityQueryService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class ActivityController {

    private final ActivityQueryService activity;

    public ActivityController(ActivityQueryService activity) {
        this.activity = activity;
    }

    @GetMapping("/activity")
    public String activity(
            @RequestParam(name = "account", required = false) String account,
            OAuth2AuthenticationToken authentication,
            Model model) {
        ActivityPageModel page = activity.pageModel(account, authentication);
        model.addAttribute("page", page);
        return "activity";
    }

    @GetMapping("/activity/status")
    public String status(
            @RequestParam(name = "account", required = false) String account,
            OAuth2AuthenticationToken authentication,
            Model model) {
        ActivityPageModel page = activity.pageModel(account, authentication);
        model.addAttribute("workspace", page.workspace());
        model.addAttribute("status", page.status());
        model.addAttribute("providerName", page.providerName());
        return "fragments/activity-status :: status";
    }
}
