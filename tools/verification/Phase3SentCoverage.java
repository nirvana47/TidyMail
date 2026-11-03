import java.util.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Aggregate-only retained-index inspection. No migrations, workers, provider calls or private header output. */
public class Phase3SentCoverage {
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration(excludeName={
        "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
        "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
        "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration"})
    static class Config {}
    public static void main(String[] args) {
        if(args.length!=1 || !args[0].equals("--local-coverage-only"))throw new IllegalArgumentException("Explicit local coverage mode required");
        try(var ctx=new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE).run(
            "--spring.main.banner-mode=off","--logging.level.root=OFF","--spring.sql.init.mode=never",
            "--spring.datasource.hikari.read-only=true","--spring.datasource.hikari.maximum-pool-size=1",
            "--spring.datasource.hikari.minimum-idle=0")) {
            var jdbc=ctx.getBean(JdbcTemplate.class);
            jdbc.queryForList("""
                SELECT a.id account_id,count(m.id) indexed_messages,
                    count(*) FILTER(WHERE 'SENT'=ANY(m.labels) AND NOT(m.labels && ARRAY['DRAFT','SPAM','TRASH'])) indexed_sent,
                    count(*) FILTER(WHERE 'SENT'=ANY(m.labels) AND cardinality(m.to_values)+cardinality(m.cc_values)+cardinality(m.bcc_values)>0) sent_with_recipients,
                    count(*) FILTER(WHERE 'SENT'=ANY(m.labels) AND cardinality(m.in_reply_to_values)+cardinality(m.references_values)>0) sent_with_reply_headers,
                    min(m.received_at) FILTER(WHERE 'SENT'=ANY(m.labels)) oldest_indexed_sent,
                    max(m.received_at) FILTER(WHERE 'SENT'=ANY(m.labels)) newest_indexed_sent,
                    s.backfill_status,s.rescan_status
                FROM user_accounts a LEFT JOIN message_metadata m ON m.account_id=a.id
                LEFT JOIN mailbox_sync_state s ON s.account_id=a.id
                GROUP BY a.id,s.backfill_status,s.rescan_status ORDER BY a.id
                """).forEach(System.out::println);
            System.out.println("Local index coverage only; missing history remains unknown. Provider/model calls: 0; retained writes: 0.");
        }
    }
}
