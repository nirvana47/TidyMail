package com.tidymail.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-only local snapshot; shared production extractor, no migrations, workers, Gmail or model calls. */
public class Phase3CorrespondenceSnapshot {
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration(excludeName={
        "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
        "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
        "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration"})
    static class Config {}
    record Event(boolean sent,boolean received,boolean reply,Instant at) {}
    static final ObjectMapper JSON=new ObjectMapper();
    public static void main(String[] args) throws Exception {
        if(args.length!=3 || !args[0].equals("--local-snapshot-only"))throw new IllegalArgumentException("Explicit local snapshot mode required");
        Path output=Path.of(args[2]);if(Files.exists(output))throw new IllegalArgumentException("Refusing overwrite");
        var manifest=(ObjectNode)JSON.readTree(Path.of(args[1]).toFile());
        if(manifest.has("heldback")&&!manifest.path("heldback").isEmpty())throw new IllegalArgumentException("No held-back reuse");
        try(var ctx=new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE).run(
            "--spring.main.banner-mode=off","--logging.level.root=OFF","--spring.sql.init.mode=never",
            "--spring.datasource.hikari.read-only=true","--spring.datasource.hikari.maximum-pool-size=1","--spring.datasource.hikari.minimum-idle=0")) {
            var jdbc=ctx.getBean(JdbcTemplate.class);
            var tx=new TransactionTemplate(new DataSourceTransactionManager(ctx.getBean(javax.sql.DataSource.class)));
            tx.setReadOnly(true);tx.setIsolationLevel(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            tx.executeWithoutResult(status->{
                var grouped=new TreeMap<Long,List<ObjectNode>>();
                for(var row:manifest.path("development"))grouped.computeIfAbsent(row.path("source_message").path("account_id").asLong(),ignored->new ArrayList<>()).add((ObjectNode)row);
                for(var entry:grouped.entrySet())snapshot(jdbc,entry.getKey(),entry.getValue());
            });
        }
        manifest.put("scope","DEVELOPMENT").put("comparison","CORRESPONDENCE_ON_OFF");
        manifest.put("history_snapshot_policy","Retained local evidence strictly before target received_at; target/future exchanges excluded; current labels only; no historical completeness claim");
        manifest.put("history_snapshot_at",Instant.now().toString());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),manifest);
        System.out.println("Private development snapshot saved; no retained writes, Gmail/model calls or held-back reuse.");
    }
    static void snapshot(JdbcTemplate jdbc,long account,List<ObjectNode> examples) {
        if(account<=0||examples.size()>40)throw new IllegalArgumentException("Bounded owned snapshot required");
        var owners=new HashSet<String>(jdbc.queryForList("SELECT email FROM user_accounts WHERE id=?",String.class,account));
        owners.addAll(jdbc.queryForList("SELECT address FROM dashboard_verified_aliases WHERE account_id=?",String.class,account));
        var events=new HashMap<String,List<Event>>();
        for(var row:examples)events.put(row.path("source_message").path("address").asText(),new ArrayList<>());
        long cursor=0,scanned=0;
        while(true) {
            var rows=jdbc.queryForList("""
                SELECT id,dashboard_from_address,labels,label_evidence_known,to_values,cc_values,bcc_values,delivered_to_values,
                    in_reply_to_values,references_values,received_at
                FROM message_metadata WHERE account_id=? AND provider='google' AND id>? ORDER BY id LIMIT 500
                """,account,cursor);
            if(rows.isEmpty())break;
            for(var row:rows)try {
                cursor=((Number)row.get("id")).longValue();scanned++;
                Timestamp timestamp=(Timestamp)row.get("received_at");
                if(timestamp==null)continue;
                for(var c:CorrespondentHistory.contributions((String)row.get("dashboard_from_address"),strings(row.get("labels")),Boolean.TRUE.equals(row.get("label_evidence_known")),
                    strings(row.get("to_values")),strings(row.get("cc_values")),strings(row.get("bcc_values")),strings(row.get("delivered_to_values")),
                    !strings(row.get("in_reply_to_values")).isEmpty()||!strings(row.get("references_values")).isEmpty(),timestamp.toInstant(),owners))
                    if(events.containsKey(c.address()))events.get(c.address()).add(new Event(c.sent(),c.received(),c.reply(),c.at()));
            }catch(SQLException e){throw new IllegalStateException("Snapshot array decoding failed");}
            if(scanned>250000)throw new IllegalStateException("Snapshot scan cap exceeded");
        }
        for(var row:examples) {
            var source=(ObjectNode)row.path("source_message");
            var target=jdbc.queryForMap("SELECT received_at,dashboard_from_address,labels,label_evidence_known,cardinality(in_reply_to_values)>0 OR cardinality(references_values)>0 reply,cardinality(list_unsubscribe_values)>0 unsubscribe FROM message_metadata WHERE account_id=? AND provider_message_id=? AND provider='google'",account,source.path("provider_message_id").asText());
            if(!Objects.equals(target.get("dashboard_from_address"),source.path("address").asText())||target.get("received_at")==null)throw new IllegalStateException("Reviewed source mismatch");
            Instant cutoff=((Timestamp)target.get("received_at")).toInstant();
            long sent=0,received=0,sr=0,rr=0;Instant lastSent=null,lastReceived=null;
            for(var e:events.get(source.path("address").asText()))if(e.at.isBefore(cutoff)) {
                if(e.sent){sent++;if(e.reply)sr++;if(lastSent==null||e.at.isAfter(lastSent))lastSent=e.at;}
                if(e.received){received++;if(e.reply)rr++;if(lastReceived==null||e.at.isAfter(lastReceived))lastReceived=e.at;}
            }
            // Retrospective source availability is incomplete even if today's mailbox backfill is complete.
            var context=new CorrespondentHistory.Context("PARTIAL",sent,received,sr,rr,Objects.toString(lastSent,""),Objects.toString(lastReceived,""));
            source.set("relationship",JSON.valueToTree(context));source.put("history_before",cutoff.toString());
            var flags=source.putObject("frozen_evidence");
            flags.put("reply",Boolean.TRUE.equals(target.get("reply"))).put("unsubscribe",Boolean.TRUE.equals(target.get("unsubscribe")))
                .put("labels_known",Boolean.TRUE.equals(target.get("label_evidence_known")));
            var categories=flags.putArray("gmail_categories");
            try { strings(target.get("labels")).stream().filter(Set.of("CATEGORY_PERSONAL","CATEGORY_PROMOTIONS","CATEGORY_SOCIAL","CATEGORY_UPDATES","CATEGORY_FORUMS")::contains).sorted().forEach(categories::add); }
            catch(SQLException e){throw new IllegalStateException("Unable to freeze category labels");}
            System.out.printf("%s prior_sent=%d prior_received=%d prior_sent_replies=%d%n",row.path("review_id").asText(),sent,received,sr);
        }
        System.out.printf("Account-local extraction: %d indexed rows read once for %d development messages.%n",scanned,examples.size());
    }
    static List<String> strings(Object value)throws SQLException{return value==null?List.of():Arrays.asList((String[])((java.sql.Array)value).getArray());}
}
