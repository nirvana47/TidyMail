import com.fasterxml.jackson.databind.ObjectMapper;
import com.tidymail.repository.DashboardRepository;
import java.nio.file.*;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bounded local-index export only. No component scan, mail transport, crypto or classifier. */
public class Phase3IndexCandidates {
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration(excludeName={
        "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
        "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
        "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration"})
    static class Config {}
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !Set.of("--local-index-only", "--heldback-index-only").contains(args[0])) throw new IllegalArgumentException("Explicit local-index-only mode required");
        boolean heldback = args[0].equals("--heldback-index-only");
        Path output = Path.of(args[1]);
        if (Files.exists(output)) throw new IllegalArgumentException("Refusing to overwrite an existing candidate export");
        try (var ctx = new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE)
                .run("--spring.main.banner-mode=off", "--logging.level.root=OFF", "--spring.sql.init.mode=never",
                    "--spring.datasource.hikari.read-only=true", "--spring.datasource.hikari.maximum-pool-size=1",
                    "--spring.datasource.hikari.minimum-idle=0")) {
            var jdbc = ctx.getBean(JdbcTemplate.class);
            String query = """
                WITH eligible AS (
                    SELECT m.account_id,a.email account_email,m.provider_message_id,m.provider_thread_id,
                        m.dashboard_from_address address,m.from_display_name,m.subject_header,m.received_at::text,
                        m.size_estimate,m.dashboard_unit_kind,m.dashboard_unit_key,
                        CASE
                          WHEN m.subject_header ~* 'password|sign.?in|security alert|verification|passcode' THEN 'access_candidate'
                          WHEN m.subject_header ~* 'invitation:|updated invitation:|canceled event:' THEN 'invitation_candidate'
                          WHEN m.subject_header ~* 'order confirm|order #[0-9]|thanks for your order|your .*order has been placed' THEN 'order_candidate'
                          WHEN m.subject_header ~* 'shipped|delivered|shipment|out for delivery' THEN 'shipping_candidate'
                          WHEN m.subject_header ~* 'sale|save [0-9]|offers|savings' THEN 'promotion_candidate'
                          WHEN cardinality(m.in_reply_to_values)>0 OR cardinality(m.references_values)>0 THEN 'correspondence_candidate'
                          WHEN m.size_estimate>5000000 THEN 'large_candidate'
                          ELSE 'routine_candidate'
                        END bucket
                    FROM message_metadata m JOIN user_accounts a ON a.id=m.account_id
                    WHERE m.provider='google' AND a.provider='google'
                      AND m.dashboard_from_address IS NOT NULL AND m.subject_header IS NOT NULL
                      AND (
                """ + DashboardRepository.RECEIVED + """
                      )
                      AND m.received_at>=CURRENT_TIMESTAMP-interval '24 months' AND m.received_at<=CURRENT_TIMESTAMP
                      AND (m.dashboard_from_address ~* '(^|@|\\.)(amazon|costco|quora|nextdoor)\\.'
                           OR m.subject_header ~* 'invitation:' OR cardinality(m.in_reply_to_values)>0
                           OR cardinality(m.references_values)>0 OR m.size_estimate>5000000)
                ), ranked AS (
                    SELECT *,row_number() OVER(PARTITION BY bucket ORDER BY received_at DESC,account_id,provider_message_id) position FROM eligible
                ) SELECT * FROM ranked WHERE position<=12 ORDER BY bucket,position
                """;
            if (heldback) query = """
                WITH eligible AS (
                    SELECT m.account_id,a.email account_email,m.provider_message_id,m.provider_thread_id,
                        m.dashboard_from_address address,m.from_display_name,m.subject_header,m.received_at::text,
                        m.size_estimate,m.dashboard_unit_kind,m.dashboard_unit_key,
                        CASE
                          WHEN m.dashboard_from_address ~* '@([^@]*\\.)?(instagram\\.com|facebookmail\\.com|facebook\\.com|redditmail\\.com|reddit\\.com|tiktok\\.com)$'
                           AND m.subject_header ~* 'password|sign.?in|log.?in|security|verification|passcode|confirm.*account'
                            THEN 'social_access_hint'
                          WHEN m.dashboard_from_address ~* '@([^@]*\\.)?(instagram\\.com|facebookmail\\.com|facebook\\.com|redditmail\\.com|reddit\\.com|tiktok\\.com)$'
                            THEN 'social_activity_hint'
                          WHEN m.subject_header ~* 'order confirmation|you placed an order|thanks for your order|purchase receipt|payment receipt'
                            THEN 'purchase_hint'
                          ELSE 'account_access_hint'
                        END bucket
                    FROM message_metadata m JOIN user_accounts a ON a.id=m.account_id
                    WHERE m.provider='google' AND a.provider='google'
                      AND m.dashboard_from_address IS NOT NULL AND m.subject_header IS NOT NULL
                      AND (
                """ + DashboardRepository.RECEIVED + """
                      )
                      AND m.received_at>=CURRENT_TIMESTAMP-interval '24 months' AND m.received_at<=CURRENT_TIMESTAMP
                      AND (m.dashboard_from_address ~* '@([^@]*\\.)?(instagram\\.com|facebookmail\\.com|facebook\\.com|redditmail\\.com|reddit\\.com|tiktok\\.com)$'
                           OR m.subject_header ~* 'order confirmation|you placed an order|thanks for your order|purchase receipt|payment receipt|password|sign.?in|log.?in|security alert|verification|passcode')
                      AND cardinality(m.in_reply_to_values)=0 AND cardinality(m.references_values)=0
                ), ranked AS (
                    SELECT *,row_number() OVER(PARTITION BY bucket ORDER BY received_at DESC,account_id,provider_message_id) position FROM eligible
                ) SELECT * FROM ranked WHERE position<=8 ORDER BY bucket,position
                """;
            var rows = jdbc.queryForList(query);
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of(
                    "status", "UNREVIEWED_LOCAL_INDEX_CANDIDATES", "mailbox_reads", 0, "model_calls", 0,
                    "retained_writes", 0, "max_rows", heldback ? 32 : 96, "rows", rows));
            System.out.println("Local index candidates exported: " + rows.size() + "; no mail reads or model calls.");
        }
    }
}
