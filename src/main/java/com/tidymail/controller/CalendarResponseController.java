package com.tidymail.controller;

import com.tidymail.service.CalendarResponseService;
import com.tidymail.service.OwnedAccountScopeService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/dashboard/api/messages")
public class CalendarResponseController {
    private final CalendarResponseService calendar;
    private final OwnedAccountScopeService accounts;
    public CalendarResponseController(CalendarResponseService calendar,OwnedAccountScopeService accounts) { this.calendar=calendar;this.accounts=accounts; }
    @PostMapping("/{message}/calendar")
    public CalendarResponseService.Snapshot inspect(@PathVariable UUID message,OAuth2AuthenticationToken auth,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        if(auth==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sign in to continue");
        return calendar.inspect(accounts.resolve(auth,"current").getFirst(),message);
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> error(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",Objects.toString(e.getReason(),"Calendar request failed")));
    }
}
