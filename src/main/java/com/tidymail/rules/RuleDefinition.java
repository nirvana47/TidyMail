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
        if(senders == null || senders.isEmpty()) fail("Choose at least one sender address or @domain.");
        senders = senders.stream().map(RuleDefinition::senderEntry).distinct().sorted().toList();
        // Restrict to plain words; prevent native search operators from changing the sender/subject scope.
        subject = cleanPhrases(subject);
        exceptSubject = cleanPhrases(exceptSubject);
        if(!ACTIONS.containsKey(action)) fail("Choose a supported action.");
    }
    private static boolean validDomain(String domain){
        return domain.length()<=253&&domain.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*\\.[a-z]{2,63}");
    }
    public static String senderEntry(String input){
        String value=Objects.toString(input,"").strip().toLowerCase(Locale.ROOT);
        if(!value.contains("@")&&validDomain(value))value="@"+value;
        if(value.startsWith("@")&&validDomain(value.substring(1)))return value;
        int at=value.indexOf('@');
        if(at>0&&value.length()<=320&&value.substring(0,at).matches("[a-z0-9][a-z0-9._%+\\-]*")&&validDomain(value.substring(at+1)))return value;
        fail("Enter complete email addresses or @domains, separated by commas. Wildcards and search operators are not supported.");return "";
    }
    public boolean hasDomains(){return senders.stream().anyMatch(v->v.startsWith("@"));}
    /** Provider domain conditions are sender-text matches, not a promise of a strict DNS boundary. */
    public boolean matchesSender(String address,String provider){
        String value=Objects.toString(address,"").toLowerCase(Locale.ROOT);
        boolean contains=hasDomains()&&"microsoft".equals(provider);
        return senders.stream().anyMatch(entry->matchesEntry(entry,value,contains));
    }
    public static boolean matchesEntry(String entry,String address,boolean contains){
        String value=Objects.toString(address,"").toLowerCase(Locale.ROOT);
        return entry.startsWith("@")||contains?value.contains(entry):value.equals(entry);
    }
    public static boolean entriesOverlap(String first,String second,boolean contains){
        return first.equals(second)||((first.startsWith("@")||contains)&&second.contains(first))
            ||((second.startsWith("@")||contains)&&first.contains(second));
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
        return (organize()?"Organize into “"+destination+"”"+(skipInbox?" and skip the Inbox":""):actionLabel(provider))+(hasDomains()?("microsoft".equals(provider)?" for incoming mail whose sender address includes any of: ":" for incoming mail matching any From entry: "):" for incoming email from ")+String.join(", ",senders)
            +(subject.isEmpty()?"":" with any subject phrase “"+String.join("”, “",subjects())+"”")
            +(exceptSubject.isEmpty()?"":" except any subject phrase “"+String.join("”, “",exceptions())+"”")+".";
    }
    public static void fail(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
