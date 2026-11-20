package com.tidymail.controller;

import com.tidymail.rules.*;
import com.tidymail.service.OwnedAccountScopeService;
import com.tidymail.repository.UserAccountRepository;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Controller
@RequestMapping("/rules")
public class AutomationRuleController {
    private final com.tidymail.service.MailDestinationService destinations;private final RuleSuggestionService suggestions;private final AutomationRuleService rules;private final OwnedAccountScopeService scope;private final UserAccountRepository accounts;
    public AutomationRuleController(AutomationRuleService rules,OwnedAccountScopeService scope,UserAccountRepository accounts,RuleSuggestionService suggestions,com.tidymail.service.MailDestinationService destinations){this.destinations=destinations;this.suggestions=suggestions;this.rules=rules;this.scope=scope;this.accounts=accounts;}
    @ModelAttribute public void noStore(HttpServletResponse r){r.setHeader("Cache-Control","no-store");}
    private long account(OAuth2AuthenticationToken auth){if(auth==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sign in to continue.");return scope.resolve(auth,"current").getFirst();}
    private long checked(OAuth2AuthenticationToken auth,long expected){long actual=account(auth);if(actual!=expected)throw new ResponseStatusException(HttpStatus.CONFLICT,"Mailbox changed. Return to Rules and review the selected mailbox.");return actual;}
    private void shell(long id,Model model){var a=accounts.findById(id).orElseThrow();model.addAttribute("account",id);model.addAttribute("email",a.getEmail());model.addAttribute("provider",a.getProvider());
        model.addAttribute("mailboxAccounts",accounts.findAllByOwnerKeyOrderByIdAsc(a.getOwnerKey()));}
    @GetMapping("/destinations") @ResponseBody public java.util.List<com.tidymail.service.MailDestinationService.Destination> destinations(OAuth2AuthenticationToken auth){return destinations.list(account(auth));}
    @GetMapping public String list(OAuth2AuthenticationToken auth,Model model){long a=account(auth);shell(a,model);model.addAttribute("data",rules.page(a,false));model.addAttribute("fingerprints",rules);model.addAttribute("suggestions",suggestions.list(a));return "rules";}
    @GetMapping("/new") public String create(@RequestParam(defaultValue="") String sender,@RequestParam(defaultValue="ORGANIZE") String action,@RequestParam(defaultValue="false") boolean inbox,OAuth2AuthenticationToken auth,Model model){long a=account(auth);shell(a,model);model.addAttribute("inbox",inbox);model.addAttribute("result",false);model.addAttribute("initialSender",sender);model.addAttribute("initialAction","ORGANIZE");return "rule-editor";}
    @GetMapping("/{id}") public String edit(@PathVariable UUID id,@RequestParam(defaultValue="false") boolean preview,@RequestParam(defaultValue="false") boolean inbox,@RequestParam(defaultValue="false") boolean result,OAuth2AuthenticationToken auth,Model model){long a=account(auth);shell(a,model);model.addAttribute("inbox",inbox);model.addAttribute("result",result);model.addAttribute("rule",rules.get(a,id));model.addAttribute("ruleResults",rules.results(a,id));if(preview)model.addAttribute("preview",rules.preview(a,id));return "rule-editor";}
    @PostMapping("/save") public String save(@RequestParam long account,@RequestParam(required=false) UUID id,@RequestParam(defaultValue="0") long version,
            @RequestParam String name,@RequestParam String senders,@RequestParam(defaultValue="") List<String> subject,@RequestParam(defaultValue="") List<String> exceptSubject,
            @RequestParam String action,@RequestParam(defaultValue="") String destination,@RequestParam(defaultValue="") String destinationId,@RequestParam(defaultValue="NEW") String destinationMode,@RequestParam(defaultValue="false") boolean skipInbox,@RequestParam(defaultValue="false") boolean inbox,OAuth2AuthenticationToken auth){long a=checked(auth,account);var d=new RuleDefinition(name,Arrays.stream(senders.split("[,;\\n]")).filter(s->!s.isBlank()).toList(),String.join("\n",subject),String.join("\n",exceptSubject),action,destination,"google".equals(accounts.findById(a).orElseThrow().getProvider())&&skipInbox,destinationId,destinationMode);
        if(d.organize()) {
            var choice=destinations.validate(a,new com.tidymail.service.MailDestinationService.Choice(d.destinationMode(),d.destinationId(),d.destination(),d.skipInbox()));
            d=new RuleDefinition(d.name(),d.senders(),d.subject(),d.exceptSubject(),d.action(),choice.name(),choice.skipInbox(),choice.id(),choice.mode());
        }
        UUID saved=rules.save(a,id,version,d);return "redirect:/rules/"+saved+"?preview=true"+(inbox?"&inbox=true":"");}
    @PostMapping("/{id}/results/refresh") public String refreshResults(@PathVariable UUID id,@RequestParam long account,@RequestParam(defaultValue="false") boolean inbox,OAuth2AuthenticationToken auth){rules.refreshResults(checked(auth,account),id);return "redirect:/rules/"+id+(inbox?"?inbox=true&result=true":"");}
    @PostMapping("/{id}/enable") public String enable(@PathVariable UUID id,@RequestParam long account,@RequestParam UUID preview,@RequestParam(defaultValue="false") boolean reviewedInventory,@RequestParam(defaultValue="false") boolean inbox,OAuth2AuthenticationToken auth){rules.enable(checked(auth,account),id,preview,reviewedInventory);return inbox?"redirect:/rules/"+id+"?inbox=true&result=true":"redirect:/rules";}
    @PostMapping("/{id}/{operation:pause|remove}") public String manage(@PathVariable UUID id,@PathVariable String operation,@RequestParam long account,OAuth2AuthenticationToken auth){rules.manage(checked(auth,account),id,operation);return "redirect:/rules";}
    @PostMapping("/{id}/resolve") public String resolve(@PathVariable UUID id,@RequestParam long account,OAuth2AuthenticationToken auth){rules.resolve(checked(auth,account),id);return "redirect:/rules";}
    @PostMapping("/refresh") public String refresh(@RequestParam long account,OAuth2AuthenticationToken auth){rules.refresh(checked(auth,account));return "redirect:/rules";}
    @PostMapping("/suggestions/dismiss") public String dismiss(@RequestParam long account,@RequestParam String sender,@RequestParam String action,OAuth2AuthenticationToken auth){suggestions.dismiss(checked(auth,account),sender,action);return "redirect:/rules";}
    @PostMapping("/adopt") public String adopt(@RequestParam long account,@RequestParam String remote,@RequestParam String fingerprint,OAuth2AuthenticationToken auth){UUID id=rules.adopt(checked(auth,account),remote,fingerprint);return "redirect:/rules/"+id;}
    @ExceptionHandler(ResponseStatusException.class) public String error(ResponseStatusException e,RedirectAttributes redirect,jakarta.servlet.http.HttpServletRequest request,HttpServletResponse response,Model model){if("true".equals(request.getParameter("inbox")) || "XMLHttpRequest".equals(request.getHeader("X-Requested-With"))){response.setStatus(e.getStatusCode().value());model.addAttribute("error",Objects.toString(e.getReason(),"Rule request failed."));return "rule-inline-error";}redirect.addFlashAttribute("error",Objects.toString(e.getReason(),"Rule request failed."));return "redirect:/rules";}
    @ExceptionHandler(IllegalStateException.class) public String unavailable(IllegalStateException e,RedirectAttributes redirect,jakarta.servlet.http.HttpServletRequest request,HttpServletResponse response,Model model){if("true".equals(request.getParameter("inbox")) || "XMLHttpRequest".equals(request.getHeader("X-Requested-With"))){response.setStatus(503);model.addAttribute("error","The request could not be confirmed. Check Manage rules before retrying.");return "rule-inline-error";}redirect.addFlashAttribute("error","The rule request could not be completed. Refresh rules to check provider state before retrying.");return "redirect:/rules";}
}
