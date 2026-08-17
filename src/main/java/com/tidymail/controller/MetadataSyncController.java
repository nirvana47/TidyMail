package com.tidymail.controller;

import com.tidymail.job.IncrementalSyncJob;
import com.tidymail.service.OwnedAccountScopeService;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class MetadataSyncController {

    private static final Set<String> WORKSPACE_ROUTES = Set.of(
        "/senders", "/unread", "/unsubscribe", "/review", "/activity", "/analytics");

    private final OwnedAccountScopeService accountScopeService;
    private final IncrementalSyncJob incrementalSyncJob;

    public MetadataSyncController(
            OwnedAccountScopeService accountScopeService,
            IncrementalSyncJob incrementalSyncJob) {
        this.accountScopeService = accountScopeService;
        this.incrementalSyncJob = incrementalSyncJob;
    }

    @PostMapping("/api/metadata-sync")
    public String requestSync(
            @RequestParam(name = "account", required = false) String account,
            @RequestParam(name = "returnTo", required = false) String returnTo,
            OAuth2AuthenticationToken authentication) {
        String redirect = returnTo == null || returnTo.isBlank() ? "/senders" : returnTo;
        if (!WORKSPACE_ROUTES.contains(redirect)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsafe return route");
        }
        for (Long accountId : accountScopeService.resolve(authentication, account)) {
            incrementalSyncJob.requestSync(accountId);
        }
        return "redirect:" + redirect;
    }
}
