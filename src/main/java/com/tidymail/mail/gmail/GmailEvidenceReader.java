package com.tidymail.mail.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.http.HttpRequest;
import com.google.api.services.gmail.Gmail;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.EmailSnippet;
import com.tidymail.service.CalendarEvidenceParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;

/** Explicit read-only enrichment; browsing does not call this boundary. */
@Component
public class GmailEvidenceReader {
    public static final String VERSION = "mime-ics-v3";
    private final GmailClientFactory clients;
    private final GmailRateLimiter limiter;
    private final ObjectMapper json;
    private final CalendarEvidenceParser calendars;
    public GmailEvidenceReader(GmailClientFactory clients,GmailRateLimiter limiter,ObjectMapper json,CalendarEvidenceParser calendars) {
        this.clients=clients;this.limiter=limiter;this.json=json;this.calendars=calendars;
    }
    public record Part(String id,String mimeType,String filename,Long bytes,boolean inline,boolean calendar) {}
    public record Evidence(String version,EmailSnippet snippet,String structureState,String attachmentState,
                           List<Part> parts,List<CalendarEvidenceParser.Facts> calendars,int requests,long responseBytes) {}
    public Evidence read(UserAccount account,String messageId) {
        Gmail gmail=clients.buildFor(account);
        try {
            var request=gmail.users().messages().get("me",messageId).setFormat("FULL")
                .setFields("id,snippet,payload("+fields(6)+")").buildHttpRequest();
            var reads=new Reads(account.getId());
            var root=reads.get(request);
            if (!messageId.equals(root.path("id").asText()) || root.has("raw")) throw new IOException("Unexpected message projection");
            var nodes=new ArrayList<JsonNode>();
            boolean complete=flatten(root.path("payload"),nodes,0);
            var parts=new ArrayList<Part>(); var facts=new ArrayList<CalendarEvidenceParser.Facts>();
            var fetchedParts=new HashMap<String,CalendarEvidenceParser.Facts>();
            int calendarReads=0; boolean hasAttachment=false, ambiguousAttachment=false;
            for (var node:nodes) {
                String type=node.path("mimeType").asText(),name=EmailSnippet.bound(node.path("filename").asText(),512);
                if (node.path("body").has("data")) throw new IOException("Unexpected content in structure response");
                boolean calendar=type.equalsIgnoreCase("text/calendar") || name.toLowerCase(Locale.ROOT).endsWith(".ics");
                String disposition="";
                for(var h:node.path("headers")) if(h.path("name").asText().equalsIgnoreCase("Content-Disposition")) disposition=h.path("value").asText().stripLeading().toLowerCase(Locale.ROOT);
                hasAttachment |= !name.isBlank() || disposition.startsWith("attachment");
                ambiguousAttachment |= name.isBlank() && !disposition.startsWith("inline") && !disposition.startsWith("attachment")
                    && !type.startsWith("multipart/") && !type.equalsIgnoreCase("text/plain") && !type.equalsIgnoreCase("text/html");
                parts.add(new Part(node.path("partId").asText(),EmailSnippet.bound(type,128),name,
                    node.path("body").path("size").isIntegralNumber()?node.path("body").path("size").asLong():null,
                    disposition.startsWith("inline"),calendar));
                if (!calendar) continue;
                String attachment=node.path("body").path("attachmentId").asText();
                var size=node.path("body").path("size");
                if (attachment.isBlank()) { facts.add(new CalendarEvidenceParser.Facts("UNSUPPORTED",List.of(),"Inline calendar content has not been retrieved")); continue; }
                if (fetchedParts.containsKey(attachment)) {
                    var previous=fetchedParts.get(attachment);
                    facts.add(previous.source(node.path("partId").asText(),previous.contentHash()));
                    continue;
                }
                if (!size.isIntegralNumber() || size.asLong()<0 || size.asLong()>131072 || calendarReads>=4) {
                    facts.add(new CalendarEvidenceParser.Facts("TRUNCATED",List.of(),"Calendar read limit reached")); continue;
                }
                calendarReads++;
                try {
                    var result=reads.get(gmail.users().messages().attachments().get("me",messageId,attachment).buildHttpRequest());
                    byte[] data=Base64.getUrlDecoder().decode(result.path("data").asText());
                    if (data.length>131072) { facts.add(new CalendarEvidenceParser.Facts("TRUNCATED",List.of(),"Calendar part too large")); continue; }
                    String text=new String(data,StandardCharsets.UTF_8);
                    var parsed=calendars.parse(text).source(node.path("partId").asText(),CalendarEvidenceParser.hash(text));
                    fetchedParts.put(attachment,parsed);facts.add(parsed);
                } catch (Exception ignored) { facts.add(new CalendarEvidenceParser.Facts("FAILED",List.of(),"Calendar content could not be retrieved")); }
            }
            // Inline images are content, not proof of an ordinary downloadable attachment.
            return new Evidence(VERSION,EmailSnippet.observed(root.path("snippet").isTextual()?root.path("snippet").asText():null),
                complete?"AVAILABLE":"TRUNCATED",hasAttachment?"PRESENT":complete&&!ambiguousAttachment?"ABSENT":"UNKNOWN",List.copyOf(parts),List.copyOf(facts),reads.count,reads.bytes);
        } catch (Exception ignored) { throw new IllegalStateException("Could not check this email. Reconnect Gmail if needed, then try again."); }
    }
    private class Reads {
        final Long account; int count; long bytes;
        Reads(Long account){this.account=account;}
        JsonNode get(HttpRequest request) throws IOException {
            if(++count>5)throw new IOException("Read limit reached");
            limiter.charge(account,5,true);
            return limiter.supplyWithBackfillRescanPermit(() -> {
                request.setNumberOfRetries(0); request.setConnectTimeout(5000);request.setReadTimeout(10000);
                request.setFollowRedirects(false);request.setLoggingEnabled(false);
                request.setUnsuccessfulResponseHandler(null);request.setIOExceptionHandler(null);
                try {
                    var response=request.execute();
                    try {
                        byte[] data=response.getContent().readNBytes(1048577);bytes+=data.length;
                        if(data.length>1048576||bytes>5242880)throw new IOException("Evidence limit reached");
                        return json.readTree(data);
                    } finally { response.disconnect(); }
                } catch(IOException ignored){throw new IllegalStateException("Evidence read failed");}
            });
        }
    }
    private static String fields(int depth) {
        String f="partId,mimeType,filename,headers(name,value),body(size,attachmentId)";
        return depth==0?f:f+",parts("+fields(depth-1)+")";
    }
    private static boolean flatten(JsonNode node,List<JsonNode> nodes,int depth) {
        if(!node.isObject()||node.path("mimeType").asText().isBlank()||nodes.size()>=128)return false;
        nodes.add(node);
        // At mask depth six, children may have been omitted. Never infer complete absence.
        if(depth>=6)return false;
        boolean complete=true;
        for(var part:node.path("parts"))complete=flatten(part,nodes,depth+1)&&complete;
        return complete;
    }
}
