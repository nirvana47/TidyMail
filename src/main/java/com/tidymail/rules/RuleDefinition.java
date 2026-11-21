package com.tidymail.rules;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The small common vocabulary exposed by both providers. */
public record RuleDefinition(String name, List<String> senders, String subject, String exceptSubject, String action, String destination, boolean skipInbox, String destinationId, String destinationMode) {
    public RuleDefinition(String name,List<String> senders,String subject,String exceptSubject,String action) {
        this(name,senders,subject,exceptSubject,action,"",false);
    }
    public RuleDefinition(String name,List<String> senders,String subject,String exceptSubject,String action,String destination,boolean skipInbox) {
        this(name,senders,subject,exceptSubject,action,destination,skipInbox,"","LEGACY");
    }
    public static final Map<String,String> ACTIONS = Map.of(
        "ORGANIZE","Apply label / Move to folder",
        "ARCHIVE","Archive", "READ_ARCHIVE","Mark as read + Archive", "READ","Mark as read",
        "TRASH","Move to Trash", "READ_TRASH","Mark as read + Move to Trash");

    public RuleDefinition {
        name = Objects.toString(name, "").strip();
        destination = Objects.toString(destination, "").strip();
        destinationId = Objects.toString(destinationId, "").strip();
        destinationMode = Objects.toString(destinationMode, "LEGACY");
        if(!Set.of("EXISTING","NEW","LEGACY").contains(destinationMode)) fail("Choose an existing destination or create a new one.");
        if("ORGANIZE".equals(action)) {
            if(destination.isEmpty() || destination.length()>500 || destination.chars().anyMatch(c -> Character.isISOControl(c)))
                fail("Give the destination a name (up to 100 characters, without slashes or control characters).");
            if(destinationMode.equals("EXISTING") && destinationId.isEmpty()) fail("Select an existing label or folder.");
            if(destinationMode.equals("NEW")) {
                destinationId="";
                if(destination.length()>100 || destination.contains("/") || destination.contains("\\")) fail("Use a new name up to 100 characters without slashes.");
            }
        } else { destination=""; skipInbox=false; destinationId=""; destinationMode="LEGACY"; }
        subject = Objects.toString(subject, "").strip();
        exceptSubject = Objects.toString(exceptSubject, "").strip();
        if(name.isEmpty() || name.length()>100) fail("Give the rule a name (up to 100 characters).");
        if(senders == null || senders.isEmpty()) fail("Choose at least one exact sender address.");
        senders = senders.stream().map(s -> s.strip().toLowerCase(Locale.ROOT)).distinct().sorted().toList();
        for(String sender : senders)
            if(!sender.matches("[a-z0-9][a-z0-9._%+\\-]*@[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,63}") || sender.length()>320)
                fail("Enter email addresses, separated by commas. Display names and domains alone are not supported.");
        // Restrict to plain words; prevent native search operators from changing the sender/subject scope.
        subject = cleanPhrases(subject);
        exceptSubject = cleanPhrases(exceptSubject);
        if(!ACTIONS.containsKey(action)) fail("Choose a supported action.");
    }
    private static String cleanPhrases(String text) {
        var phrases=Arrays.stream(text.split("\\R")).map(String::strip).filter(v->!v.isEmpty()).distinct().toList();
        if(phrases.size()>20 || phrases.stream().anyMatch(v->!v.matches("[\\p{L}\\p{N} ]{1,100}")))
            fail("Use up to 20 subject phrases, each with letters, numbers and spaces (up to 100 characters).");
        return String.join("\n",phrases);
    }
    public List<String> subjects() { return subject.isEmpty()?List.of():List.of(subject.split("\\R")); }
    public List<String> exceptions() { return exceptSubject.isEmpty()?List.of():List.of(exceptSubject.split("\\R")); }
    public boolean trash() { return action.contains("TRASH"); }
    public boolean organize() { return action.equals("ORGANIZE"); }
    public boolean archive() { return action.contains("ARCHIVE") || organize() && skipInbox; }
    public boolean read() { return action.startsWith("READ"); }
    public String actionLabel() { return ACTIONS.get(action); }
    public String actionLabel(String provider) {return "microsoft".equals(provider)?actionLabel().replace("Trash","Deleted Items"):actionLabel();}
    public String reviewAction(String provider) {
        if(!organize())return actionLabel(provider)+" for matching incoming mail.";
        return "microsoft".equals(provider)?"Move matching incoming mail to “"+destination+"”.":
            "Apply label “"+destination+"”"+(skipInbox?" and remove matching incoming mail from Inbox.":" to matching incoming mail. Keep it in Inbox.");
    }
    public String sentence() {return sentence("google");}
    public String sentence(String provider) {
        return (organize()?"Organize into “"+destination+"”"+(skipInbox?" and skip the Inbox":""):actionLabel(provider))+" for incoming email from "+String.join(", ",senders)
            +(subject.isEmpty()?"":" with any subject phrase “"+String.join("”, “",subjects())+"”")
            +(exceptSubject.isEmpty()?"":" except any subject phrase “"+String.join("”, “",exceptions())+"”")+".";
    }
    public static void fail(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
