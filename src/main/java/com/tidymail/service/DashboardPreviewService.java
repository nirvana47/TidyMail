package com.tidymail.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tidymail.dto.DashboardFilter;
import com.tidymail.repository.DashboardRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Exact, persisted previews. No mutation or legacy approval dispatch occurs here. */
@Service
public class DashboardPreviewService {
    private final DashboardRepository repository;
    private final ActionScopeHasher hasher;
    private final ObjectMapper json;
    public DashboardPreviewService(DashboardRepository repository,ActionScopeHasher hasher,ObjectMapper json) {
        this.repository=repository;this.hasher=hasher;this.json=json;
    }
    public record Request(DashboardFilter filter,List<UUID> ids,String action,long revision) {}
    public record Preview(UUID id,String action,int selected,int effective,int skipped,int protectedCount,DashboardFilter filter,Map<String,Object> coverage,Instant expiresAt) {}
    public record Targets(List<Map<String,Object>> rows,long total,int page) {}

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Preview create(long account,Request request) {
        if(request==null || request.filter()==null || request.ids()==null || request.ids().isEmpty()
            || !Set.of("ARCHIVE","MARK_READ","TRASH").contains(Objects.toString(request.action(),""))) throw bad("Choose messages and an action");
        var jdbc=repository.jdbc();
        jdbc.queryForObject("SELECT id FROM user_accounts WHERE id=:account FOR UPDATE",Map.of("account",account),Long.class);
        if(request.revision()!=repository.revision(account)) throw conflict("Grouping changed. Create a new preview.");
        var ids=request.ids().stream().distinct().toList();
        var p=repository.parameters(account,request.filter()).addValue("ids",ids);
        var targets=jdbc.queryForList(DashboardRepository.FILTERED+"""
          SELECT m.provider_message_id,m.dashboard_message_id,m.subject_header,
            COALESCE(m.dashboard_from_address,m.from_address,'Unknown sender') address,m.received_at,
            f.id IS NOT NULL AS eligible,'INBOX'=ANY(m.labels) inbox,m.unread,
            EXISTS(SELECT 1 FROM protected_senders protected WHERE protected.account_id=m.account_id
              AND protected.sender_address=lower(COALESCE(m.dashboard_from_address,m.from_address,''))) protected
          FROM message_metadata m LEFT JOIN filtered f ON f.id=m.id
          WHERE m.account_id=:account AND m.dashboard_message_id IN (:ids) ORDER BY m.provider_message_id
          """,p);
        if(targets.size()!=ids.size()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Some selected messages are unavailable for this account. Refresh selection.");
        int effective=0,protectedCount=0;
        for(var target:targets) {
            boolean protectedTarget=Boolean.TRUE.equals(target.get("protected"));if(protectedTarget) protectedCount++;
            String reason=!Boolean.TRUE.equals(target.get("eligible")) ? "No longer matches these filters or received-mail eligibility"
                : protectedTarget ? "Protected sender"
                : request.action().equals("ARCHIVE") && !Boolean.TRUE.equals(target.get("inbox")) ? "Already outside Inbox"
                : request.action().equals("MARK_READ") && !Boolean.TRUE.equals(target.get("unread")) ? "Already read" : "Eligible";
            boolean applies=reason.equals("Eligible");if(applies) effective++;
            target.put("effective",applies);target.put("reason",reason);
        }
        var providerIds=targets.stream().map(t->t.get("provider_message_id").toString()).toList();
        var canonical=hasher.canonicalize(account,request.action(),"DASHBOARD_SELECTION",providerIds);
        p.addValue("action",request.action()).addValue("provider_ids",new org.springframework.jdbc.support.SqlArrayValue("text",canonical.messageIds().toArray()))
            .addValue("hash",canonical.scopeHash()).addValue("count",targets.size())
            .addValue("summary",encode(Map.of("displayLabel","Dashboard selection","selectedTargetCount",targets.size())))
            .addValue("risk",encode(Map.of("protectedCount",protectedCount,"highRiskCount",0,"warningCodes",List.of())));
        long scope=jdbc.queryForObject("""
          INSERT INTO action_scopes(account_id,action_type,target_kind,message_ids,scope_hash,message_count,target_summary,risk_summary)
          VALUES(:account,:action,'DASHBOARD_SELECTION',:provider_ids,:hash,:count,CAST(:summary AS jsonb),CAST(:risk AS jsonb))
          ON CONFLICT(account_id,scope_hash) DO UPDATE SET scope_hash=EXCLUDED.scope_hash RETURNING id
          """,p,Long.class);
        p.addValue("scope",scope);
        jdbc.update("""
          INSERT INTO action_scope_targets(scope_id,account_id,scope_ordinal,target_fingerprint)
          SELECT id,account_id,target.ordinality-1,encode(digest(convert_to(target.message_id,'UTF8'),'sha256'),'hex')
          FROM action_scopes CROSS JOIN LATERAL unnest(message_ids) WITH ORDINALITY target(message_id,ordinality)
          WHERE id=:scope AND account_id=:account ON CONFLICT(scope_id,scope_ordinal) DO NOTHING
          """,p);
        UUID id=UUID.randomUUID();Instant expires=Instant.now().plusSeconds(1800);
        var coverage=jdbc.queryForMap("SELECT count(*) indexed,min(received_at) oldest,max(received_at) newest,max(last_synced_at) as_of FROM message_metadata WHERE account_id=:account",p);
        p.addValue("preview",id).addValue("revision",request.revision()).addValue("filter",encode(Map.of("filter",request.filter().firstPage(),"coverage",coverage)))
            .addValue("effective",effective).addValue("expires",DashboardRepository.timestamp(expires));
        jdbc.update("""
          INSERT INTO dashboard_previews(public_id,account_id,scope_id,grouping_revision,filter_snapshot,selected_count,effective_count,expires_at)
          VALUES(:preview,:account,:scope,:revision,CAST(:filter AS jsonb),:count,:effective,:expires)
          """,p);
        var rows=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource[targets.size()];
        for(int i=0;i<targets.size();i++) {var t=targets.get(i);rows[i]=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(t).addValue("preview",id).addValue("account",account).addValue("ordinal",i);}
        jdbc.batchUpdate("""
          INSERT INTO dashboard_preview_targets(preview_id,account_id,ordinal,message_id,provider_message_id,subject_header,sender_address,received_at,effective,reason)
          VALUES(:preview,:account,:ordinal,:dashboard_message_id,:provider_message_id,:subject_header,:address,:received_at,:effective,:reason)
          """,rows);
        return new Preview(id,request.action(),targets.size(),effective,targets.size()-effective,protectedCount,request.filter().firstPage(),coverage,expires);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Targets targets(long account,UUID preview,int page) {
        if(page<0 || page>1_000_000) throw bad("Invalid target page");
        var p=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("account",account).addValue("preview",preview).addValue("offset",page*10L);
        var available=repository.jdbc().queryForList("SELECT selected_count,grouping_revision FROM dashboard_previews WHERE public_id=:preview AND account_id=:account AND expires_at>now() AND invalidated_at IS NULL",p);
        if(available.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Preview expired or unavailable");
        if(((Number)available.getFirst().get("grouping_revision")).longValue()!=repository.revision(account)) throw conflict("Grouping changed. Create a new preview.");
        return new Targets(repository.jdbc().queryForList("SELECT ordinal,message_id,subject_header,sender_address,received_at,effective,reason FROM dashboard_preview_targets WHERE preview_id=:preview AND account_id=:account ORDER BY ordinal LIMIT 10 OFFSET :offset",p),((Number)available.getFirst().get("selected_count")).longValue(),page);
    }
    @Transactional
    public void invalidate(long account,UUID preview) {
        repository.jdbc().update("UPDATE dashboard_previews SET invalidated_at=now() WHERE account_id=:account AND public_id=:preview",Map.of("account",account,"preview",preview));
    }
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(JsonProcessingException error){throw new IllegalStateException(error);}}
    private static ResponseStatusException bad(String message) {return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private static ResponseStatusException conflict(String message) {return new ResponseStatusException(HttpStatus.CONFLICT,message);}
}
