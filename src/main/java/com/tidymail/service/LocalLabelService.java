package com.tidymail.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Local header/provider checks only. No model client, spending or mailbox writes. */
@Service
public class LocalLabelService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    public LocalLabelService(JdbcTemplate jdbc,ObjectMapper json,TransactionTemplate tx) {this.jdbc=jdbc;this.json=json;this.tx=tx;}

    @Scheduled(fixedDelayString="${app.local-labels.poll-ms:2000}",initialDelayString="${app.local-labels.startup-delay-ms:15000}")
    public void tick() { processBatch(200); }

    public int processBatch(int limit) {
        return tx.execute(status->{
            // Match cleanup/sync lock order: account first, then the queued emails.
            var accounts=jdbc.queryForList("SELECT id FROM user_accounts a WHERE EXISTS(SELECT 1 FROM local_label_dirty d WHERE d.account_id=a.id) ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED",Long.class);
            return accounts.isEmpty()?0:processBatch(accounts.getFirst(),limit);
        });
    }
    public int processBatch(long account,int limit) {
        return tx.execute(status->{
            jdbc.queryForList("SELECT id FROM user_accounts WHERE id=? FOR UPDATE",Long.class,account);
            var ids=jdbc.queryForList("SELECT message_id FROM local_label_dirty WHERE account_id=? ORDER BY message_id LIMIT ? FOR UPDATE SKIP LOCKED",UUID.class,account,Math.clamp(limit,1,500));
            recompute(account,ids);
            if(!ids.isEmpty())jdbc.update("INSERT INTO dashboard_account_settings(account_id,classification_revision) VALUES(?,1) ON CONFLICT(account_id) DO UPDATE SET classification_revision=dashboard_account_settings.classification_revision+1",account);
            return ids.size();
        });
    }
    public void recompute(long account,List<UUID> ids) {
        for(UUID id:ids) {
            var rows=jdbc.queryForList("""
                SELECT dashboard_from_address,from_display_name,subject_header,provider,
                  'OUTLOOK_CALENDAR_INVITE'=ANY(labels) meeting,local_mail_label_hash(m) input_hash
                FROM message_metadata m WHERE account_id=? AND dashboard_message_id=?
                """,account,id);
            if(rows.isEmpty())continue;
            var row=rows.getFirst();
            var labels=LocalMailLabels.classify((String)row.get("dashboard_from_address"),(String)row.get("from_display_name"),
                (String)row.get("subject_header"),"microsoft".equals(row.get("provider")),Boolean.TRUE.equals(row.get("meeting")));
            try {
                jdbc.update("""
                    INSERT INTO message_local_labels(account_id,message_id,input_hash,version,reasons)
                    SELECT ?,?,?,?,?::jsonb WHERE EXISTS(SELECT 1 FROM message_metadata m WHERE account_id=? AND dashboard_message_id=? AND local_mail_label_hash(m)=?)
                    ON CONFLICT(account_id,message_id) DO UPDATE SET input_hash=EXCLUDED.input_hash,version=EXCLUDED.version,reasons=EXCLUDED.reasons
                    """,account,id,row.get("input_hash"),LocalMailLabels.VERSION,json.writeValueAsString(labels),account,id,row.get("input_hash"));
                jdbc.update("DELETE FROM local_label_dirty WHERE account_id=? AND message_id=? AND EXISTS(SELECT 1 FROM message_local_labels WHERE account_id=? AND message_id=? AND input_hash=?)",account,id,account,id,row.get("input_hash"));
            } catch(com.fasterxml.jackson.core.JsonProcessingException failure) {throw new IllegalStateException("Could not save local labels",failure);}
        }
    }
    public Map<String,String> labels(long account,UUID id) {
        var rows=jdbc.queryForList("SELECT reasons::text FROM message_local_labels l JOIN message_metadata m ON m.account_id=l.account_id AND m.dashboard_message_id=l.message_id WHERE l.account_id=? AND l.message_id=? AND l.input_hash=local_mail_label_hash(m) AND l.version=?",String.class,account,id,LocalMailLabels.VERSION);
        if(rows.isEmpty())return Map.of();
        try {return json.readValue(rows.getFirst(),new TypeReference<Map<String,String>>(){});}
        catch(Exception failure){throw new IllegalStateException("Saved local labels unavailable",failure);}
    }
}
