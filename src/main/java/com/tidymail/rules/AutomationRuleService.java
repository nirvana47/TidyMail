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
    private final RuleStore store;private final UserAccountRepository accounts;private final ProviderDeepLinkService links;
    private final Map<String,RuleProvider> providers=new HashMap<>();
    private final ConcurrentHashMap<Long,Object> locks=new ConcurrentHashMap<>();
    public AutomationRuleService(RuleStore store,UserAccountRepository accounts,ProviderDeepLinkService links,List<RuleProvider> providers){
        this.store=store;this.accounts=accounts;this.links=links;providers.forEach(p->this.providers.put(p.provider(),p));
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
    private List<String> protectedSenders(long account,RuleDefinition d){return store.jdbc().queryForList(
        "SELECT sender_address FROM protected_senders WHERE account_id=:account AND sender_address IN (:senders) ORDER BY sender_address",
        Map.of("account",account,"senders",d.senders()),String.class);}

    public Map<String,Object> preview(long id,UUID ruleId){return serial(id,()->{
        var a=account(id);var r=store.get(id,ruleId);var d=r.definition();var p=provider(a);
        var params=new HashMap<String,Object>();params.put("account",id);params.put("senders",d.senders());params.put("subject",d.subject().toLowerCase(Locale.ROOT));params.put("except",d.exceptSubject().toLowerCase(Locale.ROOT));
        boolean deployable=true;String matchingNote="";
        String bucket="CASE WHEN :subject<>'' AND position(:subject in lower(COALESCE(subject_header,'')))=0 THEN 'other' WHEN :except<>'' AND position(:except in lower(COALESCE(subject_header,'')))>0 THEN 'excluded' ELSE 'match' END";
        if(p instanceof GmailRuleProvider gmail&&(!d.subject().isEmpty()||!d.exceptSubject().isEmpty())){
            try{var matches=gmail.matching(a,d);
                params.put("included",matches.included().isEmpty()?List.of("__no_matches__"):matches.included());
                params.put("before",matches.beforeException().isEmpty()?List.of("__no_matches__"):matches.beforeException());
                bucket="CASE WHEN provider_message_id IN (:included) THEN 'match' WHEN provider_message_id IN (:before) THEN 'excluded' ELSE 'other' END";
                matchingNote="Subject matches were checked with Gmail search. Only examples retained in TidyMail are shown.";
            }catch(RuntimeException unavailable){deployable=false;matchingNote="Gmail subject matching could not be verified. These are approximate local examples; native activation is unavailable until a fresh preview succeeds.";}
        }else if(!d.subject().isEmpty()||!d.exceptSubject().isEmpty())matchingNote="Outlook subject conditions match text within the subject, ignoring case.";
        String source="""
            WITH evidence AS (
              SELECT m.*,%s AS bucket
              FROM message_metadata m WHERE m.account_id=:account
              AND lower(COALESCE(m.dashboard_from_address,m.from_address,'')) IN (:senders)
            )
            """.formatted(bucket);
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
        snapshot.put("protected",protectedAddresses);snapshot.put("counts",counts);snapshot.put("definition",d);
        // The preview records whether provider-specific subject matching was successfully checked.
        snapshot.put("deployable",deployable);
        store.jdbc().update("DELETE FROM automation_rule_previews WHERE account_id=:account AND expires_at<now()-interval '1 day'",Map.of("account",id));
        UUID preview=UUID.randomUUID();var sql=new HashMap<String,Object>();sql.put("id",preview);sql.put("rule",ruleId);sql.put("account",id);sql.put("version",r.version());sql.put("snapshot",store.encode(snapshot));sql.put("inventory",inventoryHash);
        store.jdbc().update("INSERT INTO automation_rule_previews(id,rule_id,account_id,version,snapshot,inventory_hash) VALUES(:id,:rule,:account,:version,CAST(:snapshot AS jsonb),:inventory)",sql);
        var out=new LinkedHashMap<String,Object>();out.put("id",preview);out.put("rule",r);out.put("counts",counts);out.put("examples",examples);out.put("protected",protectedAddresses);
        out.put("permission",permitted(a));out.put("inventoryError",inventoryError);out.put("inventory",inventory);out.put("deployable",deployable);out.put("matchingNote",matchingNote);
        return out;
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
        if(!inventory.isEmpty()&&!reviewedInventory)RuleStore.conflict("Review the existing mailbox rules before enabling this rule.");
        for(var existing:inventory){
            if(existing.id().equals(r.remoteId())||!existing.enabled()||existing.compatible()==null)continue;
            if(!Collections.disjoint(existing.compatible().senders(),r.definition().senders())
                &&!existing.compatible().action().equals(r.definition().action()))RuleStore.conflict("An existing rule handles this sender differently. Resolve that overlap in mailbox settings first.");
        }
        JsonNode payload=p.compile(a,r.definition(),inventory);
        if(p.provider().equals("microsoft")&&r.remoteId()==null&&r.remoteSnapshot()!=null&&r.remoteSnapshot().has("sequence"))
            ((com.fasterxml.jackson.databind.node.ObjectNode)payload).set("sequence",r.remoteSnapshot().get("sequence"));
        if(r.remoteId()!=null){
            var nativeRule=find(inventory,r.remoteId());
            if(nativeRule==null||!p.behavior(nativeRule.raw()).equals(p.behavior(r.remoteSnapshot())))RuleStore.conflict("The native rule changed. Refresh before resuming.");
            store.claim(r,"RESUME",nativeRule.raw());
            try{p.resume(a,r.remoteId());confirm(a,p,r,"RESUME");}catch(RuntimeException e){store.attention(r,"Resume needs verification. Refresh rules; do not recreate the rule.");}
        }else{
            if(inventory.stream().anyMatch(n->p.behavior(n.raw()).equals(p.behavior(payload))||sameDefinition(n.compatible(),r.definition())))RuleStore.conflict("An equivalent rule already exists. Manage that rule instead of creating a duplicate.");
            store.claim(r,"CREATE",payload);
            try{String created=p.create(a,payload);store.recordCreated(r,created);confirm(a,p,r,"CREATE");}catch(RuntimeException e){store.attention(r,"Creation needs verification. Refresh rules before trying another operation.");}
        }return null;
    });}
    public void manage(long account,UUID id,String action){serial(account,()->{
        var r=store.get(account,id);
        if(action.equals("remove")&&r.remoteId()==null&&r.pending()==null){store.settle(r,"REMOVED",null,null,"Local draft removed");return null;}
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
        if(r.pending().equals("CREATE")){
            final var pending=r;long matches=inventory.stream().filter(n->p.behavior(n.raw()).equals(p.behavior(pending.operation()))).count();
            if(matches!=0)RuleStore.conflict("Matching native rules still exist. Resolve their state in mailbox settings first.");
            store.settle(r,"DRAFT",null,null,"No native rule found; uncertain creation explicitly cleared");
        }else{
            var n=find(inventory,r.remoteId());
            if(n==null)store.settle(r,"PAUSED",null,null,"Native rule absence acknowledged");
            else if(p.behavior(n.raw()).equals(p.behavior(r.operation())))store.settle(r,n.enabled()?"ACTIVE":"PAUSED",n.id(),n.raw(),"Current provider state explicitly accepted");
            else RuleStore.conflict("The native definition changed. Restore or remove it in mailbox settings before resolving.");
        }return null;
    });}
    public void refresh(long account){serial(account,()->{var a=account(account);requirePermission(a);var p=provider(a);reconcile(a,p,p.list(a));return null;});}
    private RuleProvider.NativeRule find(List<RuleProvider.NativeRule> list,String id){return list.stream().filter(n->n.id().equals(id)).findFirst().orElse(null);}
    private void confirm(UserAccount a,RuleProvider p,RuleStore.Saved r,String op){reconcileOne(a,p,store.get(a.getId(),r.id()),p.list(a));}
    private void reconcile(UserAccount a,RuleProvider p,List<RuleProvider.NativeRule> inventory){for(var r:store.list(a.getId()))reconcileOne(a,p,r,inventory);}
    private void reconcileOne(UserAccount a,RuleProvider p,RuleStore.Saved r,List<RuleProvider.NativeRule> inventory){
        if(r.pending()!=null){
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
        for(long id:due)try{var a=account(id);if(permitted(a))refresh(id);}
            catch(RuntimeException unavailable){
                for(var r:store.list(id))if(r.remoteId()!=null||r.pending()!=null)store.attention(r,"Provider state could not be checked. Native rules may still be running; refresh or inspect mailbox settings.");
            }
    }
    private boolean sameDefinition(RuleDefinition a,RuleDefinition b){
        return a!=null&&a.senders().equals(b.senders())&&a.subject().equalsIgnoreCase(b.subject())
            &&a.exceptSubject().equalsIgnoreCase(b.exceptSubject())&&a.action().equals(b.action());
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
