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
    private final RuleExistingMailService existingMail;
    private final com.tidymail.service.MailDestinationService destinations;private final AutomationRuleService rules;private final OwnedAccountScopeService scope;private final UserAccountRepository accounts;
    public AutomationRuleController(AutomationRuleService rules,OwnedAccountScopeService scope,UserAccountRepository accounts,com.tidymail.service.MailDestinationService destinations,RuleExistingMailService existingMail){this.existingMail=existingMail;this.destinations=destinations;this.rules=rules;this.scope=scope;this.accounts=accounts;}
    @ModelAttribute public void validateMailScope(@RequestParam(defaultValue="all") String mailScope){if(!Set.of("inbox","all","indexed").contains(mailScope))RuleDefinition.fail("Choose Inbox or All mailbox.");}
    @ModelAttribute public void noStore(HttpServletResponse r){r.setHeader("Cache-Control","no-store");}
    private long account(OAuth2AuthenticationToken auth){if(auth==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sign in to continue.");return scope.resolve(auth,"current").getFirst();}
    private long checked(OAuth2AuthenticationToken auth,long expected){long actual=account(auth);if(actual!=expected)throw new ResponseStatusException(HttpStatus.CONFLICT,"Mailbox changed. Return to Rules and review the selected mailbox.");return actual;}
    private void shell(long id,Model model){var a=accounts.findById(id).orElseThrow();model.addAttribute("account",id);model.addAttribute("email",a.getEmail());model.addAttribute("provider",a.getProvider());
        model.addAttribute("mailboxAccounts",accounts.findAllByOwnerKeyOrderByIdAsc(a.getOwnerKey()));}
    @GetMapping("/destinations") @ResponseBody public java.util.List<com.tidymail.service.MailDestinationService.Destination> destinations(OAuth2AuthenticationToken auth){return destinations.list(account(auth));}
    @GetMapping public String list(OAuth2AuthenticationToken auth,Model model){long a=account(auth);shell(a,model);model.addAttribute("data",rules.page(a,false));model.addAttribute("fingerprints",rules);return "rules";}
    private record DraftConfirmation(long account,List<AutomationRuleService.DraftTarget> targets) {}
    @GetMapping("/drafts/deletion-preview") @ResponseBody
    public Map<String,Object> draftDeletionPreview(@RequestParam long account,@RequestParam(required=false) UUID id,OAuth2AuthenticationToken auth,jakarta.servlet.http.HttpSession session){
        long a=checked(auth,account);var targets=rules.draftDeletionTargets(a,id);String token=UUID.randomUUID().toString();
        // One open confirmation per session; only this server-held snapshot may be committed.
        session.setAttribute("ruleDraftDeletion",Map.entry(token,new DraftConfirmation(a,targets)));
        return Map.of("token",token,"count",targets.size());
    }
    @PostMapping("/drafts/delete")
    public String deleteDrafts(@RequestParam long account,@RequestParam(defaultValue="") String confirmation,OAuth2AuthenticationToken auth,jakarta.servlet.http.HttpSession session,RedirectAttributes redirect){
        long a=checked(auth,account);
        Object saved=session.getAttribute("ruleDraftDeletion");
        if(!(saved instanceof Map.Entry<?,?> entry)||!entry.getKey().equals(confirmation)||!(entry.getValue() instanceof DraftConfirmation snapshot)||snapshot.account()!=a)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Reopen draft deletion to review the current drafts.");
        session.removeAttribute("ruleDraftDeletion");
        var result=rules.deleteDrafts(a,snapshot.targets());
        String notice="Deleted "+result.deleted()+" draft"+(result.deleted()==1?"":"s")+".";
        if(result.skipped()>0)notice+=" Skipped "+result.skipped()+" changed or already removed.";
        redirect.addFlashAttribute("notice",notice);
        return "redirect:/rules";
    }
    @GetMapping("/new") public String create(@RequestParam(defaultValue="") String sender,@RequestParam(defaultValue="ORGANIZE") String action,@RequestParam(defaultValue="false") boolean inbox,@RequestParam(defaultValue="all") String mailScope,OAuth2AuthenticationToken auth,Model model){long a=account(auth);shell(a,model);model.addAttribute("mailScope",mailScope);model.addAttribute("inbox",inbox);model.addAttribute("result",false);model.addAttribute("initialSender",sender);model.addAttribute("initialAction","ORGANIZE");return "rule-editor";}
    private List<String> senderAddresses(String senders){return Arrays.stream(senders.split("[,;\\n]")).map(String::strip).filter(v->!v.isBlank()).map(v->v.toLowerCase(Locale.ROOT)).distinct().toList();}
    @GetMapping("/add-senders") public String senderPicker(@RequestParam long account,@RequestParam String senders,@RequestParam(defaultValue="inbox") String mailScope,OAuth2AuthenticationToken auth,Model model){
        long a=checked(auth,account);shell(a,model);var addresses=senderAddresses(senders);
        new RuleDefinition("Sender selection",addresses,"","","READ");
        model.addAttribute("senders",String.join(", ",addresses));model.addAttribute("addresses",addresses);model.addAttribute("mailScope",mailScope);model.addAttribute("data",rules.page(a,false));return "rule-add-senders";
    }
    @PostMapping("/{id}/senders/review") public String reviewSenders(@PathVariable UUID id,@RequestParam long account,@RequestParam String senders,@RequestParam(defaultValue="inbox") String mailScope,OAuth2AuthenticationToken auth,Model model){
        long a=checked(auth,account);shell(a,model);model.addAttribute("senders",senders);model.addAttribute("mailScope",mailScope);model.addAttribute("addition",rules.reviewSenders(a,id,senderAddresses(senders)));return "rule-add-senders";
    }
    @PostMapping("/{id}/senders/add") public String addSenders(@PathVariable UUID id,@RequestParam long account,@RequestParam UUID preview,@RequestParam(defaultValue="false") boolean reviewedInventory,@RequestParam(defaultValue="inbox") String mailScope,OAuth2AuthenticationToken auth,Model model){
        long a=checked(auth,account);var before=rules.get(a,id);rules.addSenders(a,id,preview,reviewedInventory);var updated=rules.get(a,id);shell(a,model);model.addAttribute("mailScope",mailScope);model.addAttribute("updated",updated);
        model.addAttribute("added",updated.definition().senders().stream().filter(v->!before.definition().senders().contains(v)).toList());return "rule-add-senders";
    }
    @GetMapping("/{id}") public String edit(@PathVariable UUID id,@RequestParam(defaultValue="false") boolean preview,@RequestParam(defaultValue="false") boolean inbox,@RequestParam(defaultValue="false") boolean result,@RequestParam(defaultValue="all") String mailScope,OAuth2AuthenticationToken auth,Model model){long a=account(auth);shell(a,model);model.addAttribute("mailScope",mailScope);model.addAttribute("inbox",inbox);model.addAttribute("result",result);model.addAttribute("rule",rules.get(a,id));if(!preview&&!result)model.addAttribute("ruleResults",rules.results(a,id));if(preview)model.addAttribute("preview",rules.preview(a,id));
        if(result && rules.get(a,id).state().equals("ACTIVE") && rules.get(a,id).definition().organize()) {
            try{model.addAttribute("existingMail",existingMail.matches(a,id,mailScope));}
            catch(RuntimeException unavailable){model.addAttribute("existingMailError","Existing emails could not be checked. Review them from the mailbox when ready.");}
        }
        return "rule-editor";}
    @PostMapping("/save") public String save(@RequestParam long account,@RequestParam(required=false) UUID id,@RequestParam(defaultValue="0") long version,
            @RequestParam String name,@RequestParam String senders,@RequestParam(defaultValue="") List<String> subject,@RequestParam(defaultValue="") List<String> exceptSubject,
            @RequestParam String action,@RequestParam(defaultValue="") String destination,@RequestParam(defaultValue="") String destinationId,@RequestParam(defaultValue="NEW") String destinationMode,@RequestParam(defaultValue="false") boolean skipInbox,@RequestParam(defaultValue="false") boolean inbox,@RequestParam(defaultValue="all") String mailScope,OAuth2AuthenticationToken auth){long a=checked(auth,account);var d=new RuleDefinition(name,Arrays.stream(senders.split("[,;\\n]")).filter(s->!s.isBlank()).toList(),String.join("\n",subject),String.join("\n",exceptSubject),action,destination,"google".equals(accounts.findById(a).orElseThrow().getProvider())&&skipInbox,destinationId,destinationMode);
        if(d.organize()) {
            var choice=destinations.validate(a,new com.tidymail.service.MailDestinationService.Choice(d.destinationMode(),d.destinationId(),d.destination(),d.skipInbox()));
            d=new RuleDefinition(d.name(),d.senders(),d.subject(),d.exceptSubject(),d.action(),choice.name(),choice.skipInbox(),choice.id(),choice.mode());
        }
        UUID saved=rules.save(a,id,version,d);return "redirect:/rules/"+saved+"?preview=true&mailScope="+mailScope+(inbox?"&inbox=true":"");}
    @PostMapping("/{id}/interaction-check") @ResponseBody
    public Map<String,Object> checkInteraction(@PathVariable UUID id,@RequestParam long account,@RequestParam UUID preview,@RequestParam String remote,@RequestParam String fingerprint,OAuth2AuthenticationToken auth){
        return rules.checkInteraction(checked(auth,account),id,preview,remote,fingerprint);
    }
    @PostMapping("/{id}/existing-preview") @ResponseBody public com.tidymail.service.DashboardPreviewService.Preview existingPreview(@PathVariable UUID id,@RequestParam long account,@RequestParam(defaultValue="inbox") String mailScope,@RequestParam(defaultValue="") String senders,OAuth2AuthenticationToken auth){return existingMail.preview(checked(auth,account),id,mailScope,senderAddresses(senders));}
    @PostMapping("/{id}/results/refresh") public String refreshResults(@PathVariable UUID id,@RequestParam long account,@RequestParam(defaultValue="false") boolean inbox,OAuth2AuthenticationToken auth){rules.refreshResults(checked(auth,account),id);return "redirect:/rules/"+id+(inbox?"?inbox=true&result=true":"");}
    @PostMapping("/{id}/enable") public String enable(@PathVariable UUID id,@RequestParam long account,@RequestParam UUID preview,@RequestParam(defaultValue="false") boolean reviewedInventory,@RequestParam(defaultValue="false") boolean inbox,@RequestParam(defaultValue="all") String mailScope,OAuth2AuthenticationToken auth){rules.enable(checked(auth,account),id,preview,reviewedInventory);return "redirect:/rules/"+id+"?result=true&mailScope="+mailScope+(inbox?"&inbox=true":"");}
    @PostMapping("/{id}/{operation:pause|remove}") public String manage(@PathVariable UUID id,@PathVariable String operation,@RequestParam long account,OAuth2AuthenticationToken auth){rules.manage(checked(auth,account),id,operation);return "redirect:/rules";}
    @PostMapping("/{id}/resolve") public String resolve(@PathVariable UUID id,@RequestParam long account,OAuth2AuthenticationToken auth){rules.resolve(checked(auth,account),id);return "redirect:/rules";}
    @PostMapping("/refresh") public String refresh(@RequestParam long account,OAuth2AuthenticationToken auth){rules.refresh(checked(auth,account));return "redirect:/rules";}
    @PostMapping("/adopt") public String adopt(@RequestParam long account,@RequestParam String remote,@RequestParam String fingerprint,OAuth2AuthenticationToken auth){UUID id=rules.adopt(checked(auth,account),remote,fingerprint);return "redirect:/rules/"+id;}
    @ExceptionHandler(ResponseStatusException.class) public String error(ResponseStatusException e,RedirectAttributes redirect,jakarta.servlet.http.HttpServletRequest request,HttpServletResponse response,Model model){if("true".equals(request.getParameter("inbox")) || "XMLHttpRequest".equals(request.getHeader("X-Requested-With"))){response.setStatus(e.getStatusCode().value());model.addAttribute("error",Objects.toString(e.getReason(),"Rule request failed."));return "rule-inline-error";}redirect.addFlashAttribute("error",Objects.toString(e.getReason(),"Rule request failed."));return "redirect:/rules";}
    @ExceptionHandler(IllegalStateException.class) public String unavailable(IllegalStateException e,RedirectAttributes redirect,jakarta.servlet.http.HttpServletRequest request,HttpServletResponse response,Model model){if("true".equals(request.getParameter("inbox")) || "XMLHttpRequest".equals(request.getHeader("X-Requested-With"))){response.setStatus(503);model.addAttribute("error","The request could not be confirmed. Check Manage rules before retrying.");return "rule-inline-error";}redirect.addFlashAttribute("error","The rule request could not be completed. Refresh rules to check provider state before retrying.");return "redirect:/rules";}
}
