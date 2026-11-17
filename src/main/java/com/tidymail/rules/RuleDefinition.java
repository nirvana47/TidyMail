package com.tidymail.rules;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The small common vocabulary exposed by both providers. */
public record RuleDefinition(String name, List<String> senders, String subject, String exceptSubject, String action) {
    public static final Map<String,String> ACTIONS = Map.of(
        "ARCHIVE","Archive", "READ_ARCHIVE","Mark as read + Archive", "READ","Mark as read",
        "TRASH","Move to Trash", "READ_TRASH","Mark as read + Move to Trash");

    public RuleDefinition {
        name = Objects.toString(name, "").strip();
        subject = Objects.toString(subject, "").strip();
        exceptSubject = Objects.toString(exceptSubject, "").strip();
        if(name.isEmpty() || name.length()>100) fail("Give the rule a name (up to 100 characters).");
        if(senders == null || senders.isEmpty() || senders.size()>20) fail("Choose 1–20 exact sender addresses.");
        senders = senders.stream().map(s -> s.strip().toLowerCase(Locale.ROOT)).distinct().sorted().toList();
        for(String sender : senders)
            if(!sender.matches("[a-z0-9][a-z0-9._%+\\-]*@[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,63}") || sender.length()>320)
                fail("Enter email addresses, separated by commas. Display names and domains alone are not supported.");
        // Restrict to plain words; prevent native search operators from changing the sender/subject scope.
        if(!subject.matches("[\\p{L}\\p{N} ]{0,100}") || !exceptSubject.matches("[\\p{L}\\p{N} ]{0,100}"))
            fail("Subject phrases can contain letters, numbers and spaces (up to 100 characters).");
        if(!ACTIONS.containsKey(action)) fail("Choose a supported action.");
    }
    public boolean trash() { return action.contains("TRASH"); }
    public boolean archive() { return action.contains("ARCHIVE"); }
    public boolean read() { return action.startsWith("READ"); }
    public String actionLabel() { return ACTIONS.get(action); }
    public String sentence() {
        return actionLabel()+" for incoming email from "+String.join(", ",senders)
            +(subject.isEmpty()?"":" with subject phrase “"+subject+"”")
            +(exceptSubject.isEmpty()?"":" except subject phrase “"+exceptSubject+"”")+".";
    }
    public static void fail(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
