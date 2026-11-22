package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.entity.UserAccount;
import com.tidymail.mail.MailProvider;
import com.tidymail.mail.PageResult;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.security.crypto.CryptoUtil;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

class Phase7BulkActionsTest {

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
    @AutoConfigureMockMvc
    @org.springframework.transaction.annotation.Transactional
    @TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "GOOGLE_CLIENT_ID=your-dummy-google-client-id",
        "GOOGLE_CLIENT_SECRET=your-dummy-google-secret",
        "SYNC_GMAIL_API_CONCURRENCY=2",
        "SYNC_GMAIL_BATCH_DELAY_MS=0",
        "TOKEN_ENCRYPT_SECRET=00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
        "TOKEN_ENCRYPT_SALT=ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100",
        "MICROSOFT_CLIENT_ID=",
        "MICROSOFT_CLIENT_SECRET=",
        "MICROSOFT_TENANT_ID=common",
    })
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @DisplayName("T7d Page Rendering")
    class T7d_PageRenderingTests {

        @Autowired
        MockMvc mockMvc;

        @Autowired
        UserAccountRepository userAccounts;

        @Autowired
        CryptoUtil cryptoUtil;

        @MockBean(name = "gmailMailService")
        MailProvider gmailProvider;

        @MockBean(name = "outlookMailService")
        MailProvider outlookProvider;

        private UserAccount insertMeAccount() {
            UserAccount me = new UserAccount();
            me.setEmail("me-t7d@example.com");
            me.setProvider("google");
            me.setProviderAccountId("google-acc-t7d");
            me.setDisplayName("Me T7d");
            me.setEncryptedAccessToken(cryptoUtil.encrypt("fake-access".getBytes(StandardCharsets.UTF_8)));
            me.setEncryptedRefreshToken(cryptoUtil.encrypt("fake-refresh".getBytes(StandardCharsets.UTF_8)));
            me.setScopes(List.of("openid", "email", "profile"));
            me.setAesKeyId("hkdf-sha256-v1");
            return userAccounts.save(me);
        }

        @BeforeEach
        void cleanDb() {
            userAccounts.deleteAll();
        }

        private OAuth2AuthenticationToken buildAuthToken(String email, String regId) {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("email", email);
            attrs.put("sub", "fake-sub-" + email);
            List<GrantedAuthority> auths = List.of(new SimpleGrantedAuthority("ROLE_USER"));
            DefaultOAuth2User user = new DefaultOAuth2User(auths, attrs, "sub");
            return new OAuth2AuthenticationToken(user, auths, regId);
        }

        @Test
        @Order(1)
        @DisplayName("T7_d_01 GET /dashboard renders indexed browsing without provider calls")
        void T7_d_01_dashboardLayout() throws Exception {
            insertMeAccount();

            MvcResult res = mockMvc.perform(get("/dashboard")
                    .with(authentication(buildAuthToken("me-t7d@example.com", "google"))))
                .andExpect(status().isOk())
                .andReturn();

            assertThat(res.getModelAndView().getViewName()).isEqualTo("dashboard");
            verify(gmailProvider, times(0)).listMessages(any(), any(), anyInt());
        }

        @Test
        @Order(10)
        @DisplayName("T7_d_02 GET /senders-frag empty → empty-senders class present")
        void T7_d_02_sendersFragEmpty() throws Exception {
            insertMeAccount();
            when(gmailProvider.listMessages(any(), any(), anyInt()))
                .thenReturn(PageResult.of(List.of(), null, 100));

            MvcResult res = mockMvc.perform(get("/senders-frag")
                    .with(authentication(buildAuthToken("me-t7d@example.com", "google"))))
                .andExpect(status().isOk())
                .andReturn();

            String body = res.getResponse().getContentAsString();
            assertThat(body).contains("empty-senders");
        }

    }
}
