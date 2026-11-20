package com.tidymail.service;

import com.tidymail.rules.*;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.mail.outlook.OutlookMailService;
import java.util.*;
import org.springframework.stereotype.Service;

/** Shared read-only picker and explicit destination choices for rules and manual filing. */
@Service
public class MailDestinationService {
    public record Destination(String id,String name) {}
    public record Choice(String mode,String id,String name,boolean skipInbox) {}
    private final UserAccountRepository accounts;
    private final GmailRuleProvider gmail;
    private final OutlookRuleProvider outlook;
    private final OutlookMailService folders;
    public MailDestinationService(UserAccountRepository accounts,GmailRuleProvider gmail,OutlookRuleProvider outlook,OutlookMailService folders) {
        this.accounts=accounts;this.gmail=gmail;this.outlook=outlook;this.folders=folders;
    }
    public List<Destination> list(long account) {
        var a=accounts.findById(account).orElseThrow();
        return "google".equals(a.getProvider())?gmail.destinations(a).stream().map(d->new Destination(d.id(),d.name())).toList()
            :folders.inventory(a).stream().filter(f->f.role().equals("MAIL")).map(f->new Destination(f.id(),f.path()))
                .sorted(Comparator.comparing(Destination::name,String.CASE_INSENSITIVE_ORDER)).toList();
    }
    public Choice validate(long account,Choice choice) {
        if(choice==null || !Set.of("NEW","EXISTING").contains(Objects.toString(choice.mode(),"")))RuleDefinition.fail("Choose an existing destination or create a new one.");
        var a=accounts.findById(account).orElseThrow();
        boolean skip="google".equals(a.getProvider())&&choice.skipInbox();
        var inventory=list(account);
        if(choice.mode().equals("EXISTING")) {
            var selected=inventory.stream().filter(d->d.id().equals(choice.id())).findFirst().orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Select an available destination."));
            return new Choice("EXISTING",selected.id(),selected.name(),skip);
        }
        var checked=new RuleDefinition("Destination",List.of("sender@example.test"),"","","ORGANIZE",choice.name(),skip,"","NEW");
        if(inventory.stream().anyMatch(d->d.name().equalsIgnoreCase(checked.destination())))RuleDefinition.fail("This name already exists. Select it from existing destinations.");
        return new Choice("NEW","",checked.destination(),skip);
    }
    public String prepare(long account,Choice choice) {
        var a=accounts.findById(account).orElseThrow();RuleProvider p="google".equals(a.getProvider())?gmail:outlook;
        var d=new RuleDefinition("Manual filing",List.of("sender@example.test"),"","","ORGANIZE",choice.name(),choice.skipInbox(),choice.id(),choice.mode());
        // compile performs no writes; prepare is invoked only after a durable destination intent.
        return p.destinationId(p.prepare(a,p.compile(a,d,List.of())));
    }
}
