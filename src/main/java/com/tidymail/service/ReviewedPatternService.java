package com.tidymail.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tidymail.repository.DashboardRepository;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Finite reviewed reuse of explicit corrections. No future-mail rule or inferred category writes. */
@Service
public class ReviewedPatternService {
    public static final String VERSION="reviewed-subject-v4";
    private static final Pattern SENSITIVE=Pattern.compile("(?i)security|password|sign.?in|log.?in|verification|verify|one.time|account|suspicious|authentication|\\botp\\b|\\b2fa\\b|passcode|two[ -]step|new[ -]device|unusual[ -]activity|your (?:login )?code|invite|invitation|calendar|meeting");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    public ReviewedPatternService(JdbcTemplate jdbc,ObjectMapper json,TransactionTemplate tx) {this.jdbc=jdbc;this.json=json;this.tx=tx;}
    public record Mail(UUID id,String address,String unit,String subject,String category,String kind,String hash,boolean calendar,String purpose,String snippet,
                       boolean correspondence,String reviewCode,String reviewReason) {
        public Mail(UUID id,String address,String unit,String subject,String category,String kind,String hash,boolean calendar,String purpose,String snippet,boolean correspondence) {
            this(id,address,unit,subject,category,kind,hash,calendar,purpose,snippet,correspondence,null,null);
        }
        Mail reason(Decision decision) {return new Mail(id,address,unit,subject,category,kind,hash,calendar,purpose,snippet,correspondence,decision.code(),decision.reason());}
    }
    public record Decision(boolean reusable,String code,String reason) {}
    public record Match(UUID id,String address,String subject,String label,String template,UUID sourceId,String sourceHash,String hash,String sourceSubject,String importance) {}
    public record Preview(UUID id,int months,String version,List<Match> matches,List<Mail> nearMisses,boolean limited,String reason) {}
    public record Apply(List<UUID> messages) {}

    public Preview preview(long account,int months) {
        if(months!=12 && months!=24) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose 12 or 24 months");
        var rows=jdbc.query("""
            SELECT m.dashboard_message_id,m.dashboard_from_address,m.dashboard_unit_kind||':'||m.dashboard_unit_key unit,
            m.subject_header,m.classification_override,m.classification_kind,dashboard_pattern_hash(m) hash,
            EXISTS(SELECT 1 FROM message_evidence e WHERE e.account_id=m.account_id AND e.provider_message_id=m.provider_message_id
              AND jsonb_array_length(e.facts->'calendars')>0) calendar,
            """+DashboardRepository.CATEGORY+"""
            purpose,m.snippet_text,(cardinality(m.references_values)>0 OR cardinality(m.in_reply_to_values)>0) correspondence
            FROM message_metadata m JOIN user_accounts a ON a.id=m.account_id
            WHERE m.account_id=? AND dashboard_reviewed_organization(m.dashboard_from_address) IN ('amazon.com','quora.com')
            AND m.received_at>=now()-make_interval(months=>?) AND m.received_at<=now() AND
            """+DashboardRepository.RECEIVED+" ORDER BY m.received_at DESC,m.dashboard_message_id LIMIT 5001",
            (r,n)->new Mail(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getBoolean(8),r.getString(9),r.getString(10),r.getBoolean(11)),account,months);
        // Capped input cannot establish consistent reviewed examples: do not offer any labels.
        if(rows.size()>5000)return save(account,new Preview(UUID.randomUUID(),months,VERSION,List.of(),List.of(),true,"More than 5,000 messages in scope; narrow the window before reviewing patterns"));
        var sources=new LinkedHashMap<String,Mail>();
        var conflicts=new HashSet<String>();
        for(var row:rows) if(row.category()!=null) {
            var previous=sources.putIfAbsent(key(row),row);
            if(previous!=null && !previous.category().equals(row.category()))conflicts.add(key(row));
        }
        var matches=new ArrayList<Match>(); var misses=new ArrayList<Mail>(); boolean limited=false;
        for(var row:rows) {
            var source=sources.get(key(row));
            Decision target=assess(row);
            Decision sourceDecision=source==null ? null : assess(source);
            if(source!=null && sourceDecision.reusable() && !conflicts.contains(key(row)) && row.category()==null && target.reusable()) {
                if(matches.size()<100)matches.add(new Match(row.id(),row.address(),row.subject(),source.category(),template(row.subject()),source.id(),source.hash(),row.hash(),source.subject(),"NOT_INFERRED"));
                else limited=true;
            } else if(row.category()==null && misses.size()<20) {
                Decision excluded=!target.reusable() ? target : conflicts.contains(key(row))
                    ? new Decision(false,"CONFLICTING_REFERENCES","Reviewed examples disagree on this subject pattern")
                    : source==null ? new Decision(false,"UNFAMILIAR_TEMPLATE","No reviewed example for this exact sender, mailing list and subject pattern")
                    : new Decision(false,"SOURCE_REQUIRES_REVIEW","The reviewed example needs separate inspection: "+sourceDecision.reason());
                misses.add(row.reason(excluded));
            }
        }
        return save(account,new Preview(UUID.randomUUID(),months,VERSION,matches,misses,limited,
            "Exact sender and list scope; subject numbers of six or more digits are normalized. Based only on your category corrections. Security, personal and calendar evidence blocks reuse. Pattern labels describe category only; importance and cleanup suitability need separate review. Labels do not change categories or move mail."));
    }
    private Preview save(long account,Preview preview) {
        try {jdbc.update("INSERT INTO dashboard_pattern_reviews(id,account_id,version,months,snapshot) VALUES(?,?,?,?,CAST(? AS jsonb))",preview.id(),account,VERSION,preview.months(),json.writeValueAsString(preview));return preview;}
        catch(Exception error){throw new IllegalStateException("Could not save pattern review",error);}
    }
    public int apply(long account,UUID review,List<UUID> ids) {
        if(ids==null || ids.isEmpty() || ids.size()>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose reviewed matches");
        return tx.execute(status->{
            jdbc.queryForObject("SELECT id FROM user_accounts WHERE id=? FOR UPDATE",Long.class,account);
            var snapshots=jdbc.queryForList("SELECT snapshot::text FROM dashboard_pattern_reviews WHERE account_id=? AND id=? AND applied_at IS NULL AND created_at>now()-interval '30 minutes' FOR UPDATE",String.class,account,review);
            if(snapshots.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"Pattern review expired or already applied. Preview again.");
            Preview preview;
            try {preview=json.readValue(snapshots.getFirst(),Preview.class);} catch(Exception error){throw new IllegalStateException(error);}
            if(!VERSION.equals(preview.version()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Pattern review changed. Preview again.");
            var matches=new HashMap<UUID,Match>();preview.matches().forEach(m->matches.put(m.id(),m));
            for(UUID id:new LinkedHashSet<>(ids)) {
                var match=matches.get(id);
                if(match==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose messages from this review");
                // Stable lock order for source and target. Metadata writes cannot race revalidation.
                jdbc.queryForList("SELECT id FROM message_metadata WHERE account_id=? AND dashboard_message_id IN (?,?) ORDER BY id FOR UPDATE",account,id,match.sourceId());
                var valid=jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM message_metadata m JOIN message_metadata source ON source.account_id=m.account_id
                    WHERE m.account_id=? AND m.dashboard_message_id=? AND source.dashboard_message_id=?
                    AND NOT EXISTS(SELECT 1 FROM message_evidence e WHERE e.account_id=m.account_id AND e.provider_message_id IN (m.provider_message_id,source.provider_message_id) AND jsonb_array_length(e.facts->'calendars')>0)
                    AND dashboard_pattern_hash(m)=? AND dashboard_pattern_hash(source)=? AND source.classification_override=?)
                    """,Boolean.class,account,id,match.sourceId(),match.hash(),match.sourceHash(),match.label());
                if(!Boolean.TRUE.equals(valid))throw new ResponseStatusException(HttpStatus.CONFLICT,"Message evidence changed. Preview patterns again.");
                jdbc.update("""
                    INSERT INTO dashboard_pattern_labels(account_id,message_id,review_id,source_id,source_hash,evidence_hash,label,template)
                    VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(account_id,message_id) DO UPDATE SET review_id=EXCLUDED.review_id,
                    source_id=EXCLUDED.source_id,source_hash=EXCLUDED.source_hash,evidence_hash=EXCLUDED.evidence_hash,label=EXCLUDED.label,template=EXCLUDED.template
                    """,account,id,review,match.sourceId(),match.sourceHash(),match.hash(),match.label(),match.template());
            }
            jdbc.update("UPDATE dashboard_pattern_reviews SET applied_at=now() WHERE account_id=? AND id=?",account,review);
            return new HashSet<>(ids).size();
        });
    }
    /** Local evidence is a reuse gate, never a claim that wording proves a category. */
    public static Decision assess(Mail m) {
        if(m.subject()==null || m.subject().isBlank() || m.unit()==null || m.address()==null)
            return new Decision(false,"INCOMPLETE_EVIDENCE","Sender or subject evidence is incomplete");
        if(m.address().substring(m.address().lastIndexOf('@')+1).equalsIgnoreCase("marketplace.amazon.com"))
            return new Decision(false,"RELAY_IDENTITY","Marketplace correspondent relay keeps its own identity and needs individual review");
        if("SECURITY".equals(m.category()) || "SECURITY".equals(m.purpose()))
            return new Decision(false,"SECURITY_CATEGORY","Security category requires individual review");
        if(Set.of("PERSON","RELAY").contains(Objects.toString(m.kind(),"")) || "PERSONAL".equals(m.category()) || "PERSONAL".equals(m.purpose()))
            return new Decision(false,"PERSONAL_IDENTITY","Personal or relay correspondence keeps its own identity and needs individual review");
        if(m.correspondence())return new Decision(false,"CORRESPONDENCE_CONTEXT","Reply or conversation headers require individual review");
        if(m.calendar())return new Decision(false,"CALENDAR_EVIDENCE","Calendar content was observed; event facts stay separate from category reuse");
        if(SENSITIVE.matcher(m.subject()+" "+Objects.toString(m.snippet(),"")).find())
            return new Decision(false,"SENSITIVE_WORDING","Access, security or invitation wording needs review; wording alone does not establish a category");
        return new Decision(true,"CATEGORY_ONLY","Eligible for reviewed category matching; importance and cleanup suitability are not inferred");
    }
    static String template(String subject) {return subject==null ? "" : subject.strip().toLowerCase(Locale.ROOT).replaceAll("\\d{6,}","{number}");}
    private String key(Mail m) {return m.address()+"\u0000"+m.unit()+"\u0000"+template(m.subject());}
}
