package com.tidymail.controller;

import com.tidymail.service.DashboardJobService;
import com.tidymail.service.OwnedAccountScopeService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/dashboard/api/jobs")
public class DashboardJobController {
    private final OwnedAccountScopeService accounts;
    private final DashboardJobService jobs;
    public DashboardJobController(OwnedAccountScopeService accounts,DashboardJobService jobs){this.accounts=accounts;this.jobs=jobs;}
    private long account(OAuth2AuthenticationToken auth) {
        if(auth==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sign in to continue");
        return accounts.resolve(auth,"current").getFirst();
    }
    @ModelAttribute public void noStore(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
    public record Confirmation(UUID preview) {}
    @PostMapping public DashboardJobService.Progress confirm(@RequestBody Confirmation request,OAuth2AuthenticationToken auth) {
        if(request.preview()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Review an exact selection first");
        return jobs.confirm(account(auth),request.preview());
    }
    @GetMapping public List<DashboardJobService.Progress> list(OAuth2AuthenticationToken auth){return jobs.list(account(auth));}
    @GetMapping("/{id}") public DashboardJobService.Progress get(@PathVariable UUID id,OAuth2AuthenticationToken auth){return jobs.get(account(auth),id);}
    @PostMapping("/{id}/cancel") public DashboardJobService.Progress cancel(@PathVariable UUID id,OAuth2AuthenticationToken auth){return jobs.cancel(account(auth),id);}
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<Map<String,String>> error(ResponseStatusException error){return ResponseEntity.status(error.getStatusCode()).body(Map.of("message",Objects.toString(error.getReason(),"Job request failed")));}
}
