package com.tidymail.rules;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Sender candidates are deliberately distinct from a verified whole-rule match. */
public final class RuleOverlap {
    private RuleOverlap() {}
    public record Sender(String address,String condition) {}
    public record Condition(String name,String value) {}
    public record Assessment(List<Sender> senders,String reason,boolean uncertain) {
        public boolean possible(){return uncertain||!senders.isEmpty();}
    }
    public static Assessment assess(RuleDefinition draft,RuleProvider.NativeRule rule){
        if(rule.compatible()!=null){
            var senders=draft.senders().stream().filter(rule.compatible().senders()::contains).map(s->new Sender(s,s)).toList();
            return new Assessment(senders,"These addresses are listed in both rules. Other conditions still need to match.",false);
        }
        var raw=rule.raw();
        if(raw.has("criteria")){
            String from=raw.path("criteria").path("from").asText("").strip().toLowerCase(Locale.ROOT);
            if(from.isBlank())return new Assessment(draft.senders().stream().map(s->new Sender(s,"No separate From restriction")).toList(),
                "This filter has no separate From restriction. Its search or other conditions may narrow which senders qualify.",false);
            // Only positive OR lists are safe to narrow locally. Names, AND, negation and search operators need provider evaluation.
            var terms=Arrays.stream(from.replaceAll("[{}()\\\",|]"," ").split("\\s+")).filter(t->!t.isBlank()&&!t.equals("or")).toList();
            if(terms.isEmpty()||terms.stream().anyMatch(t->!t.matches("[a-z0-9][a-z0-9._%+@\\-]*\\.[a-z]{2,63}")||t.startsWith("-")))
                return new Assessment(List.of(),"This filter uses a complex From condition. The affected addresses have not been identified; check all selected senders below.",true);
            var senders=draft.senders().stream().map(s->new Sender(s,String.join(" OR ",terms.stream().filter(t->t.contains("@")?s.equals(t):s.contains(t)).toList())))
                .filter(s->!s.condition().isEmpty()).toList();
            return new Assessment(senders,"These addresses contain a term from the filter’s From condition. This is a possible sender overlap, not a full-rule match.",false);
        }
        var from=raw.path("conditions").path("fromAddresses");
        if(from.isEmpty())return new Assessment(draft.senders().stream().map(s->new Sender(s,"No sender restriction")).toList(),
            "This rule has no sender restriction. Its other conditions and exceptions still need to match.",false);
        var addresses=new HashSet<String>();for(var sender:from)addresses.add(sender.path("emailAddress").path("address").asText().toLowerCase(Locale.ROOT));
        return new Assessment(draft.senders().stream().filter(addresses::contains).map(s->new Sender(s,s)).toList(),
            "These addresses are listed in both rules. Other conditions and exceptions still need to match.",false);
    }
    public static List<Condition> conditions(JsonNode raw){
        var out=new ArrayList<Condition>();
        if(raw.has("criteria")){
            var names=Map.of("from","From","to","Recipient (To, Cc or Bcc)","subject","Subject phrase","query","Also must match this search","negatedQuery","Must not match this search","hasAttachment","Has attachment","excludeChats","Exclude chats","size","Message size (bytes)","sizeComparison","Size comparison");
            raw.path("criteria").fields().forEachRemaining(f->{if(!f.getValue().isNull())out.add(new Condition(names.getOrDefault(f.getKey(),"Additional condition: "+f.getKey()),display(f.getValue())));});
        }else{
            for(String section:List.of("conditions","exceptions"))raw.path(section).fields().forEachRemaining(f->{
                var v=f.getValue();if(v.isNull()||v.isArray()&&v.isEmpty()||v.isBoolean()&&!v.asBoolean())return;
                String name=switch(f.getKey()){case "fromAddresses"->"From";case "subjectContains"->"Subject contains any";case "bodyContains"->"Body contains any";case "bodyOrSubjectContains"->"Body or subject contains any";case "hasAttachments"->"Has attachments";default->f.getKey();};
                out.add(new Condition((section.equals("exceptions")?"Except: ":"")+name,display(v)));
            });
        }
        if(out.isEmpty())out.add(new Condition("Conditions","No conditions supplied by the mailbox provider"));
        return out;
    }
    private static String display(JsonNode value){
        if(value.isTextual())return value.asText();
        if(value.isArray()){var values=new ArrayList<String>();for(var v:value)values.add(v.has("emailAddress")?v.path("emailAddress").path("address").asText():display(v));return String.join(", ",values);}
        return value.toString();
    }
    public static String action(RuleProvider.NativeRule rule,String provider){
        if(rule.compatible()!=null)return rule.compatible().reviewAction(provider);
        var raw=rule.raw();var actions=new ArrayList<String>();
        if(raw.has("action")){
            var add=new HashSet<String>();raw.path("action").path("addLabelIds").forEach(v->add.add(v.asText()));
            var remove=new HashSet<String>();raw.path("action").path("removeLabelIds").forEach(v->remove.add(v.asText()));
            if(add.contains("TRASH"))actions.add("Move matching mail to Trash");
            if(remove.contains("INBOX"))actions.add("Skip the Inbox");
            if(remove.contains("UNREAD"))actions.add("Mark as read");
            if(add.stream().anyMatch(v->!v.equals("TRASH")))actions.add("Apply labels");
            if(raw.path("action").hasNonNull("forward"))actions.add("Forward matching mail");
        }else{
            var a=raw.path("actions");
            if(a.path("delete").asBoolean())actions.add("Move matching mail to Deleted Items");
            if(a.hasNonNull("moveToFolder"))actions.add("Move matching mail to an Outlook folder");
            if(a.path("markAsRead").asBoolean())actions.add("Mark as read");
            if(a.path("stopProcessingRules").asBoolean())actions.add("Stop processing later rules");
        }
        return actions.isEmpty()?"Additional mailbox actions (see full rule below)":String.join("; ",actions);
    }
}
