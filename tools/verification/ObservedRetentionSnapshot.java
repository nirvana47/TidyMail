package com.tidymail.service;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Local retained Sent metadata only, scoped to the existing eleven replay senders. No provider/model/write. */
public final class ObservedRetentionSnapshot {
    public static void main(String[] args) throws Exception {
        System.setProperty("spring.devtools.restart.enabled","false");
        if(args.length!=3 || !args[0].equals("--local-sent-only"))throw new IllegalArgumentException("Explicit local-only mode required");
        var json=new ObjectMapper().findAndRegisterModules();var samples=json.readTree(Path.of(args[1]).toFile());Path output=Path.of(args[2]);
        if(samples.size()!=11 || Files.exists(output))throw new IllegalArgumentException("New scoped snapshot required");
        long account=samples.get(0).path("metadata").path("account_id").asLong();
        var target=new TreeMap<String,Long>();var excluded=new HashSet<String>();
        for(var sample:samples) {
            if(sample.path("metadata").path("account_id").asLong()!=account)throw new IllegalArgumentException("One account required");
            target.put(sample.path("evidence").path("address").asText(),0L);
            excluded.add(sample.path("metadata").path("provider_message_id").asText());
        }
        try(var context=RecommendationSnippetPilot.context(true)) {
            var jdbc=context.getBean(JdbcTemplate.class);
            if(!jdbc.queryForObject("SELECT current_setting('transaction_read_only')='on'",Boolean.class))throw new IllegalStateException("Read-only connection required");
            var owners=new HashSet<>(jdbc.queryForList("SELECT email FROM user_accounts WHERE id=? AND provider='google'",String.class,account));
            if(owners.size()!=1)throw new IllegalStateException("Owned account unavailable");
            owners.addAll(jdbc.queryForList("SELECT address FROM dashboard_verified_aliases WHERE account_id=?",String.class,account));
            var rows=jdbc.queryForList("""
                SELECT provider_message_id,dashboard_from_address,labels,label_evidence_known,to_values,cc_values,bcc_values,delivered_to_values
                FROM message_metadata WHERE account_id=? AND provider='google' AND 'SENT'=ANY(labels)
                  AND NOT(labels && ARRAY['SPAM','TRASH','DRAFT']) ORDER BY id LIMIT 25001
                """,account);
            if(rows.size()>25000)throw new IllegalStateException("Bounded Sent snapshot limit reached");
            for(var row:rows) {
                if(excluded.contains(row.get("provider_message_id")))continue;
                for(var contribution:CorrespondentHistory.contributions((String)row.get("dashboard_from_address"),strings(row.get("labels")),
                        Boolean.TRUE.equals(row.get("label_evidence_known")),strings(row.get("to_values")),strings(row.get("cc_values")),
                        strings(row.get("bcc_values")),strings(row.get("delivered_to_values")),false,null,owners))
                    if(contribution.sent() && target.containsKey(contribution.address()))target.compute(contribution.address(),(k,v)->v+1);
            }
            var report=json.createObjectNode();report.put("account_id",account).put("observed_at",Instant.now().toString())
                .put("coverage","PARTIAL_RETAINED_SENT_INDEX").put("sent_rows_inspected",rows.size()).put("gmail_calls",0).put("model_calls",0).put("retained_writes",0)
                .put("negative_count_means","Unknown correspondence, never proof of no relationship");report.set("sent_to_exact_address",json.valueToTree(target));
            Files.createDirectories(output.getParent());json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
            System.out.println("Observed local Sent history for "+target.size()+" exact addresses from "+rows.size()+" retained rows; positive addresses="+target.values().stream().filter(v->v>0).count()+"; provider/model calls=0.");
        }
    }
    static List<String> strings(Object value) throws Exception {return value==null?List.of():Arrays.asList((String[])((java.sql.Array)value).getArray());}
}
