package com.tidymail.rules;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.google.api.services.gmail.model.*;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.gmail.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class GmailRuleProvider implements RuleProvider {
    private final GmailClientFactory clients;
    private final GmailRateLimiter quota;
    private final ObjectMapper json;
    public GmailRuleProvider(GmailClientFactory clients,GmailRateLimiter quota,ObjectMapper json) {
        this.clients=clients;this.quota=quota;this.json=json;
    }
    public String provider(){return "google";}
    public String scope(){return "https://www.googleapis.com/auth/gmail.settings.basic";}
    public String settingsUrl(){return "https://mail.google.com/mail/u/0/#settings/filters";}
    private interface Call<T>{T run(com.google.api.services.gmail.Gmail client)throws Exception;}
    private <T>T call(UserAccount a,int cost,Call<T> call){
        try { quota.charge(a.getId(),cost,false);return call.run(clients.buildFor(a)); }
        catch(Exception e){throw new IllegalStateException("Gmail rule request failed. Reconnect if permission is missing, then refresh rules before retrying.",e);}
    }
    public List<NativeRule> list(UserAccount a){
        var filters=call(a,1,c->c.users().settings().filters().list("me").execute()).getFilter();
        if(filters==null)return List.of();
        return filters.stream().map(f->{
            JsonNode raw;
            try { raw=json.readTree(com.google.api.client.json.jackson2.JacksonFactory.getDefaultInstance().toString(f)); }
            catch(Exception e){throw new IllegalStateException("Gmail rule response could not be read.",e);}
            return new NativeRule(f.getId(),"Gmail filter",true,false,raw,compatible(f),summary(f));
        }).toList();
    }
    private String summary(Filter f){
        var c=f.getCriteria();return c==null?"Native Gmail filter":Objects.toString(c.getFrom(),"Any sender")
            +(c.getSubject()==null?"":" · Subject: "+c.getSubject())
            +(c.getQuery()==null?"":" · "+c.getQuery());
    }
    private RuleDefinition compatible(Filter f){
        var c=f.getCriteria();var a=f.getAction();
        if(c==null||a==null||!c.getUnknownKeys().isEmpty()||!a.getUnknownKeys().isEmpty()||c.getFrom()==null||c.getTo()!=null||c.getSubject()!=null||c.getQuery()!=null
            ||c.getNegatedQuery()!=null||c.getHasAttachment()!=null||c.getSize()!=null||c.getSizeComparison()!=null||c.getExcludeChats()!=null||a.getForward()!=null)return null;
        var add=new HashSet<>(a.getAddLabelIds()==null?List.<String>of():a.getAddLabelIds());
        var remove=new HashSet<>(a.getRemoveLabelIds()==null?List.<String>of():a.getRemoveLabelIds());
        if(!Set.of("TRASH").containsAll(add)||!Set.of("UNREAD","INBOX").containsAll(remove))return null;
        boolean trash=add.contains("TRASH"),archive=remove.contains("INBOX"),read=remove.contains("UNREAD");
        if(trash&&archive||!trash&&!archive&&!read)return null;
        try { return new RuleDefinition("Imported Gmail filter",List.of(c.getFrom()),"","",
            trash?(read?"READ_TRASH":"TRASH"):archive?(read?"READ_ARCHIVE":"ARCHIVE"):"READ"); }
        catch(RuntimeException unsupported){return null;}
    }
    public JsonNode compile(UserAccount a,RuleDefinition d,List<NativeRule> inventory){
        ObjectNode root=json.createObjectNode(),criteria=root.putObject("criteria"),action=root.putObject("action");
        // A single address uses the native From field; multiple addresses use a bounded OR query.
        if(d.senders().size()==1)criteria.put("from",d.senders().getFirst());
        else criteria.put("from","{"+String.join(" ",d.senders())+"}");
        if(!d.subject().isEmpty())criteria.put("subject","\""+d.subject()+"\"");
        if(!d.exceptSubject().isEmpty())criteria.put("negatedQuery","subject:\""+d.exceptSubject()+"\"");
        if(d.trash())action.putArray("addLabelIds").add("TRASH");
        ArrayNode remove=json.createArrayNode();
        if(d.archive())remove.add("INBOX");if(d.read())remove.add("UNREAD");
        if(!remove.isEmpty())action.set("removeLabelIds",remove);
        return root;
    }
    public record Matches(Set<String> included,Set<String> beforeException) {}
    public Matches matching(UserAccount a,RuleDefinition d){
        String query="from:{"+String.join(" ",d.senders())+"}";
        if(!d.subject().isEmpty())query+=" subject:\""+d.subject()+"\"";
        Set<String> before=search(a,query);
        Set<String> included=d.exceptSubject().isEmpty()?before:search(a,query+" -subject:\""+d.exceptSubject()+"\"");
        return new Matches(included,before);
    }
    private Set<String> search(UserAccount a,String query){
        var ids=new HashSet<String>();String cursor=null;
        do {final String token=cursor;
            var page=call(a,5,c->c.users().messages().list("me").setQ(query).setIncludeSpamTrash(true).setMaxResults(500L).setPageToken(token).execute());
            if(page.getMessages()!=null)page.getMessages().forEach(m->ids.add(m.getId()));
            cursor=page.getNextPageToken();
            if(ids.size()>=10000&&cursor!=null)throw new IllegalStateException("This preview exceeds 10,000 provider matches. Narrow the sender/subject conditions.");
        }while(cursor!=null);return ids;
    }
    public String create(UserAccount a,JsonNode payload){
        return call(a,5,c->{var f=new Filter();
            var criteria=new FilterCriteria();var x=payload.path("criteria");
            if(x.has("from"))criteria.setFrom(x.get("from").asText());
            if(x.has("subject"))criteria.setSubject(x.get("subject").asText());
            if(x.has("negatedQuery"))criteria.setNegatedQuery(x.get("negatedQuery").asText());
            var action=new FilterAction();var y=payload.path("action");
            if(y.has("addLabelIds"))action.setAddLabelIds(strings(y.get("addLabelIds")));
            if(y.has("removeLabelIds"))action.setRemoveLabelIds(strings(y.get("removeLabelIds")));
            return c.users().settings().filters().create("me",f.setCriteria(criteria).setAction(action)).execute().getId();});
    }
    private static List<String> strings(JsonNode node){var out=new ArrayList<String>();node.forEach(v->out.add(v.asText()));return out;}
    public void pause(UserAccount a,String id){call(a,5,c->{c.users().settings().filters().delete("me",id).execute();return null;});}
    public void resume(UserAccount a,String id){throw new UnsupportedOperationException("Gmail resume recreates a saved filter.");}
    public JsonNode behavior(JsonNode raw){ObjectNode copy=raw.deepCopy();copy.remove("id");
        for(String field:List.of("addLabelIds","removeLabelIds"))if(copy.path("action").path(field).isArray()){
            var values=strings(copy.path("action").path(field));values=new ArrayList<>(values);Collections.sort(values);
            ArrayNode sorted=json.createArrayNode();values.forEach(sorted::add);((ObjectNode)copy.path("action")).set(field,sorted);
        }return copy;}
}
