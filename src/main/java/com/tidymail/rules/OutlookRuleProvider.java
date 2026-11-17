package com.tidymail.rules;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.outlook.OutlookGraph;
import java.util.*;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

@Component
public class OutlookRuleProvider implements RuleProvider {
    private static final String BASE="/v1.0/me/mailFolders/inbox/messageRules";
    private final OutlookGraph graph;private final ObjectMapper json;
    public OutlookRuleProvider(OutlookGraph graph,ObjectMapper json){this.graph=graph;this.json=json;}
    public String provider(){return "microsoft";}
    public String scope(){return "MailboxSettings.ReadWrite";}
    public String settingsUrl(){return "https://outlook.live.com/mail/0/options/mail/rules";}
    public List<NativeRule> list(UserAccount a){
        var out=new ArrayList<NativeRule>();String url=BASE;
        do {var page=graph.request(a,HttpMethod.GET,url,null);
            for(var r:page.path("value"))out.add(new NativeRule(r.path("id").asText(),r.path("displayName").asText("Outlook rule"),
                r.path("isEnabled").asBoolean(),r.path("isReadOnly").asBoolean(),r,null,r.path("conditions").toString()));
            url=page.path("@odata.nextLink").asText(null);
            if(out.size()>2000)throw new IllegalStateException("Too many Outlook rules to inspect safely.");
        }while(url!=null);
        String archive=null;
        if(out.stream().anyMatch(n->n.raw().path("actions").hasNonNull("moveToFolder")))
            archive=graph.request(a,HttpMethod.GET,"/v1.0/me/mailFolders/archive?$select=id",null).path("id").asText();
        final String archiveId=archive;
        out.replaceAll(n->new NativeRule(n.id(),n.name(),n.enabled(),n.readOnly(),n.raw(),compatible(n.raw(),archiveId),n.explanation()));
        out.sort(Comparator.comparingInt(r->r.raw().path("sequence").asInt()));return out;
    }
    private RuleDefinition compatible(JsonNode r,String archiveId){
        if(r.path("isReadOnly").asBoolean()||r.path("hasError").asBoolean()||r.path("actions").path("stopProcessingRules").asBoolean())return null;
        var c=r.path("conditions");var ex=r.path("exceptions");var a=r.path("actions");
        if(extra(c,Set.of("fromAddresses","subjectContains"))||extra(ex,Set.of("subjectContains"))
            ||extra(a,Set.of("delete","markAsRead","moveToFolder","stopProcessingRules")))return null;
        boolean archive=a.hasNonNull("moveToFolder")&&!a.path("moveToFolder").asText().isEmpty();
        if(archive&&!a.path("moveToFolder").asText().equals(archiveId))return null;
        if(c.path("subjectContains").size()>1||ex.path("subjectContains").size()>1||!c.path("fromAddresses").isArray())return null;
        var senders=new ArrayList<String>();for(var from:c.path("fromAddresses"))senders.add(from.path("emailAddress").path("address").asText());
        boolean trash=a.path("delete").asBoolean(),read=a.path("markAsRead").asBoolean();
        if(trash&&archive||!trash&&!read&&!archive)return null;
        try{return new RuleDefinition(r.path("displayName").asText(),senders,c.path("subjectContains").path(0).asText(""),
            ex.path("subjectContains").path(0).asText(""),trash?(read?"READ_TRASH":"TRASH"):archive?(read?"READ_ARCHIVE":"ARCHIVE"):"READ");}
        catch(RuntimeException unsupported){return null;}
    }
    private boolean extra(JsonNode n,Set<String> allowed){
        var fields=n.fields();while(fields.hasNext()){var f=fields.next();var v=f.getValue();
            boolean meaningful=!v.isNull()&&!(v.isArray()&&v.isEmpty())&&!(v.isBoolean()&&!v.asBoolean());
            if(meaningful&&!allowed.contains(f.getKey())&&!f.getKey().startsWith("@odata."))return true;
        }return false;
    }
    public JsonNode compile(UserAccount a,RuleDefinition d,List<NativeRule> inventory){
        ObjectNode r=json.createObjectNode();r.put("displayName",d.name());r.put("isEnabled",true);
        r.put("sequence",inventory.stream().mapToInt(n->n.raw().path("sequence").asInt()).max().orElse(0)+1);
        var c=r.putObject("conditions");var from=c.putArray("fromAddresses");
        for(String sender:d.senders())from.addObject().putObject("emailAddress").put("address",sender);
        if(!d.subject().isEmpty())c.putArray("subjectContains").add(d.subject());
        if(!d.exceptSubject().isEmpty())r.putObject("exceptions").putArray("subjectContains").add(d.exceptSubject());
        var actions=r.putObject("actions");actions.put("stopProcessingRules",false);
        if(d.trash())actions.put("delete",true);if(d.read())actions.put("markAsRead",true);
        if(d.archive()){
            var folder=graph.request(a,HttpMethod.GET,"/v1.0/me/mailFolders/archive?$select=id",null);
            if(folder.path("id").asText().isEmpty())throw new IllegalStateException("Outlook Archive folder is unavailable.");
            actions.put("moveToFolder",folder.path("id").asText());
        }return r;
    }
    public String create(UserAccount a,JsonNode payload){return graph.request(a,HttpMethod.POST,BASE,payload).path("id").asText();}
    public void pause(UserAccount a,String id){graph.request(a,HttpMethod.PATCH,path(id),Map.of("isEnabled",false));}
    public void resume(UserAccount a,String id){graph.request(a,HttpMethod.PATCH,path(id),Map.of("isEnabled",true));}
    public void remove(UserAccount a,String id){graph.request(a,HttpMethod.DELETE,path(id),null);}
    private String path(String id){return BASE+"/"+org.springframework.web.util.UriUtils.encodePathSegment(id,java.nio.charset.StandardCharsets.UTF_8);}
    public JsonNode behavior(JsonNode raw){
        ObjectNode r=json.createObjectNode();for(String key:List.of("displayName","conditions","exceptions","actions","sequence"))
            if(raw.has(key))r.set(key,raw.get(key));
        // Graph materializes defaults omitted in create requests. Normalize these recursively.
        normalize(r);
        // Recipient display names are presentation; matching is determined by the address.
        for(String section:List.of("conditions","exceptions"))for(var recipient:r.path(section).path("fromAddresses"))
            if(recipient.path("emailAddress").isObject())((ObjectNode)recipient.path("emailAddress")).remove("name");
        return r;
    }
    private void normalize(JsonNode n){if(n.isObject()){
        var remove=new ArrayList<String>();n.fields().forEachRemaining(f->{var v=f.getValue();
            if(v.isNull()||v.isArray()&&v.isEmpty()||v.isBoolean()&&!v.asBoolean()||f.getKey().startsWith("@odata."))remove.add(f.getKey());else normalize(v);});
        ((ObjectNode)n).remove(remove);
    }else if(n.isArray())n.forEach(this::normalize);}
}
