package com.tidymail.rules;

import com.fasterxml.jackson.databind.*;
import com.tidymail.entity.UserAccount;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.service.ProviderDeepLinkService;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AutomationRuleService {
    private final RuleResults results;
    private final RuleStore store;private final UserAccountRepository accounts;private final ProviderDeepLinkService links;
    private final Map<String,RuleProvider> providers=new HashMap<>();
    private final ConcurrentHashMap<Long,Object> locks=new ConcurrentHashMap<>();
    public AutomationRuleService(RuleStore store,UserAccountRepository accounts,ProviderDeepLinkService links,List<RuleProvider> providers,RuleResults results){
        this.results=results;this.store=store;this.accounts=accounts;this.links=links;providers.forEach(p->this.providers.put(p.provider(),p));
    }
    private UserAccount account(long id){return accounts.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Mailbox not found."));}
    private RuleProvider provider(UserAccount a){var p=providers.get(a.getProvider());if(p==null)throw new IllegalStateException("Rules are unavailable for this provider.");return p;}
    public boolean permitted(UserAccount a){var scope=provider(a).scope();return a.getScopes()!=null&&a.getScopes().stream().anyMatch(s->s.equals(scope)||s.equals("https://graph.microsoft.com/"+scope));}
    private void requirePermission(UserAccount a){if(!permitted(a))RuleStore.conflict("Connect rule permissions first. Drafts and local previews remain available.");}
    private <T>T serial(long account,Supplier<T> work){synchronized(locks.computeIfAbsent(account,k->new Object())){return work.get();}}
    public Map<String,Object> page(long id,boolean refresh){return serial(id,()->{
        var a=account(id);var p=provider(a);List<RuleProvider.NativeRule> nativeRules=List.of();String error="";
        if(permitted(a))try{nativeRules=p.list(a);if(refresh)reconcile(a,p,nativeRules);}catch(RuntimeException e){error="Mailbox rules could not be refreshed. No rule changes were confirmed. Check permissions or try again.";}
        var result=new LinkedHashMap<String,Object>();result.put("rules",store.list(id));result.put("nativeRules",nativeRules);
        result.put("permission",permitted(a));result.put("provider",a.getProvider());result.put("settingsUrl",p.settingsUrl());result.put("error",error);
        result.put("account",id);result.put("email",a.getEmail());
        result.put("events",store.jdbc().queryForList("SELECT e.event,e.occurred_at,r.definition->>'name' name FROM automation_rule_events e JOIN automation_rules r ON r.id=e.rule_id WHERE e.account_id=:account ORDER BY e.id DESC LIMIT 20",Map.of("account",id)));
        var bound=new HashSet<String>();store.list(id).forEach(r->{if(r.remoteId()!=null)bound.add(r.remoteId());});result.put("bound",bound);
        return result;
    });}
    public UUID save(long account,UUID id,long version,RuleDefinition d){return serial(account,()->{
        if(id!=null){var r=store.get(account,id);
            if(r.version()!=version)RuleStore.conflict("The rule changed. Reload before saving.");
            if(r.state().equals("PAUSED")&&r.remoteId()!=null&&r.pending()==null){
                manage(account,id,"remove");
                if(!store.get(account,id).state().equals("REMOVED"))RuleStore.conflict("Removing the paused native rule needs verification. Refresh before saving the replacement.");
                UUID replacement=store.save(account,null,0,d);
                // Retain the original Outlook slot so editing does not silently append the rule after unrelated rules.
                store.jdbc().update("UPDATE automation_rules SET remote_snapshot=CAST(:snapshot AS jsonb) WHERE id=:id AND account_id=:account",
                    Map.of("snapshot",store.encode(r.remoteSnapshot()),"id",replacement,"account",account));
                return replacement;
            }
        }
        return store.save(account,id,version,d);
    });}
    public RuleStore.Saved get(long account,UUID id){return store.get(account,id);}
    public Map<String,Object> results(long account,UUID id){return serial(account,()->{
        var r=store.get(account,id);var a=account(account);
        if(r.definition().organize())results.observe(a,provider(a),r,false);
        return results.summary(r);
    });}
    public void refreshResults(long account,UUID id){serial(account,()->{
        var a=account(account);requirePermission(a);var r=store.get(account,id);
        results.observe(a,provider(a),r,true);return null;
    });}
    private List<String> protectedSenders(long account,RuleDefinition d){
        String provider=account(account).getProvider();
        return store.jdbc().queryForList("SELECT sender_address FROM protected_senders WHERE account_id=:account ORDER BY sender_address",Map.of("account",account),String.class)
            .stream().filter(sender->d.matchesSender(sender,provider)).toList();
    }
    private String senderPredicate(UserAccount a,RuleDefinition d,List<String> entries,Map<String,Object> params,String prefix){
        String address="lower(COALESCE(dashboard_from_address,from_address,''))";
        var exact=new ArrayList<String>();var clauses=new ArrayList<String>();
        boolean contains=d.hasDomains()&&"microsoft".equals(a.getProvider());
        for(String entry:entries){
            if(entry.startsWith("@")||contains){String key=prefix+clauses.size();params.put(key,entry);clauses.add("position(:"+key+" in "+address+")>0");}
            else exact.add(entry);
        }
        if(!exact.isEmpty()){params.put(prefix+"Exact",exact);clauses.add(address+" IN (:"+prefix+"Exact)");}
        return clauses.isEmpty()?"false":"("+String.join(" OR ",clauses)+")";
    }

    private String localPhrasePredicate(List<String> phrases,String prefix,Map<String,Object> params) {
        var clauses=new ArrayList<String>();
        for(int i=0;i<phrases.size();i++){String key=prefix+i;params.put(key,phrases.get(i).toLowerCase(Locale.ROOT));clauses.add("position(:"+key+" in lower(COALESCE(subject_header,'')))>0");}
        return clauses.isEmpty()?"true":"("+String.join(" OR ",clauses)+")";
    }
    public record Matching(String bucket,String senderPredicate,Map<String,Object> params,boolean deployable,String note) {}
    public Matching matching(long account,UUID ruleId) {var a=account(account);return matching(a,store.get(account,ruleId).definition());}
    public Matching matching(long account,UUID ruleId,List<String> entries){
        var a=account(account);var d=store.get(account,ruleId).definition();var m=matching(a,d);
        if(entries.isEmpty())return m;
        if(!d.senders().containsAll(entries))RuleDefinition.fail("Choose sender entries already included in this rule.");
        return new Matching(m.bucket(),"("+m.senderPredicate()+") AND ("+senderPredicate(a,d,entries,m.params(),"selectedSender")+")",m.params(),m.deployable(),m.note());
    }
    private Matching matching(UserAccount a,RuleDefinition d) {
        var p=provider(a);
        var params=new HashMap<String,Object>();params.put("account",a.getId());params.put("senders",d.senders());params.put("subject",d.subject().toLowerCase(Locale.ROOT));params.put("except",d.exceptSubject().toLowerCase(Locale.ROOT));
        String senderPredicate=senderPredicate(a,d,d.senders(),params,"sender");
        boolean deployable=true;String matchingNote="";
        String include=localPhrasePredicate(d.subjects(),"include",params), exclude=localPhrasePredicate(d.exceptions(),"exclude",params);
        String bucket="CASE WHEN NOT ("+include+") THEN 'other' WHEN "+(d.exceptions().isEmpty()?"false":exclude)+" THEN 'excluded' ELSE 'match' END";
        if(p instanceof GmailRuleProvider gmail&&(d.hasDomains()||!d.subject().isEmpty()||!d.exceptSubject().isEmpty())){
            try{var matches=gmail.matching(a,d);
                params.put("included",matches.included().isEmpty()?List.of("__no_matches__"):matches.included());
                params.put("before",matches.beforeException().isEmpty()?List.of("__no_matches__"):matches.beforeException());
                bucket="CASE WHEN provider_message_id IN (:included) THEN 'match' WHEN provider_message_id IN (:before) THEN 'excluded' ELSE 'other' END";
                if(d.hasDomains())senderPredicate="("+senderPredicate+" OR provider_message_id IN (:before))";
                matchingNote="Sender/domain and subject matches were checked with Gmail search. Only examples retained in TidyMail are shown.";
            }catch(RuntimeException unavailable){deployable=false;matchingNote="Gmail sender/domain or subject matching could not be verified. These are approximate local examples; native activation is unavailable until a fresh preview succeeds.";}
        }else if(!d.subject().isEmpty()||!d.exceptSubject().isEmpty())matchingNote="Outlook subject conditions match text within the subject, ignoring case.";
        if("microsoft".equals(a.getProvider())&&d.hasDomains())matchingNote="Outlook uses sender address includes for all entries in a domain rule. Entries are alternatives; matching is text-based rather than a strict domain boundary. "+matchingNote;
        return new Matching(bucket,senderPredicate,params,deployable,matchingNote);
    }
    public String destination(long account,UUID ruleId) {return results.destination(store.get(account,ruleId));}
    public record Interaction(String name,String action,List<RuleOverlap.Sender> senders,String reason,boolean uncertain,
                              List<RuleOverlap.Condition> conditions,String remote,String fingerprint,String settingsUrl,String manageUrl,boolean checkable) {}
    private List<RuleProvider.NativeRule> interacting(RuleDefinition d,List<RuleProvider.NativeRule> inventory) {
        return inventory.stream().filter(RuleProvider.NativeRule::enabled).filter(n->RuleOverlap.assess(d,n).possible()).toList();
    }
    private List<Interaction> interactionSummaries(UserAccount a,RuleDefinition d,List<RuleProvider.NativeRule> inventory) {
        var managed=new HashMap<String,RuleStore.Saved>();for(var r:store.list(a.getId()))if(r.remoteId()!=null)managed.put(r.remoteId(),r);
        String settings=links.accountPage(a.getProvider(),a.getEmail()).map(url->url+"#settings/filters").orElse(provider(a).settingsUrl());
        return interacting(d,inventory).stream().map(n->{
            var saved=managed.get(n.id());var overlap=RuleOverlap.assess(d,n);
            String name=saved!=null?saved.definition().name():n.raw().has("criteria")?"Existing Gmail filter":n.name();
            return new Interaction(name,RuleOverlap.action(n,a.getProvider()),overlap.senders(),overlap.reason(),overlap.uncertain(),
                RuleOverlap.conditions(n.raw()),n.id(),fingerprint(n.raw()),settings,saved==null?"":"/rules/"+saved.id(),provider(a) instanceof GmailRuleProvider);
        }).toList();
    }
    public Map<String,Object> preview(long id,UUID ruleId){return serial(id,()->{
        var r=store.get(id,ruleId);return previewDefinition(id,r,r.definition(),r.definition().senders());
    });}
    private Map<String,Object> previewDefinition(long id,RuleStore.Saved r,RuleDefinition d,List<String> sampleSenders){
        UUID ruleId=r.id();var a=account(id);var p=provider(a);
        var matching=matching(a,d);var params=matching.params();boolean deployable=matching.deployable();String matchingNote=matching.note(),bucket=matching.bucket();
        String samplePredicate=sampleSenders.equals(d.senders())?matching.senderPredicate():senderPredicate(a,d,sampleSenders,params,"sample");
        String source="""
            WITH evidence AS (
              SELECT m.*,%s AS bucket
              FROM message_metadata m WHERE m.account_id=:account
              AND %s
            )
            """.formatted(bucket,samplePredicate);
        var counts=store.jdbc().queryForMap(source+"SELECT count(*) FILTER(WHERE bucket='match') matched,count(*) FILTER(WHERE bucket='excluded') excluded,count(*) FILTER(WHERE bucket='other') other,min(received_at) oldest,max(received_at) newest,max(last_synced_at) synced FROM evidence",params);
        var examples=store.jdbc().queryForList(source+"SELECT * FROM (SELECT provider_message_id,provider_thread_id,subject_header,received_at,outlook_web_link,bucket,COALESCE(dashboard_from_address,from_address) sender,row_number() OVER(PARTITION BY bucket ORDER BY received_at DESC NULLS LAST,id DESC) rn FROM evidence) sample WHERE rn<=10 ORDER BY bucket,rn",params);
        for(var e:examples){String url="";
            if("google".equals(a.getProvider()))url=links.gmail(a.getProvider(),a.getEmail(),(String)e.get("provider_thread_id"),(String)e.get("provider_message_id")).orElse("");
            else {String candidate=Objects.toString(e.get("outlook_web_link"),"");
                try {var u=java.net.URI.create(candidate);if("https".equals(u.getScheme())&&Set.of("outlook.live.com","outlook.office.com","outlook.office365.com").contains(u.getHost())&&u.getUserInfo()==null)url=candidate;}catch(Exception ignored){}
            }e.put("url",url);
        }
        List<RuleProvider.NativeRule> inventory=List.of();String inventoryHash=null,inventoryError="";
        if(permitted(a))try{inventory=p.list(a);inventoryHash=inventoryFingerprint(inventory);}
            catch(RuntimeException e){inventoryError="Native rules could not be checked; activation is unavailable until a fresh preview succeeds.";}
        var protectedAddresses=protectedSenders(id,d);var snapshot=new LinkedHashMap<String,Object>();
        snapshot.put("protected",protectedAddresses);snapshot.put("counts",counts);snapshot.put("definition",d);snapshot.put("originalState",r.state());
        // The preview records whether provider-specific subject matching was successfully checked.
        snapshot.put("deployable",deployable);
        store.jdbc().update("DELETE FROM automation_rule_previews WHERE account_id=:account AND expires_at<now()-interval '1 day'",Map.of("account",id));
        UUID preview=UUID.randomUUID();var sql=new HashMap<String,Object>();sql.put("id",preview);sql.put("rule",ruleId);sql.put("account",id);sql.put("version",r.version());sql.put("snapshot",store.encode(snapshot));sql.put("inventory",inventoryHash);
        store.jdbc().update("INSERT INTO automation_rule_previews(id,rule_id,account_id,version,snapshot,inventory_hash) VALUES(:id,:rule,:account,:version,CAST(:snapshot AS jsonb),:inventory)",sql);
        var out=new LinkedHashMap<String,Object>();out.put("id",preview);out.put("rule",r);out.put("counts",counts);out.put("examples",examples);out.put("protected",protectedAddresses);
        out.put("interactions",interactionSummaries(a,d,inventory.stream().filter(n->!n.id().equals(r.remoteId())).toList()));out.put("permission",permitted(a));out.put("inventoryError",inventoryError);out.put("inventory",inventory);out.put("deployable",deployable);out.put("matchingNote",matchingNote);
        return out;
    }
    public Map<String,Object> checkInteraction(long account,UUID id,UUID preview,String remote,String expectedFingerprint){return serial(account,()->{
        var a=account(account);requirePermission(a);var current=store.get(account,id);
        if(!(provider(a) instanceof GmailRuleProvider))RuleStore.conflict("Full-condition checking is currently available for Gmail filters. Review this rule in Outlook settings.");
        var gmail=(GmailRuleProvider)provider(a);
        var rows=store.jdbc().queryForList("SELECT snapshot FROM automation_rule_previews WHERE id=:preview AND rule_id=:id AND account_id=:account AND version=:version AND expires_at>now()",
            Map.of("preview",preview,"id",id,"account",account,"version",current.version()));
        if(rows.isEmpty())RuleStore.conflict("This review expired or the rule changed. Review the rule again before checking overlaps.");
        var snapshot=store.decode(rows.getFirst().get("snapshot").toString(),JsonNode.class);
        var d=store.decode(snapshot.path("definition").toString(),RuleDefinition.class);
        var existing=find(gmail.list(a),remote);
        if(existing==null||!existing.enabled()||!fingerprint(existing.raw()).equals(expectedFingerprint))RuleStore.conflict("The existing filter changed or is missing. Refresh the rule review before checking it.");
        Set<String> matches;
        try{matches=gmail.matchingInbox(a,existing.raw(),d.senders());}
        catch(IllegalStateException unavailable){throw new ResponseStatusException(HttpStatus.CONFLICT,"The full Gmail conditions could not be checked. No partial result is treated as verified. Review this filter in Gmail or try again.");}
        var params=new HashMap<String,Object>();params.put("account",account);String checkSenders=senderPredicate(a,d,d.senders(),params,"checkSender");params.put("ids",matches.isEmpty()?Set.of("__no_matches__"):matches);
        var indexed=store.jdbc().queryForList("SELECT provider_message_id,COALESCE(dashboard_from_address,from_address) sender,subject_header,received_at,provider_thread_id FROM message_metadata WHERE account_id=:account AND provider_message_id IN (:ids) AND "+checkSenders+" ORDER BY received_at DESC NULLS LAST,id DESC",params);
        var senderRows=new ArrayList<Map<String,Object>>();
        for(String sender:d.senders()){
            long count=indexed.stream().filter(m->RuleDefinition.matchesEntry(sender,Objects.toString(m.get("sender"),""),false)).count();
            senderRows.add(Map.of("address",sender,"matches",count));
        }
        senderRows.sort(Comparator.<Map<String,Object>>comparingLong(m->((Number)m.get("matches")).longValue()).reversed());
        var examples=new ArrayList<Map<String,Object>>();
        for(var m:indexed.stream().limit(5).toList())examples.add(Map.of("sender",m.get("sender"),"subject",Objects.toString(m.get("subject_header"),"(no subject)"),
            "url",links.gmail(a.getProvider(),a.getEmail(),(String)m.get("provider_thread_id"),(String)m.get("provider_message_id")).orElse("")));
        String explanation=matches.isEmpty()?"Gmail found no current Inbox messages from these addresses satisfying this filter’s full conditions. Sharing a sender condition alone does not establish a message match.":
            "Gmail currently finds Inbox messages satisfying this filter’s conditions. That confirms a current match, not that this filter ran when they arrived. Gmail does not provide filter creation time or a per-message execution history here; the emails may predate the filter or have been restored to Inbox. The cause is not established.";
        return Map.of("checkedAt",Instant.now().toString(),"selectedAddresses",d.senders().size(),"hasDomains",d.hasDomains(),"currentInboxMatches",matches.size(),"indexedMatches",indexed.size(),
            "unindexedMatches",Math.max(0,matches.size()-indexed.size()),"senders",senderRows,"examples",examples,"explanation",explanation);
    });}
    public void enable(long account,UUID id,UUID preview,boolean reviewedInventory){serial(account,()->{
        var a=account(account);requirePermission(a);var r=store.get(account,id);var p=provider(a);
        if(!Set.of("DRAFT","PAUSED").contains(r.state())||r.pending()!=null)RuleStore.conflict("Reload this rule before enabling it.");
        var rows=store.jdbc().queryForList("SELECT * FROM automation_rule_previews WHERE id=:preview AND rule_id=:id AND account_id=:account AND version=:version AND expires_at>now()",
            Map.of("preview",preview,"id",id,"account",account,"version",r.version()));
        if(rows.isEmpty())RuleStore.conflict("Preview expired or the rule changed. Preview it again.");
        var snapshot=store.decode(rows.getFirst().get("snapshot").toString(),JsonNode.class);
        if(!snapshot.path("deployable").asBoolean())RuleStore.conflict("Subject matching was not verified. Create a fresh preview before enabling this rule.");
        if(!protectedSenders(account,r.definition()).isEmpty())RuleStore.conflict("This rule includes a protected sender. Resolve the protection before enabling it.");
        var inventory=p.list(a);
        if(!Objects.equals(rows.getFirst().get("inventory_hash"),inventoryFingerprint(inventory)))RuleStore.conflict("Mailbox rules changed or were not checked. Preview again.");
        if(!interacting(r.definition(),inventory.stream().filter(n->!n.id().equals(r.remoteId())).toList()).isEmpty()&&!reviewedInventory)RuleStore.conflict("Review the existing mailbox rules before enabling this rule.");
        for(var existing:inventory){
            if(existing.id().equals(r.remoteId())||!existing.enabled()||existing.compatible()==null)continue;
            if(RuleOverlap.assess(r.definition(),existing).possible()
                &&(!existing.compatible().action().equals(r.definition().action())
                  ||!existing.compatible().destination().equals(r.definition().destination())))RuleStore.conflict("An existing rule handles this sender differently. Resolve that overlap in mailbox settings first.");
        }
        JsonNode payload=p.compile(a,r.definition(),inventory);
        String destination=results.destination(r);
        if(r.definition().organize()&&destination!=null)payload=p.useDestination(payload,destination);
        if(p.provider().equals("microsoft")&&r.remoteId()==null&&r.remoteSnapshot()!=null&&r.remoteSnapshot().has("sequence"))
            ((com.fasterxml.jackson.databind.node.ObjectNode)payload).set("sequence",r.remoteSnapshot().get("sequence"));
        if(r.remoteId()!=null){
            var nativeRule=find(inventory,r.remoteId());
            if(nativeRule==null||!p.behavior(nativeRule.raw()).equals(p.behavior(r.remoteSnapshot())))RuleStore.conflict("The native rule changed. Refresh before resuming.");
            store.claim(r,"RESUME",nativeRule.raw());
            try{p.resume(a,r.remoteId());confirm(a,p,r,"RESUME");}catch(RuntimeException e){store.attention(r,"Resume needs verification. Refresh rules; do not recreate the rule.");}
        }else{
            final JsonNode candidate=payload;
            if(inventory.stream().anyMatch(n->p.behavior(n.raw()).equals(p.behavior(candidate))||sameDefinition(n.compatible(),r.definition())))RuleStore.conflict("An equivalent rule already exists. Manage that rule instead of creating a duplicate.");
            store.claim(r,"CREATE",payload);
            try{
                payload=p.prepare(a,payload);
                // Persist the actual destination ID before dispatching a native rule; reconciliation needs this exact payload.
                final JsonNode ready=payload;
                if(inventory.stream().anyMatch(n->p.behavior(n.raw()).equals(p.behavior(ready))))
                    throw new IllegalStateException("An equivalent native rule already exists. Inspect mailbox settings.");
                store.prepared(r,payload);
                results.baseline(a,p,r,payload);
                String created=p.create(a,payload);store.recordCreated(r,created);confirm(a,p,r,"CREATE");
            }catch(RuntimeException e){store.attention(r,"Creation needs verification. Refresh rules before trying another operation.");}
        }return null;
    });}
    /** Review a proposed definition without changing the saved or native rule. */
    public Map<String,Object> reviewSenders(long account,UUID id,List<String> senders){return serial(account,()->{
        var r=store.get(account,id);requireSenderEditable(r);
        var union=new LinkedHashSet<>(r.definition().senders());union.addAll(senders);
        var d=withSenders(r.definition(),new ArrayList<>(union));
        if(d.senders().equals(r.definition().senders()))RuleStore.conflict("These addresses are already included in this rule.");
        var added=d.senders().stream().filter(v->!r.definition().senders().contains(v)).toList();
        var out=previewDefinition(account,r,d,added);out.put("proposed",d);out.put("added",added);
        return out;
    });}
    private RuleDefinition withSenders(RuleDefinition d,List<String> senders){return new RuleDefinition(d.name(),senders,d.subject(),d.exceptSubject(),d.action(),d.destination(),d.skipInbox(),d.destinationId(),d.destinationMode());}
    private void requireSenderEditable(RuleStore.Saved r){
        if(r.pending()!=null||!Set.of("ACTIVE","PAUSED","DRAFT").contains(r.state()))RuleStore.conflict("This rule needs attention. Review its status in Rules first.");
    }
    public void addSenders(long account,UUID id,UUID preview,boolean reviewedInventory){serial(account,()->{
        var a=account(account);var r=store.get(account,id);requireSenderEditable(r);
        var rows=store.jdbc().queryForList("SELECT * FROM automation_rule_previews WHERE id=:preview AND rule_id=:id AND account_id=:account AND version=:version AND expires_at>now()",
            Map.of("preview",preview,"id",id,"account",account,"version",r.version()));
        if(rows.isEmpty())RuleStore.conflict("The rule changed or this review expired. Choose the rule and review again.");
        var snapshot=store.decode(rows.getFirst().get("snapshot").toString(),JsonNode.class);
        var d=store.decode(snapshot.path("definition").toString(),RuleDefinition.class);
        if(!snapshot.path("originalState").asText().equals(r.state()))RuleStore.conflict("The rule status changed. Review the addition again.");
        if(!withSenders(r.definition(),d.senders()).equals(d)||!d.senders().containsAll(r.definition().senders())||d.senders().equals(r.definition().senders()))RuleStore.conflict("Review the sender addition again.");
        if(!snapshot.path("deployable").asBoolean()||!protectedSenders(account,d).isEmpty())RuleStore.conflict("Matching could not be verified or a sender is protected. Review the rule again.");
        if(r.remoteId()==null){store.updateSenders(r,d,r.state(),null,r.remoteSnapshot());return null;}
        requirePermission(a);var p=provider(a);var inventory=p.list(a);
        if(!Objects.equals(rows.getFirst().get("inventory_hash"),inventoryFingerprint(inventory)))RuleStore.conflict("Mailbox rules changed. Review the addition again.");
        var n=find(inventory,r.remoteId());
        if(n==null||n.readOnly()||n.enabled()!=r.state().equals("ACTIVE")||!p.behavior(n.raw()).equals(p.behavior(r.remoteSnapshot())))RuleStore.conflict("The native rule changed. Refresh it in Rules before editing.");
        var others=inventory.stream().filter(v->!v.id().equals(r.remoteId())).toList();
        if(!interacting(d,others).isEmpty()&&!reviewedInventory)RuleStore.conflict("Review the other rules that may act on this mail.");
        JsonNode payload=p.withSenders(n.raw(),d.senders());
        if(p.provider().equals("google")&&others.stream().anyMatch(v->p.behavior(v.raw()).equals(p.behavior(payload))))RuleStore.conflict("An equivalent rule already exists. Manage it in Rules.");
        var operation=store.json().createObjectNode();operation.set("payload",payload);operation.set("definition",store.json().valueToTree(d));operation.put("state",r.state());
        store.claim(r,"UPDATE_SENDERS",operation);
        try{
            if(p.provider().equals("google"))p.create(a,payload);else p.updateSenders(a,r.remoteId(),payload);
            confirm(a,p,r,"UPDATE_SENDERS");
        }catch(RuntimeException failure){store.attention(r,"Sender update needs verification. Refresh Rules before retrying; the original or replacement rule may still be running.");}
        return null;
    });}
    private void reconcileSenderUpdate(UserAccount a,RuleProvider p,RuleStore.Saved r,List<RuleProvider.NativeRule> inventory){
        var payload=r.operation().path("payload");
        var matches=inventory.stream().filter(n->!n.readOnly()&&p.behavior(n.raw()).equals(p.behavior(payload))
            &&(p.provider().equals("google")||n.id().equals(r.remoteId()))).toList();
        if(matches.size()!=1){store.attention(r,"Sender update not confirmed. Inspect mailbox rules, then refresh or resolve; do not create another rule.");return;}
        var replacement=matches.getFirst();
        if(replacement.enabled()!=r.operation().path("state").asText().equals("ACTIVE")){store.attention(r,"The updated rule status changed. Inspect mailbox settings.");return;}
        if(p.provider().equals("google")){
            var old=find(inventory,r.remoteId());
            if(old!=null){
                if(old.readOnly()||!p.behavior(old.raw()).equals(p.behavior(r.remoteSnapshot()))){store.attention(r,"The original filter changed during replacement. Review both filters in mailbox settings.");return;}
                p.pause(a,old.id());
                var fresh=p.list(a);
                if(find(fresh,old.id())!=null){store.attention(r,"The replacement exists; removal of the original filter needs verification.");return;}
                replacement=find(fresh,replacement.id());
                if(replacement==null||!p.behavior(replacement.raw()).equals(p.behavior(payload))){store.attention(r,"The replacement filter changed during verification.");return;}
            }
        }
        var d=store.decode(r.operation().path("definition").toString(),RuleDefinition.class);
        store.updateSenders(r,d,replacement.enabled()?"ACTIVE":"PAUSED",replacement.id(),replacement.raw());
    }
    public record DraftTarget(UUID id,long version) {}
    public record DraftDeletion(int deleted,int skipped) {}
    public List<DraftTarget> draftDeletionTargets(long account,UUID id){return serial(account,()->store.list(account).stream()
        .filter(RuleStore::deletableDraft).filter(r->id==null||r.id().equals(id)).map(r->new DraftTarget(r.id(),r.version())).toList());}
    public DraftDeletion deleteDrafts(long account,List<DraftTarget> confirmed){return serial(account,()->{
        int deleted=0;
        var current=new HashMap<UUID,RuleStore.Saved>();store.list(account).forEach(r->current.put(r.id(),r));
        for(var target:confirmed){
            var r=current.get(target.id());
            if(r!=null&&r.version()==target.version()&&RuleStore.deletableDraft(r)&&store.removeLocal(r,true))deleted++;
        }
        return new DraftDeletion(deleted,confirmed.size()-deleted);
    });}
    public void manage(long account,UUID id,String action){serial(account,()->{
        var r=store.get(account,id);
        if(action.equals("remove")&&r.remoteId()==null&&r.pending()==null){if(!store.removeLocal(r,false))RuleStore.conflict("The rule changed. Reload before removing it.");return null;}
        var a=account(account);requirePermission(a);var p=provider(a);
        if(r.pending()!=null)RuleStore.conflict("Refresh the pending provider operation first.");
        if(!Set.of("pause","remove").contains(action))RuleDefinition.fail("Unknown rule operation.");
        if(r.remoteId()==null)RuleStore.conflict("No native rule is attached.");
        var nativeRule=find(p.list(a),r.remoteId());
        if(nativeRule==null||!p.behavior(nativeRule.raw()).equals(p.behavior(r.remoteSnapshot())))RuleStore.conflict("The native rule changed or is missing. Refresh and review it in mailbox settings.");
        if(nativeRule.readOnly())RuleStore.conflict("This rule is read-only at the provider.");
        String op=action.equals("remove")?"REMOVE":"PAUSE";store.claim(r,op,nativeRule.raw());
        try{
            if(p instanceof OutlookRuleProvider outlook&&op.equals("REMOVE"))outlook.remove(a,r.remoteId());else p.pause(a,r.remoteId());
            confirm(a,p,r,op);
        }catch(RuntimeException e){store.attention(r,"Provider operation needs verification. Refresh rules before retrying.");}return null;
    });}
    public UUID adopt(long account,String remoteId,String expectedHash){return serial(account,()->{
        var a=account(account);requirePermission(a);var p=provider(a);var inventory=p.list(a);var n=find(inventory,remoteId);
        if(n==null||n.compatible()==null||n.readOnly())RuleStore.conflict("This rule cannot be safely edited in the shared editor. Use mailbox settings.");
        if(!fingerprint(n.raw()).equals(expectedHash))RuleStore.conflict("This rule changed. Refresh and review it again.");
        if(store.list(account).stream().anyMatch(r->remoteId.equals(r.remoteId())))RuleStore.conflict("This rule is already managed.");
        var d=n.compatible();UUID id=store.save(account,null,0,d);var r=store.get(account,id);
        store.settle(r,n.enabled()?"ACTIVE":"PAUSED",remoteId,n.raw(),"Existing mailbox rule brought under management; native definition preserved");return id;
    });}
    public void resolve(long account,UUID id){serial(account,()->{
        var a=account(account);requirePermission(a);var p=provider(a);var r=store.get(account,id);
        if(r.pending()==null&&r.state().equals("ATTENTION")){
            var n=find(p.list(a),r.remoteId());
            if(n==null){store.settle(r,"PAUSED",null,null,"Missing native rule acknowledged; definition retained");return null;}
            if(n.compatible()==null)RuleStore.conflict("This changed rule has unsupported settings. Manage it in mailbox settings.");
            // Do not accept changed definitions under a previously reviewed local definition.
            RuleStore.conflict("Remove or restore the changed rule in mailbox settings, then refresh. Its altered behavior will not be silently adopted.");
        }
        if(r.pending()==null)RuleStore.conflict("No pending operation to resolve.");
        boolean old=Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT updated_at<now()-interval '2 minutes' FROM automation_rules WHERE id=:id AND account_id=:account",Map.of("id",id,"account",account),Boolean.class));
        if(!old)RuleStore.conflict("Wait two minutes after the request, then refresh before resolving an uncertain operation.");
        var inventory=p.list(a);reconcileOne(a,p,r,inventory);r=store.get(account,id);
        if(r.pending()==null)return null;
        if(r.pending().equals("UPDATE_SENDERS")){
            var current=r;var payload=r.operation().path("payload");
            if(inventory.stream().anyMatch(n->p.behavior(n.raw()).equals(p.behavior(payload))))RuleStore.conflict("An updated rule exists. Review mailbox settings and refresh before resolving.");
            var original=find(inventory,r.remoteId());
            if(original!=null&&p.behavior(original.raw()).equals(p.behavior(current.remoteSnapshot())))
                store.settle(r,original.enabled()?"ACTIVE":"PAUSED",original.id(),original.raw(),"Sender update not applied; original rule retained. Review the addition again.");
            else RuleStore.conflict("Restore the original rule in mailbox settings before resolving this update.");
        }else if(r.pending().equals("CREATE")){
            final var pending=r;long matches=inventory.stream().filter(n->p.behavior(n.raw()).equals(p.behavior(pending.operation()))).count();
            if(matches!=0)RuleStore.conflict("Matching native rules still exist. Resolve their state in mailbox settings first.");
            store.settle(r,"DRAFT",null,null,"No native rule found; uncertain creation explicitly cleared");
            store.jdbc().update("DELETE FROM automation_rule_results WHERE rule_id=:rule AND account_id=:account",Map.of("rule",id,"account",account));
        }else{
            var n=find(inventory,r.remoteId());
            if(n==null)store.settle(r,"PAUSED",null,null,"Native rule absence acknowledged");
            else if(p.behavior(n.raw()).equals(p.behavior(r.operation())))store.settle(r,n.enabled()?"ACTIVE":"PAUSED",n.id(),n.raw(),"Current provider state explicitly accepted");
            else RuleStore.conflict("The native definition changed. Restore or remove it in mailbox settings before resolving.");
        }return null;
    });}
    public void refresh(long account){refresh(account,true);}
    private void refresh(long account,boolean manual){serial(account,()->{
        var a=account(account);requirePermission(a);var p=provider(a);reconcile(a,p,p.list(a));
        for(var r:store.list(account))if(r.definition().organize()&&r.pending()==null)
            results.observe(a,p,r,manual||results.countDue(r));
        return null;
    });}
    private RuleProvider.NativeRule find(List<RuleProvider.NativeRule> list,String id){return list.stream().filter(n->n.id().equals(id)).findFirst().orElse(null);}
    private void confirm(UserAccount a,RuleProvider p,RuleStore.Saved r,String op){reconcileOne(a,p,store.get(a.getId(),r.id()),p.list(a));}
    private void reconcile(UserAccount a,RuleProvider p,List<RuleProvider.NativeRule> inventory){for(var r:store.list(a.getId()))reconcileOne(a,p,r,inventory);}
    private void reconcileOne(UserAccount a,RuleProvider p,RuleStore.Saved r,List<RuleProvider.NativeRule> inventory){
        if(r.pending()!=null){
            if(r.pending().equals("UPDATE_SENDERS")){reconcileSenderUpdate(a,p,r,inventory);return;}
            if(r.pending().equals("CREATE")){
                var matches=inventory.stream().filter(n->(r.remoteId()==null||r.remoteId().equals(n.id()))&&p.behavior(n.raw()).equals(p.behavior(r.operation()))).toList();
                if(matches.size()==1)store.settle(r,matches.getFirst().enabled()?"ACTIVE":"PAUSED",matches.getFirst().id(),matches.getFirst().raw(),"Native rule creation confirmed");
                else store.attention(r,matches.isEmpty()?"No matching native rule found after creation attempt. Check mailbox settings before resolving this operation.":"Multiple matching native rules found. Resolve duplicates in mailbox settings.");
            }else{
                var n=find(inventory,r.remoteId());
                if(r.pending().equals("REMOVE")&&n==null)store.settle(r,"REMOVED",null,null,"Native rule removed");
                else if(r.pending().equals("PAUSE")&&n==null&&p.provider().equals("google"))store.settle(r,"PAUSED",null,null,"Gmail filter removed; definition retained for resume");
                else if(r.pending().equals("REMOVE"))store.attention(r,"Native rule removal has not been confirmed. Check mailbox settings.");
                else if(n!=null&&p.behavior(n.raw()).equals(p.behavior(r.operation()))){
                    boolean resume=r.pending().equals("RESUME");
                    if(n.enabled()==resume)store.settle(r,resume?"ACTIVE":"PAUSED",n.id(),n.raw(),resume?"Native rule enabled":"Native rule disabled");
                    else store.attention(r,"Provider has not confirmed the requested state. Inspect mailbox settings.");
                }else store.attention(r,"Native rule differs from the pending operation. Inspect mailbox settings.");
            }return;
        }
        if(r.remoteId()==null)return;
        var n=find(inventory,r.remoteId());
        if(n!=null&&(n.readOnly()||n.raw().path("hasError").asBoolean())){store.attention(r,"Provider reports this rule as read-only or in error. Inspect mailbox settings.");return;}
        if(n==null){store.attention(r,"Native rule is missing. It will not be recreated automatically.");return;}
        if(!p.behavior(n.raw()).equals(p.behavior(r.remoteSnapshot()))){store.attention(r,"Changed outside TidyMail. Use mailbox settings to review the full rule.");return;}
        if(n.enabled()&&!protectedSenders(a.getId(),r.definition()).isEmpty()){
            store.claim(r,"PAUSE",n.raw());
            try{p.pause(a,n.id());confirm(a,p,r,"PAUSE");}catch(RuntimeException e){store.attention(r,"Protected sender still may be affected by native rules. Pause could not be confirmed; use mailbox settings.");}return;
        }
        String state=n.enabled()?"ACTIVE":"PAUSED";
        if(!state.equals(r.state()))store.settle(r,state,n.id(),n.raw(),"Provider state refreshed");
        else store.jdbc().update("UPDATE automation_rules SET checked_at=now() WHERE id=:id AND account_id=:account",Map.of("id",r.id(),"account",a.getId()));
    }
    public void reconcileDue(){
        var due=store.jdbc().queryForList("SELECT DISTINCT account_id FROM automation_rules WHERE state<>'REMOVED' AND (remote_id IS NOT NULL OR pending_operation IS NOT NULL) AND (checked_at IS NULL OR checked_at<now()-interval '5 minutes')",Map.of(),Long.class);
        for(long id:due)try{var a=account(id);if(permitted(a))refresh(id,false);}
            catch(RuntimeException unavailable){
                for(var r:store.list(id))if(r.remoteId()!=null||r.pending()!=null)store.attention(r,"Provider state could not be checked. Native rules may still be running; refresh or inspect mailbox settings.");
            }
    }
    private boolean sameDefinition(RuleDefinition a,RuleDefinition b){
        return a!=null&&a.senders().equals(b.senders())&&a.subject().equalsIgnoreCase(b.subject())
            &&a.exceptSubject().equalsIgnoreCase(b.exceptSubject())&&a.action().equals(b.action())
            &&a.destination().equalsIgnoreCase(b.destination())&&a.destinationId().equals(b.destinationId())&&a.skipInbox()==b.skipInbox();
    }
    private String inventoryFingerprint(List<RuleProvider.NativeRule> inventory){
        return fingerprint(inventory.stream().sorted(Comparator.comparing(RuleProvider.NativeRule::id)).map(RuleProvider.NativeRule::raw).toList());
    }
    public String fingerprint(Object value){
        try{var bytes=store.json().writeValueAsBytes(canonical(store.json().valueToTree(value)));
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    private Object canonical(JsonNode n){if(n.isObject()){var out=new TreeMap<String,Object>();n.fields().forEachRemaining(f->out.put(f.getKey(),canonical(f.getValue())));return out;}
        if(n.isArray()){var out=new ArrayList<Object>();n.forEach(v->out.add(canonical(v)));return out;}return n;}
}
