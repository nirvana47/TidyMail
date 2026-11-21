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
        try {
            String from=c.getFrom().strip();
            boolean orList=from.startsWith("{")&&from.endsWith("}");
            if(orList)from=from.substring(1,from.length()-1);
            var entries=Arrays.stream(from.split("\\s+")).toList();
            if(entries.size()>1&&!orList)return null;
            for(String entry:entries)if(!RuleDefinition.senderEntry(entry).equalsIgnoreCase(entry))return null;
            return new RuleDefinition("Imported Gmail filter",entries,"","",
            trash?(read?"READ_TRASH":"TRASH"):archive?(read?"READ_ARCHIVE":"ARCHIVE"):"READ"); }
        catch(RuntimeException unsupported){return null;}
    }
    public JsonNode compile(UserAccount a,RuleDefinition d,List<NativeRule> inventory){
        ObjectNode root=json.createObjectNode(),criteria=root.putObject("criteria"),action=root.putObject("action");
        // A single address uses the native From field; multiple addresses use a bounded OR query.
        if(d.senders().size()==1)criteria.put("from",d.senders().getFirst());
        else criteria.put("from","{"+String.join(" ",d.senders())+"}");
        if(d.subjects().size()==1)criteria.put("subject","\""+d.subject()+"\"");
        else if(!d.subjects().isEmpty())criteria.put("query",subjectQuery(d.subjects()));
        if(!d.exceptSubject().isEmpty())criteria.put("negatedQuery",subjectQuery(d.exceptions()));
        if(d.organize()) {
            root.put("_destinationName",d.destination());root.put("_destinationMode",d.destinationMode());
            if(!d.destinationId().isEmpty())root.put("_destinationId",d.destinationId());
        }
        if(d.trash())action.putArray("addLabelIds").add("TRASH");
        ArrayNode remove=json.createArrayNode();
        if(d.archive())remove.add("INBOX");if(d.read())remove.add("UNREAD");
        if(!remove.isEmpty())action.set("removeLabelIds",remove);
        return root;
    }
    public JsonNode prepare(UserAccount a,JsonNode payload){
        if(!payload.has("_destinationName"))return payload;
        String name=payload.path("_destinationName").asText();
        if(payload.path("_destinationMode").asText().equals("EXISTING")) {
            String id=payload.path("_destinationId").asText();
            if(destinations(a).stream().noneMatch(d->d.id().equals(id)))throw new IllegalStateException("Selected label is unavailable.");
            return useDestination(payload,id);
        }
        var labels=call(a,1,c->c.users().labels().list("me").execute()).getLabels();
        var matches=labels==null?List.<Label>of():labels.stream().filter(l->name.equalsIgnoreCase(l.getName())).toList();
        if(matches.size()>1)throw new IllegalStateException("Destination name is ambiguous.");
        if(!matches.isEmpty()&&!"user".equals(matches.getFirst().getType()))
            throw new IllegalStateException("Choose a custom label name rather than a Gmail system label.");
        if(!matches.isEmpty() && payload.path("_destinationMode").asText().equals("NEW"))
            throw new IllegalStateException("A label with this name already exists. Select it explicitly.");
        String id=matches.isEmpty()?call(a,5,c->c.users().labels().create("me",new Label().setName(name)
            .setLabelListVisibility("labelShow").setMessageListVisibility("show")).execute()).getId():matches.getFirst().getId();
        if(id==null||id.isBlank())throw new IllegalStateException("Destination creation needs verification.");
        ObjectNode ready=payload.deepCopy();ready.remove(List.of("_destinationName","_destinationMode","_destinationId"));
        ((ObjectNode)ready.path("action")).putArray("addLabelIds").add(id);return ready;
    }
    public JsonNode useDestination(JsonNode payload,String id){
        ObjectNode ready=payload.deepCopy();ready.remove(List.of("_destinationName","_destinationMode","_destinationId"));
        ((ObjectNode)ready.path("action")).putArray("addLabelIds").add(id);return ready;
    }
    public String destinationId(JsonNode payload){
        for(var id:payload.path("action").path("addLabelIds"))if(!id.asText().equals("TRASH"))return id.asText();
        return null;
    }
    public long destinationCount(UserAccount a,String id){
        Integer count=call(a,1,c->c.users().labels().get("me",id).execute()).getMessagesTotal();
        if(count==null)throw new IllegalStateException("Destination count is unavailable.");return count;
    }
    public record Matches(Set<String> included,Set<String> beforeException) {}
    public Matches matching(UserAccount a,RuleDefinition d){
        String query="from:{"+String.join(" ",d.senders())+"}";
        if(!d.subject().isEmpty())query+=" "+subjectQuery(d.subjects());
        Set<String> before=search(a,query);
        Set<String> included=d.exceptSubject().isEmpty()?before:search(a,query+" -"+subjectQuery(d.exceptions()));
        return new Matches(included,before);
    }
    /** One combined, read-only search for all selected addresses, bounded by search()'s 10,000-match ceiling. */
    public Set<String> matchingInbox(UserAccount a,JsonNode filter,List<String> senders){return search(a,inboxFilterQuery(filter,senders));}
    public static String inboxFilterQuery(JsonNode filter,List<String> senders){
        // Validate selected addresses independently of the native search expression.
        var selected=new RuleDefinition("Check existing filter",senders,"","","READ");
        var c=filter.path("criteria");
        if(!c.isObject())throw new IllegalStateException("The full filter conditions are unavailable. Review this filter in Gmail.");
        var allowed=Set.of("from","to","subject","query","negatedQuery","hasAttachment","excludeChats","size","sizeComparison");
        c.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))throw new IllegalStateException("This filter uses conditions that cannot be checked safely here. Review the full filter in Gmail.");});
        var parts=new ArrayList<String>();parts.add("in:inbox");parts.add("from:{"+String.join(" ",selected.senders())+"}");
        for(String field:List.of("from","query","negatedQuery")){
            String text=c.path(field).asText("").strip();if(text.isEmpty())continue;requireBalancedQuery(text);
            parts.add((field.equals("from")?"from:":field.equals("negatedQuery")?"-":"")+"("+text+")");
        }
        String to=c.path("to").asText("").strip();
        if(!to.isEmpty()){requireBalancedQuery(to);parts.add("{to:("+to+") cc:("+to+") bcc:("+to+")}");}
        String subject=c.path("subject").asText("").strip();
        if(!subject.isEmpty()){
            if(subject.startsWith("\"")&&subject.endsWith("\"")&&subject.length()>1)subject=subject.substring(1,subject.length()-1);
            if(subject.contains("\"")||subject.contains("\\"))throw new IllegalStateException("This subject expression needs review in Gmail; no partial match will be reported as verified.");
            parts.add("subject:\""+subject+"\"");
        }
        if(c.hasNonNull("hasAttachment")){
            if(!c.path("hasAttachment").asBoolean())throw new IllegalStateException("This attachment condition needs review in Gmail.");
            parts.add("has:attachment");
        }
        if(c.path("excludeChats").asBoolean())parts.add("-is:chat");
        if(c.hasNonNull("size")){
            String comparison=c.path("sizeComparison").asText();
            if(!Set.of("smaller","larger").contains(comparison)||!c.path("size").canConvertToLong()||c.path("size").asLong()<0)
                throw new IllegalStateException("This size condition cannot be checked safely. Review it in Gmail.");
            parts.add(comparison+":"+c.path("size").asLong());
        }else if(c.hasNonNull("sizeComparison")&&!c.path("sizeComparison").asText().equals("unspecified"))
            throw new IllegalStateException("The filter’s size condition is incomplete. Review it in Gmail.");
        return String.join(" ",parts);
    }
    private static void requireBalancedQuery(String query){
        var groups=new ArrayDeque<Character>();boolean quoted=false,escaped=false;
        for(char ch:query.toCharArray()){
            if(escaped){escaped=false;continue;}if(ch=='\\'){escaped=true;continue;}if(ch=='"'){quoted=!quoted;continue;}
            if(quoted)continue;if(ch=='('||ch=='{')groups.push(ch);
            else if(ch==')'||ch=='}')if(groups.isEmpty()||groups.pop()!=(ch==')'?'(':'{'))throw new IllegalStateException("This search expression needs review in Gmail.");
        }
        if(quoted||escaped||!groups.isEmpty())throw new IllegalStateException("This search expression needs review in Gmail.");
    }
    public static String subjectQuery(List<String> phrases) {
        return "{"+String.join(" ",phrases.stream().map(p->"subject:\""+p+"\"").toList())+"}";
    }
    public record Destination(String id,String name) {}
    public List<Destination> destinations(UserAccount a) {
        var labels=call(a,1,c->c.users().labels().list("me").execute()).getLabels();
        return labels==null?List.of():labels.stream().filter(l->"user".equals(l.getType()))
            .map(l->new Destination(l.getId(),l.getName())).sorted(Comparator.comparing(Destination::name,String.CASE_INSENSITIVE_ORDER)).toList();
    }
    private Set<String> search(UserAccount a,String query){
        var ids=new HashSet<String>();String cursor=null;
        do {final String token=cursor;
            var page=call(a,5,c->c.users().messages().list("me").setQ(query).setIncludeSpamTrash(true).setMaxResults(500L).setPageToken(token).execute());
            if(page.getMessages()!=null)page.getMessages().forEach(m->ids.add(m.getId()));
            cursor=page.getNextPageToken();
            if(ids.size()>10000||ids.size()>=10000&&cursor!=null)throw new IllegalStateException("This preview exceeds 10,000 provider matches. Narrow the sender/subject conditions.");
        }while(cursor!=null);return ids;
    }
    public JsonNode withSenders(JsonNode raw,List<String> senders){
        ObjectNode payload=raw.deepCopy();payload.remove("id");
        ((ObjectNode)payload.path("criteria")).put("from",senders.size()==1?senders.getFirst():"{"+String.join(" ",senders)+"}");return payload;
    }
    public String create(UserAccount a,JsonNode payload){
        return call(a,5,c->{var f=new Filter();
            var criteria=new FilterCriteria();var x=payload.path("criteria");
            if(x.has("from"))criteria.setFrom(x.get("from").asText());
            if(x.has("query"))criteria.setQuery(x.get("query").asText());
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
