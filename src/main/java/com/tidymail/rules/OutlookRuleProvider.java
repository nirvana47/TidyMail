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
        if(out.stream().anyMatch(n->n.raw().path("actions").hasNonNull("moveToFolder")))try{
            archive=graph.request(a,HttpMethod.GET,"/v1.0/me/mailFolders/archive?$select=id",null).path("id").asText();
        }catch(com.tidymail.mail.CleanupProviderException unavailable){if(unavailable.status()!=404)throw unavailable;}
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
        if(!c.path("fromAddresses").isArray())return null;
        var senders=new ArrayList<String>();for(var from:c.path("fromAddresses"))senders.add(from.path("emailAddress").path("address").asText());
        boolean trash=a.path("delete").asBoolean(),read=a.path("markAsRead").asBoolean();
        if(trash&&archive||!trash&&!read&&!archive)return null;
        try{return new RuleDefinition(r.path("displayName").asText(),senders,phrases(c.path("subjectContains")),
            phrases(ex.path("subjectContains")),trash?(read?"READ_TRASH":"TRASH"):archive?(read?"READ_ARCHIVE":"ARCHIVE"):"READ");}
        catch(RuntimeException unsupported){return null;}
    }
    private String phrases(JsonNode node){var values=new ArrayList<String>();node.forEach(v->values.add(v.asText()));return String.join("\n",values);}
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
        if(!d.subject().isEmpty())d.subjects().forEach(c.putArray("subjectContains")::add);
        if(!d.exceptSubject().isEmpty())d.exceptions().forEach(r.putObject("exceptions").putArray("subjectContains")::add);
        var actions=r.putObject("actions");actions.put("stopProcessingRules",false);
        if(d.trash())actions.put("delete",true);if(d.read())actions.put("markAsRead",true);
        if(d.organize()) {
            r.put("_destinationName",d.destination());r.put("_destinationMode",d.destinationMode());
            if(!d.destinationId().isEmpty())r.put("_destinationId",d.destinationId());
        }
        if(d.archive()&&!d.organize()){
            var folder=graph.request(a,HttpMethod.GET,"/v1.0/me/mailFolders/archive?$select=id",null);
            if(folder.path("id").asText().isEmpty())throw new IllegalStateException("Outlook Archive folder is unavailable.");
            actions.put("moveToFolder",folder.path("id").asText());
        }return r;
    }
    public JsonNode prepare(UserAccount a,JsonNode payload){
        if(!payload.has("_destinationName"))return payload;
        String name=payload.path("_destinationName").asText();
        if(payload.path("_destinationMode").asText().equals("EXISTING")) {
            String id=payload.path("_destinationId").asText();
            var folder=graph.request(a,HttpMethod.GET,"/v1.0/me/mailFolders/"+org.springframework.web.util.UriUtils.encodePathSegment(id,java.nio.charset.StandardCharsets.UTF_8)+"?$select=id",null);
            if(!id.equals(folder.path("id").asText()))throw new IllegalStateException("Selected folder is unavailable.");
            return useDestination(payload,id);
        }
        var matches=new ArrayList<String>();String url="/v1.0/me/mailFolders?$select=id,displayName&$top=100";int seen=0;
        do {var page=graph.request(a,HttpMethod.GET,url,null);
            for(var folder:page.path("value")){
                if(++seen>2000)throw new IllegalStateException("Too many folders to resolve safely.");
                if(name.equalsIgnoreCase(folder.path("displayName").asText()))matches.add(folder.path("id").asText());
            }
            url=page.path("@odata.nextLink").asText(null);
        }while(url!=null);
        if(matches.size()>1)throw new IllegalStateException("Destination name is ambiguous.");
        if(!matches.isEmpty() && payload.path("_destinationMode").asText().equals("NEW"))
            throw new IllegalStateException("A folder with this name already exists. Select it explicitly.");
        String id=matches.isEmpty()?graph.request(a,HttpMethod.POST,"/v1.0/me/mailFolders",Map.of("displayName",name)).path("id").asText():matches.getFirst();
        if(id.isBlank())throw new IllegalStateException("Destination creation needs verification.");
        // Never reuse Inbox or another built-in destination under an organize action.
        if(!matches.isEmpty())for(String system:List.of("inbox","deleteditems","junkemail","sentitems","drafts","outbox","archive")){
            try{
                var folder=graph.request(a,HttpMethod.GET,"/v1.0/me/mailFolders/"+system+"?$select=id",null);
                if(id.equals(folder.path("id").asText()))throw new IllegalStateException("Choose a custom folder name rather than a built-in folder.");
            }catch(com.tidymail.mail.CleanupProviderException unavailable){if(unavailable.status()!=404)throw unavailable;}
        }
        ObjectNode ready=payload.deepCopy();ready.remove(List.of("_destinationName","_destinationMode","_destinationId"));
        ((ObjectNode)ready.path("actions")).put("moveToFolder",id);return ready;
    }
    public JsonNode useDestination(JsonNode payload,String id){
        ObjectNode ready=payload.deepCopy();ready.remove(List.of("_destinationName","_destinationMode","_destinationId"));
        ((ObjectNode)ready.path("actions")).put("moveToFolder",id);return ready;
    }
    public String destinationId(JsonNode payload){return payload.path("actions").path("moveToFolder").asText(null);}
    public long destinationCount(UserAccount a,String id){
        var folder=graph.request(a,HttpMethod.GET,"/v1.0/me/mailFolders/"+org.springframework.web.util.UriUtils.encodePathSegment(id,java.nio.charset.StandardCharsets.UTF_8)+"?$select=totalItemCount",null);
        if(!folder.path("totalItemCount").canConvertToLong())throw new IllegalStateException("Destination count is unavailable.");
        return folder.path("totalItemCount").asLong();
    }
    public JsonNode withSenders(JsonNode raw,List<String> senders){
        ObjectNode payload=raw.deepCopy();var from=((ObjectNode)payload.path("conditions")).putArray("fromAddresses");
        for(String sender:senders)from.addObject().putObject("emailAddress").put("address",sender);return payload;
    }
    public void updateSenders(UserAccount a,String id,JsonNode payload){
        graph.request(a,HttpMethod.PATCH,path(id),Map.of("conditions",payload.path("conditions")));
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
