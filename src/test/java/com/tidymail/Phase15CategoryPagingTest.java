package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.dto.CategoryPage;
import com.tidymail.dto.SenderDecisionRow;
import com.tidymail.dto.SenderQuery;
import com.tidymail.repository.DecisionSurfaceQueryRepository;
import com.tidymail.service.SenderDecisionQueryService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.task.scheduling.enabled=false",
    "TOKEN_ENCRYPT_SECRET=0000000000000000000000000000000000000000000000000000000000000000",
    "TOKEN_ENCRYPT_SALT=00000000000000000000000000000000",
    "GOOGLE_CLIENT_ID=",
    "GOOGLE_CLIENT_SECRET=",
    "MICROSOFT_CLIENT_ID=",
    "MICROSOFT_CLIENT_SECRET=",
    "MICROSOFT_TENANT_ID=common"
})
class Phase15CategoryPagingTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;
    @Autowired SenderDecisionQueryService senderService;
    @SpyBean DecisionSurfaceQueryRepository repository;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM user_accounts");
        clearInvocations(repository);
    }

    @Test
    void exactSummariesAndTiedRowsUseStableFiveRowPagesWithoutStreamingBodies()
            throws Exception {
        long accountId = account("paging@example.com");
        for (int sender = 0; sender < 12; sender++) {
            for (int message = 0; message < 2; message++) {
                message(accountId, "sender%02d@example.com".formatted(sender),
                    "tie-" + sender + "-" + message, 100);
            }
        }
        SenderQuery query = query();

        var groups = senderService.categories(List.of(accountId), query, null);
        var other = groups.stream()
            .filter(group -> group.key().equals("other"))
            .findFirst()
            .orElseThrow();
        assertThat(other.senderCount()).isEqualTo(12);
        assertThat(other.estimatedSize()).isEqualTo(2_400);
        assertThat(List.of(other.getClass().getRecordComponents()))
            .extracting(component -> component.getName())
            .doesNotContain("rows");
        verify(repository, never()).streamSenders(
            anyCollection(), any(SenderQuery.class), any(Instant.class), isNull());

        CategoryPage<SenderDecisionRow> first =
            senderService.categoryPage(List.of(accountId), query, null, "other", 0, false);
        CategoryPage<SenderDecisionRow> second =
            senderService.categoryPage(List.of(accountId), query, null, "other", 5, false);

        assertThat(first.rows()).extracting(SenderDecisionRow::senderAddress)
            .containsExactly(
                "sender00@example.com", "sender01@example.com", "sender02@example.com",
                "sender03@example.com", "sender04@example.com");
        assertThat(second.rows()).extracting(SenderDecisionRow::senderAddress)
            .containsExactly(
                "sender05@example.com", "sender06@example.com", "sender07@example.com",
                "sender08@example.com", "sender09@example.com");
        assertThat(first.loadedCount()).isEqualTo(5);
        assertThat(first.exactTotal()).isEqualTo(12);
        assertThat(first.loadAllAvailable()).isFalse();
        assertThat(second.loadedCount()).isEqualTo(10);
        assertThat(second.hasMore()).isTrue();
        assertThat(second.loadAllAvailable()).isTrue();
    }

    @Test
    void loadAllAllowsOneThousandAndRejectsOneThousandOneBeforeRowQuery()
            throws Exception {
        long allowed = account("allowed@example.com");
        messages(allowed, 1_000);
        CategoryPage<SenderDecisionRow> loaded =
            senderService.categoryPage(List.of(allowed), query(), null, "other", 10, true);
        assertThat(loaded.rows()).hasSize(990);
        assertThat(loaded.loadedCount()).isEqualTo(1_000);
        assertThat(loaded.hasMore()).isFalse();

        long rejected = account("rejected@example.com");
        messages(rejected, 1_001);
        clearInvocations(repository);
        assertThatThrownBy(() ->
            senderService.categoryPage(List.of(rejected), query(), null, "other", 10, true))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> {
                ResponseStatusException response = (ResponseStatusException) error;
                assertThat(response.getStatusCode()).isEqualTo(
                    HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(response.getReason()).contains("Continue with Load more");
            });
        verify(repository, never()).senderCategoryRows(
            anyCollection(), any(SenderQuery.class), anyString(), anyLong(), anyInt(),
            any(Instant.class), isNull());
    }

    @Test
    void unknownCategoryAndForeignAccountExposeNoRows() throws Exception {
        long owned = account("owned@example.com");
        long foreign = account("foreign@example.com");
        message(owned, "sender@example.com", "owned-message", 100);

        CategoryPage<SenderDecisionRow> unknown =
            senderService.categoryPage(List.of(owned), query(), null, "not-a-category", 0, false);
        assertThat(unknown.rows()).isEmpty();
        assertThat(unknown.exactTotal()).isZero();

        SenderQuery foreignQuery = SenderQuery.parse(Map.of(
            "account", List.of(Long.toString(foreign)),
            "window", List.of("all"),
            "queue", List.of("all"),
            "protected", List.of("include")));
        assertThatThrownBy(() ->
            senderService.categoryPage(
                List.of(owned), foreignQuery, null, "other", 0, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Authorized account scope does not match query");
    }

    @Test
    void senderPageRendersBoundedInitialRowsAndCategoryRouteReturnsFiveRowChunks()
            throws Exception {
        String email = "web-paging@example.com";
        long accountId = account(email);
        for (int sender = 0; sender < 12; sender++) {
            message(accountId, "web%02d@example.com".formatted(sender),
                "web-message-" + sender, 100);
        }

        String initial = mockMvc.perform(get("/senders")
                .param("account", Long.toString(accountId))
                .param("window", "all")
                .with(authentication(auth(email))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(initial)
            .contains("12 senders")
            .contains("/senders/categories/other")
            .contains("web00@example.com", "web04@example.com")
            .doesNotContain("web05@example.com", "web11@example.com");
        verify(repository, never()).senders(
            anyCollection(), any(SenderQuery.class), any(Instant.class), isNull());
        verify(repository).senderCategoryRows(
            anyCollection(), any(SenderQuery.class), eq("other"), eq(0L), eq(5),
            any(Instant.class), isNull());

        String first = mockMvc.perform(get("/senders/categories/other")
                .param("account", Long.toString(accountId))
                .param("window", "all")
                .param("offset", "0")
                .param("loadAll", "false")
                .with(authentication(auth(email))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(first)
            .contains("web00@example.com", "web04@example.com", "Load more")
            .contains("cleanup-row-head", "Sender", "Domain", "All Mail unread",
                "Size (Estimated)")
            .doesNotContain("web05@example.com", "web11@example.com", "Load all",
                "<span>Action</span>");

        String second = mockMvc.perform(get("/senders/categories/other")
                .param("account", Long.toString(accountId))
                .param("window", "all")
                .param("offset", "5")
                .param("loadAll", "false")
                .header("HX-Request", "true")
                .with(authentication(auth(email))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(second)
            .contains("web05@example.com", "web09@example.com", "Load more", "Load all")
            .doesNotContain("web00@example.com", "web04@example.com", "web10@example.com",
                "cleanup-row-head", "<span>Sender</span>", "<span>Domain</span>",
                "<span>All Mail unread</span>", "<span>Size (Estimated)</span>",
                "<span>Action</span>");
    }

    @Test
    void oversizedLoadAllReturnsTargetedGuidanceForHtmxAndNativeCallers()
            throws Exception {
        String email = "web-rejected@example.com";
        long accountId = account(email);
        messages(accountId, 1_001);

        String body = mockMvc.perform(get("/senders/categories/other")
                .param("account", Long.toString(accountId))
                .param("window", "all")
                .param("offset", "10")
                .param("loadAll", "true")
                .header("HX-Request", "true")
                .with(authentication(auth(email))))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(header().string(
                "HX-Retarget", "#category-error-other"))
            .andExpect(header().string("HX-Reswap", "innerHTML"))
            .andReturn().getResponse().getContentAsString();
        assertThat(body)
            .contains("more than 1,000 senders", "Continue with Load more")
            .doesNotContain("sender0000@example.com");
    }

    private SenderQuery query() {
        return SenderQuery.parse(Map.of(
            "window", List.of("all"),
            "queue", List.of("all"),
            "protected", List.of("include")));
    }

    private long account(String email) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO user_accounts(
                    owner_key,email,provider,provider_account_id,encrypted_access_token,
                    encrypted_refresh_token,scopes)
                VALUES (?,?,'google',?,decode('01','hex'),decode('02','hex'),ARRAY['openid'])
                """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, email + ":google");
            statement.setString(2, email);
            statement.setString(3, "provider-" + email);
            return statement;
        }, keys);
        return ((Number) keys.getKeys().get("id")).longValue();
    }

    private void messages(long accountId, int count) throws Exception {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            for (int index = 0; index < count; index++) {
                message(connection, accountId,
                    "sender%04d@example.com".formatted(index), "message-" + index, 100);
            }
            connection.commit();
        }
    }

    private void message(long accountId, String sender, String messageId, long size)
            throws Exception {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            message(connection, accountId, sender, messageId, size);
        }
    }

    private static void message(
            Connection connection, long accountId, String sender, String messageId, long size)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO message_metadata(
                account_id,provider,provider_message_id,from_address,from_display_name,
                from_domain,received_at,last_synced_at,unread,size_estimate)
            VALUES (?,'google',?,?,?, 'example.com',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,true,?)
            """)) {
            statement.setLong(1, accountId);
            statement.setString(2, messageId);
            statement.setString(3, sender);
            statement.setString(4, sender);
            statement.setLong(5, size);
            statement.executeUpdate();
        }
    }

    private static OAuth2AuthenticationToken auth(String email) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("email", email);
        attributes.put("name", "Workspace Owner");
        attributes.put("sub", "subject-" + email);
        List<GrantedAuthority> authorities =
            List.of(new SimpleGrantedAuthority("ROLE_USER"));
        DefaultOAuth2User user = new DefaultOAuth2User(authorities, attributes, "sub");
        return new OAuth2AuthenticationToken(user, authorities, "google");
    }
}
