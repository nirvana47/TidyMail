package com.tidymail.rules;

import com.tidymail.dto.DashboardFilter;
import com.tidymail.repository.DashboardRepository;
import com.tidymail.service.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Another entry point to the ordinary manual-filing preview, never an automatic mutation. */
@Service
public class RuleExistingMailService {
    private final AutomationRuleService rules;
    private final DashboardRepository dashboard;
    private final DashboardPreviewService previews;
    public RuleExistingMailService(AutomationRuleService rules,DashboardRepository dashboard,DashboardPreviewService previews) {
        this.rules=rules;this.dashboard=dashboard;this.previews=previews;
    }
    public record Matches(int total,int inbox,int unread,List<UUID> ids,DashboardFilter filter,MailDestinationService.Choice destination) {}
    public Matches matches(long account,UUID id,String scope) {
        return matches(account,id,scope,List.of());
    }
    public Matches matches(long account,UUID id,String scope,List<String> senders) {
        var rule=rules.get(account,id);
        if(!rule.state().equals("ACTIVE")||rule.pending()!=null||!rule.definition().organize())RuleStore.conflict("Enable an organizing rule before reviewing existing emails.");
        if(!Set.of("inbox","all","indexed").contains(scope))RuleDefinition.fail("Choose Inbox or All mailbox.");
        // Exactly the standard mailbox action scope: received mail, excluding Spam/Trash/Drafts.
        var filter=new DashboardFilter(scope,"","","","UTC",0).cleanupScope();
        var matching=rules.matching(account,id);
        if(!matching.deployable())RuleStore.conflict("Matching existing emails could not be checked. Try again before applying the rule.");
        var params=dashboard.parameters(account,filter);matching.params().forEach(params::addValue);
        if(!senders.isEmpty()){
            if(!rule.definition().senders().containsAll(senders))RuleDefinition.fail("Choose addresses already included in this rule.");
            params.addValue("senders",senders);
        }
        var rows=dashboard.jdbc().queryForList(dashboard.filtered(account,filter)+"""
            SELECT dashboard_message_id,unread,'INBOX'=ANY(labels) in_inbox FROM filtered
            WHERE lower(COALESCE(dashboard_from_address,from_address,'')) IN (:senders)
            AND ("""+matching.bucket()+"""
            )='match' ORDER BY received_at DESC NULLS LAST,id DESC LIMIT 10001
            """,params);
        if(rows.size()>10000)RuleStore.conflict("More than 10,000 emails match. Use the mailbox filters to review a smaller selection.");
        String destination=rules.destination(account,id);
        if(destination==null)RuleStore.conflict("The rule destination could not be confirmed. Refresh the rule before applying it to existing emails.");
        return new Matches(rows.size(),(int)rows.stream().filter(r->Boolean.TRUE.equals(r.get("in_inbox"))).count(),
            (int)rows.stream().filter(r->Boolean.TRUE.equals(r.get("unread"))).count(),rows.stream().map(r->(UUID)r.get("dashboard_message_id")).toList(),filter,
            new MailDestinationService.Choice("EXISTING",destination,rule.definition().destination(),rule.definition().skipInbox()));
    }
    public DashboardPreviewService.Preview preview(long account,UUID id,String scope) {
        return preview(account,id,scope,List.of());
    }
    public DashboardPreviewService.Preview preview(long account,UUID id,String scope,List<String> senders) {
        var matches=matches(account,id,scope,senders);
        if(matches.ids().isEmpty())RuleStore.conflict("No existing emails currently match in this scope.");
        return previews.create(account,new DashboardPreviewService.Request(matches.filter(),matches.ids(),"ORGANIZE",dashboard.revision(account),null,matches.destination()));
    }
}
