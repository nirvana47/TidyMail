package com.tidymail.support;

import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.tidymail.mail.MailProvider;
import com.tidymail.mail.sync.MailboxSyncProvider;
import com.tidymail.mail.sync.SyncCapabilities;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

/**
 * Shared real-Postgres base for HTTP-to-database user journeys.
 *
 * <p>Local Colima runs require
 * {@code DOCKER_HOST=unix://$HOME/.colima/default/docker.sock} and
 * {@code TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.task.scheduling.enabled=false",
    "app.classification.worker.enabled=false",
    "app.classification.batch-size=5",
    "TOKEN_ENCRYPT_SECRET=0000000000000000000000000000000000000000000000000000000000000000",
    "TOKEN_ENCRYPT_SALT=00000000000000000000000000000000",
    "GOOGLE_CLIENT_ID=",
    "GOOGLE_CLIENT_SECRET=",
    "MICROSOFT_CLIENT_ID=",
    "MICROSOFT_CLIENT_SECRET=",
    "MICROSOFT_TENANT_ID=common"
})
public abstract class PostgresJourneyTest {

    @Container
    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("tidymail")
            .withUsername("tidymail")
            .withPassword("tidymail");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @MockBean(name = "gmailMailService")
    protected TestGmailProvider gmailProvider;

    @MockBean(name = "outlookMailService")
    protected MailProvider outlookProvider;

    protected UserAccountFixtures userAccounts;

    @BeforeEach
    protected void resetState() {
        reset(gmailProvider, outlookProvider);
        jdbc.execute("TRUNCATE TABLE user_accounts RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO classification_budget(id) VALUES(1) ON CONFLICT DO NOTHING");
        userAccounts = new UserAccountFixtures(jdbc);
    }

    protected static OAuth2AuthenticationToken oauth2Authentication(String email) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("email", email);
        attributes.put("name", "Journey Owner");
        attributes.put("sub", "subject-" + email);
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        DefaultOAuth2User user = new DefaultOAuth2User(authorities, attributes, "sub");
        return new OAuth2AuthenticationToken(user, authorities, "google");
    }

    protected static RequestPostProcessor authenticatedAs(String email) {
        return authentication(oauth2Authentication(email));
    }

    protected static RequestPostProcessor csrfToken() {
        return csrf();
    }

    protected SyntheticMailboxGenerator.SeedResult seedSyntheticMailbox() {
        return new SyntheticMailboxGenerator(jdbc, userAccounts).seed();
    }

    protected SyntheticMailboxGenerator.SeedResult seedSyntheticMailbox(int rowCount) {
        return new SyntheticMailboxGenerator(jdbc, userAccounts).seed(rowCount);
    }

    protected SyntheticMailboxGenerator.SeedResult seedSyntheticMailbox(long seed, int rowCount) {
        return new SyntheticMailboxGenerator(jdbc, userAccounts).seed(seed, rowCount);
    }

    protected interface TestGmailProvider extends MailProvider, MailboxSyncProvider {
        @Override
        SyncCapabilities capabilities();
    }
}
