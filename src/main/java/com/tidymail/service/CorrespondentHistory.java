package com.tidymail.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Local, bounded relationship index. Never invokes providers or schedules paid classification. */
@Service
public class CorrespondentHistory {
    public static final String VERSION = "correspondence-v1";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public CorrespondentHistory(JdbcTemplate jdbc, TransactionTemplate tx) { this.jdbc=jdbc; this.tx=tx; }

    public record Context(String coverage, long sent, long received, long sentReplies, long receivedReplies,
                          String lastSentAt, String lastReceivedAt) {
        public Context {
            if (!Set.of("UNKNOWN", "PARTIAL", "INDEXED").contains(coverage)
                    || sent<0 || received<0 || sentReplies<0 || receivedReplies<0
                    || sentReplies>sent || receivedReplies>received) throw new IllegalArgumentException("Invalid relationship evidence");
            lastSentAt=Objects.toString(lastSentAt, ""); lastReceivedAt=Objects.toString(lastReceivedAt, "");
            if (coverage.equals("UNKNOWN") && (sent+received!=0 || !lastSentAt.isEmpty() || !lastReceivedAt.isEmpty()))
                throw new IllegalArgumentException("Unknown history has no counts");
        }
        public static Context unknown() { return new Context("UNKNOWN",0,0,0,0,"",""); }
    }
    public record Contribution(String address, boolean sent, boolean received, boolean reply, Instant at) {}
    private record Owned(long account, long message) {}

    /** One cheap, account-scoped lookup per bounded candidate batch, with explicit coverage. */
    public Map<String,Context> lookup(long account, Collection<String> addresses) {
        if (account<=0 || addresses.size()>200) throw new IllegalArgumentException("Bounded owned lookup required");
        var normalized=new TreeSet<String>();
        for (String address:addresses) { String value=DashboardIdentity.mailbox(address); if(value!=null)normalized.add(value); }
        if(normalized.isEmpty()) return Map.of();
        var result=new LinkedHashMap<String,Context>();
        var params=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("account",account)
            .addValue("addresses",new org.springframework.jdbc.support.SqlArrayValue("text",normalized.toArray(String[]::new)));
        // One SQL snapshot: never mark stale counts INDEXED between separate coverage/data reads.
        new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).query("""
            WITH coverage AS (
              SELECT CASE WHEN b.complete AND s.backfill_status='COMPLETE'
                AND s.rescan_status IN ('NOT_STARTED','COMPLETE')
                AND NOT EXISTS(SELECT 1 FROM correspondent_dirty d WHERE d.account_id=b.account_id)
                THEN 'INDEXED' ELSE 'PARTIAL' END value
              FROM correspondent_backfill b LEFT JOIN mailbox_sync_state s ON s.account_id=b.account_id WHERE b.account_id=:account
            )
            SELECT requested.address,COALESCE(c.value,'UNKNOWN') coverage,
              COALESCE(h.sent_count,0) sent_count,COALESCE(h.received_count,0) received_count,
              COALESCE(h.sent_reply_count,0) sent_reply_count,COALESCE(h.received_reply_count,0) received_reply_count,
              h.last_sent_at,h.last_received_at
            FROM unnest(CAST(:addresses AS text[])) requested(address)
            LEFT JOIN correspondent_summary h ON h.account_id=:account AND h.address=requested.address
            LEFT JOIN coverage c ON true
            """,params,(org.springframework.jdbc.core.RowCallbackHandler)r->result.put(r.getString("address"),new Context(
                r.getString("coverage"),r.getLong("sent_count"),r.getLong("received_count"),
                r.getLong("sent_reply_count"),r.getLong("received_reply_count"),date(r.getTimestamp("last_sent_at")),date(r.getTimestamp("last_received_at")))));
        return Map.copyOf(result);
    }

    /** Candidate-only adapter; the live classifier and existing paid pilot are unchanged. */
    public List<SemanticQualificationContract.Evidence> attach(long account, List<SemanticQualificationContract.Evidence> batch) {
        var summaries=lookup(account,batch.stream().map(SemanticQualificationContract.Evidence::address).toList());
        return batch.stream().map(e->new SemanticQualificationContract.Evidence(e.message(),e.sourceRevision(),e.extractorVersion(),
            e.address(),e.name(),e.subject(),e.reply(),e.unsubscribe(),e.snippet(),e.normalizedBody(),e.facts(),
            summaries.getOrDefault(Objects.toString(DashboardIdentity.mailbox(e.address()),""),Context.unknown()))).toList();
    }

    @Scheduled(fixedDelayString="${app.correspondence.delay-ms:2000}")
    public void tick() { processBatch(200); }

    /** Restartable keyset backfill plus change queue; one worker transaction across instances. */
    public int processBatch(int limit) {
        if(limit<1 || limit>500) throw new IllegalArgumentException("Batch must contain 1–500 messages");
        return Objects.requireNonNull(tx.execute(status->{
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(32198472)",Boolean.class))) return 0;
            // Account deletion must precede all local evidence locks. Skip accounts already locked
            // by cleanup/disconnect, rather than waiting while holding contribution/message locks.
            var available=jdbc.queryForList("SELECT id FROM user_accounts ORDER BY id FOR KEY SHARE SKIP LOCKED",Long.class);
            if(available.isEmpty())return 0;
            var named=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
            var ownedParams=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("accounts",available)
                .addValue("limit",Math.max(1,limit/2));
            named.update("INSERT INTO correspondent_backfill(account_id) SELECT id FROM user_accounts WHERE id IN (:accounts) ON CONFLICT DO NOTHING",ownedParams);
            // Reserve half the capacity for historical progress even during fresh sync activity.
            var batch=new LinkedHashSet<Owned>(named.query("SELECT account_id,message_id FROM correspondent_dirty WHERE account_id IN (:accounts) ORDER BY account_id,message_id LIMIT :limit",ownedParams,
                (r,n)->new Owned(r.getLong(1),r.getLong(2))));
            var historical=new HashSet<Owned>();
            var accounts=named.queryForList("SELECT account_id FROM correspondent_backfill WHERE account_id IN (:accounts) AND NOT complete ORDER BY account_id LIMIT 1",ownedParams,Long.class);
            if(!accounts.isEmpty()) {
                long account=accounts.getFirst();
                var ids=jdbc.queryForList("""
                    SELECT id FROM message_metadata WHERE account_id=? AND id>(SELECT last_message_id FROM correspondent_backfill WHERE account_id=?)
                    ORDER BY id LIMIT ?
                    """,Long.class,account,account,limit-batch.size());
                for(long id:ids){var owned=new Owned(account,id);batch.add(owned);historical.add(owned);}
                if(!ids.isEmpty())jdbc.update("UPDATE correspondent_backfill SET last_message_id=? WHERE account_id=?",ids.getLast(),account);
                else if(batch.size()<limit)jdbc.update("UPDATE correspondent_backfill SET complete=true WHERE account_id=?",account);
            }
            // Skip sync/cleanup locks, retaining dirty entries and rewinding skipped backfill IDs.
            // Never acquire queue locks before message locks.
            var affected=new LinkedHashMap<Long,Map<String,long[]>>();
            var owners=new HashMap<Long,Set<String>>();
            int processed=0;
            for(var owned:batch) {
                var locked=jdbc.queryForList("SELECT id FROM message_metadata WHERE account_id=? AND id=? FOR UPDATE SKIP LOCKED",Long.class,owned.account,owned.message);
                if(locked.isEmpty() && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM message_metadata WHERE account_id=? AND id=?)",Boolean.class,owned.account,owned.message))) {
                    if(historical.contains(owned))jdbc.update("UPDATE correspondent_backfill SET last_message_id=LEAST(last_message_id,?),complete=false WHERE account_id=?",owned.message-1,owned.account);
                    continue;
                }
                // Deleting before reading lets a concurrent delete requeue a missing row safely.
                jdbc.update("DELETE FROM correspondent_dirty WHERE account_id=? AND message_id=?",owned.account,owned.message);
                var deltas=affected.computeIfAbsent(owned.account,ignored->new TreeMap<>());
                jdbc.query("SELECT * FROM correspondent_contributions WHERE account_id=? AND message_id=?",(org.springframework.jdbc.core.RowCallbackHandler)r->
                    delta(deltas,new Contribution(r.getString("address"),r.getBoolean("sent"),r.getBoolean("received"),r.getBoolean("reply"),null),-1),owned.account,owned.message);
                var rows=jdbc.query("""
                    SELECT m.*,a.email owner FROM message_metadata m JOIN user_accounts a ON a.id=m.account_id WHERE m.account_id=? AND m.id=?
                    """,(r,n)->{
                        var aliases=owners.get(owned.account);
                        if(aliases==null) {
                            aliases=new HashSet<String>(); aliases.add(r.getString("owner"));
                            aliases.addAll(jdbc.queryForList("SELECT address FROM dashboard_verified_aliases WHERE account_id=?",String.class,owned.account));
                            owners.put(owned.account,aliases);
                        }
                        return contributions(r.getString("dashboard_from_address"),strings(r.getArray("labels")),r.getBoolean("label_evidence_known"),
                            strings(r.getArray("to_values")),strings(r.getArray("cc_values")),strings(r.getArray("bcc_values")),
                            strings(r.getArray("delivered_to_values")),!strings(r.getArray("in_reply_to_values")).isEmpty()||!strings(r.getArray("references_values")).isEmpty(),
                            r.getTimestamp("received_at")==null?null:r.getTimestamp("received_at").toInstant(),aliases);
                    },owned.account,owned.message);
                jdbc.update("DELETE FROM correspondent_contributions WHERE account_id=? AND message_id=?",owned.account,owned.message);
                processed++;
                if(!rows.isEmpty())for(var c:rows.getFirst()) {
                    delta(deltas,c,1);
                    jdbc.update("INSERT INTO correspondent_contributions VALUES(?,?,?,?,?,?,?)",owned.account,owned.message,c.address,c.sent,c.received,c.reply,c.at==null?null:Timestamp.from(c.at));
                }
            }
            for(var entry:affected.entrySet()) for(var address:entry.getValue().entrySet()) refresh(entry.getKey(),address.getKey(),address.getValue());
            return processed;
        }));
    }

    private static void delta(Map<String,long[]> deltas,Contribution c,int sign) {
        long[] d=deltas.computeIfAbsent(c.address,ignored->new long[4]);
        if(c.sent)d[0]+=sign; if(c.received)d[1]+=sign;
        if(c.sent&&c.reply)d[2]+=sign; if(c.received&&c.reply)d[3]+=sign;
    }

    private void refresh(long account,String address,long[] delta) {
        // Counts use deltas. Date repairs read the first matching entry from partial date indexes,
        // including when a deletion removes the latest exchange. Neither path rescans Sent history.
        var revisions=jdbc.query("""
            INSERT INTO correspondent_summary(account_id,address,sent_count,received_count,sent_reply_count,received_reply_count,last_sent_at,last_received_at)
            VALUES(?,?,?,?,?,?,
              (SELECT observed_at FROM correspondent_contributions WHERE account_id=? AND address=? AND sent AND observed_at IS NOT NULL ORDER BY observed_at DESC LIMIT 1),
              (SELECT observed_at FROM correspondent_contributions WHERE account_id=? AND address=? AND received AND observed_at IS NOT NULL ORDER BY observed_at DESC LIMIT 1))
            ON CONFLICT(account_id,address) DO UPDATE SET
                sent_count=correspondent_summary.sent_count+EXCLUDED.sent_count,
                received_count=correspondent_summary.received_count+EXCLUDED.received_count,
                sent_reply_count=correspondent_summary.sent_reply_count+EXCLUDED.sent_reply_count,
                received_reply_count=correspondent_summary.received_reply_count+EXCLUDED.received_reply_count,
                last_sent_at=EXCLUDED.last_sent_at,last_received_at=EXCLUDED.last_received_at,revision=correspondent_summary.revision+1
            WHERE EXCLUDED.sent_count<>0 OR EXCLUDED.received_count<>0 OR EXCLUDED.sent_reply_count<>0 OR EXCLUDED.received_reply_count<>0
                OR ROW(correspondent_summary.last_sent_at,correspondent_summary.last_received_at)
                    IS DISTINCT FROM ROW(EXCLUDED.last_sent_at,EXCLUDED.last_received_at)
            RETURNING revision
            """,(r,n)->r.getLong(1),account,address,delta[0],delta[1],delta[2],delta[3],account,address,account,address);
        if(revisions.isEmpty())return;
        jdbc.update("""
            INSERT INTO correspondent_reconsideration SELECT account_id,address,revision FROM correspondent_summary WHERE account_id=? AND address=?
            ON CONFLICT(account_id,address) DO UPDATE SET revision=EXCLUDED.revision WHERE correspondent_reconsideration.revision<>EXCLUDED.revision
            """,account,address);
    }

    public static List<Contribution> contributions(String from,List<String> labels,boolean known,List<String> to,List<String> cc,List<String> bcc,
            List<String> delivered,boolean reply,Instant at,Set<String> owners) {
        if((!known && labels.isEmpty()) || !Collections.disjoint(labels,List.of("DRAFT","SPAM","TRASH"))) return List.of();
        var self=new HashSet<String>(); for(String owner:owners){String address=DashboardIdentity.mailbox(owner);if(address!=null)self.add(address);}
        var result=new LinkedHashMap<String,Contribution>();
        // SENT is provider evidence; recipients, not From/display-name/domain, establish outgoing correspondents.
        if(labels.contains("SENT")) {
            var recipients=new TreeSet<String>(); for(var headers:List.of(to,cc,bcc))recipients.addAll(recipients(headers));
            for(String address:recipients)if(!self.contains(address))result.put(address,new Contribution(address,true,false,reply,at));
        }
        String sender=DashboardIdentity.mailbox(from);
        boolean incoming=!labels.contains("SENT") || labels.contains("INBOX") || delivered.stream().map(DashboardIdentity::mailbox).anyMatch(self::contains);
        if(incoming && sender!=null && !self.contains(sender))result.put(sender,new Contribution(sender,result.containsKey(sender),true,reply,at));
        return List.copyOf(result.values());
    }
    /** Split only outside quoted display names/comments/angle addresses; reject ambiguous individual tokens. */
    static Set<String> recipients(List<String> headers) {
        var result=new TreeSet<String>();
        for(String header:headers) {
            if(header==null || header.length()>16384)continue;
            var token=new StringBuilder(); boolean quote=false,escape=false; int angle=0,comment=0;
            for(int i=0;i<=header.length();i++) {
                char c=i==header.length()?',':header.charAt(i);
                if(c==','&&!quote&&angle==0&&comment==0) {String address=DashboardIdentity.mailbox(token.toString());if(address!=null)result.add(address);token.setLength(0);continue;}
                if(escape){token.append(c);escape=false;continue;}
                if(c=='\\'&&quote){token.append(c);escape=true;continue;}
                if(c=='"'&&comment==0)quote=!quote;
                if(!quote){if(c=='(')comment++;if(c==')'&&comment>0){comment--;continue;}if(comment>0)continue;if(c=='<')angle++;if(c=='>')angle--;}
                token.append(c);
            }
        }
        return result;
    }
    private static List<String> strings(java.sql.Array array) throws java.sql.SQLException { return array==null?List.of():Arrays.asList((String[])array.getArray()); }
    private static String date(Timestamp timestamp) { return timestamp==null?"":timestamp.toInstant().toString(); }
}
