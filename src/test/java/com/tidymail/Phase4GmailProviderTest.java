package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.MailMessage;
import com.tidymail.mail.gmail.GmailClientFactory;
import com.tidymail.mail.gmail.GmailClientInitException;
import com.tidymail.mail.gmail.GmailMailService;
import com.tidymail.security.oauth2.ManagedGoogleCredentialService.CredentialAuthorizationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "TOKEN_ENCRYPT_SECRET=0000000000000000000000000000000000000000000000000000000000000000",
    "TOKEN_ENCRYPT_SALT=00000000000000000000000000000000",
    "GOOGLE_CLIENT_ID=",
    "GOOGLE_CLIENT_SECRET=",
    "MICROSOFT_CLIENT_ID=",
    "MICROSOFT_CLIENT_SECRET=",
    "MICROSOFT_TENANT_ID=common"
})
class Phase4GmailProviderTest {

    @DynamicPropertySource
    static void googleOAuthOverride(DynamicPropertyRegistry registry) {
        registry.add("GOOGLE_CLIENT_ID", () -> "your-dummy-test-client-id");
        registry.add("GOOGLE_CLIENT_SECRET", () -> "your-dummy-test-client-secret");
    }

    @Autowired
    private GmailClientFactory gmailClientFactory;

    @Test
    @DisplayName("T4(a) — METADATA whitelist guard — 24 exact headers, no format=full allowed")
    void metadataWhitelistGuard() {
        Set<String> expectedSet = Set.of(
            "From",
            "To",
            "Cc",
            "Bcc",
            "Subject",
            "Date",
            "List-Unsubscribe",
            "List-Unsubscribe-Post",
            "List-Id",
            "Precedence",
            "Auto-Submitted",
            "Return-Path",
            "Reply-To",
            "Message-ID",
            "References",
            "In-Reply-To",
            "Delivered-To",
            "Received-SPF",
            "Authentication-Results",
            "DKIM-Signature",
            "X-Auto-Response-Suppress",
            "X-Mailer",
            "X-Mailing-List",
            "Feedback-ID"
        );

        assertThat(GmailMailService.METADATA_HEADERS.size())
            .as("METADATA_HEADERS size matches Set's reported size")
            .isEqualTo(expectedSet.size());

        assertThat(GmailMailService.METADATA_HEADERS)
            .as("METADATA_HEADERS contains exactly the PLAN whitelist headers")
            .isEqualTo(expectedSet);

        Message msgWithRaw = new Message();
        msgWithRaw.setId("msg-raw-1");
        msgWithRaw.setRaw("Zm9v");
        assertThatThrownBy(() -> GmailMailService.toMailMessage("google", msgWithRaw))
            .as("Non-empty raw field triggers METADATA guard")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("format is not METADATA");

        Message msgWithBodyData = new Message();
        msgWithBodyData.setId("msg-body-1");
        MessagePart payloadWithBody = new MessagePart();
        payloadWithBody.setBody(new MessagePartBody().setData("aGVsbG8="));
        msgWithBodyData.setPayload(payloadWithBody);
        assertThatThrownBy(() -> GmailMailService.toMailMessage("google", msgWithBodyData))
            .as("Non-empty payload.body.data triggers METADATA guard")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("payload.body.data");

        Message msgWithNestedBodyData = new Message().setId("msg-parts-1");
        MessagePart payloadWithParts = new MessagePart();
        List<MessagePart> partsList = new ArrayList<>();
        partsList.add(new MessagePart()
            .setBody(new MessagePartBody().setData("aGVsbG8=")));
        payloadWithParts.setParts(partsList);
        msgWithNestedBodyData.setPayload(payloadWithParts);
        assertThatThrownBy(() ->
                GmailMailService.toMailMessage("google", msgWithNestedBodyData))
            .as("Body data nested in MIME part metadata triggers privacy guard")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("payload.body.data");
    }

    @Test
    @DisplayName("T4(b) — MailMessage.body() throws UnsupportedOperationException METADATA-only")
    void mailMessageBodyThrowsMetadataOnly() {
        MailMessage mm = new MailMessage("id1", "thread1", "google", null, java.util.Map.of(), Set.of());
        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class, mm::body);
        assertThat(ex.getMessage()).as("Exception message mentions METADATA-only contract")
            .contains("METADATA-only");
    }

    @Test
    @DisplayName("T4(c) — GmailClientFactory rejects non-Google and requires managed persisted credentials")
    void gmailClientFactoryBuildForRejectsNonGoogleAndValidatesCredentialFlow() {
        UserAccount microsoftAccount = new UserAccount();
        microsoftAccount.setProvider("microsoft");
        microsoftAccount.setEmail("x@y.com");
        microsoftAccount.setProviderAccountId("abc123");
        microsoftAccount.setEncryptedAccessToken(new byte[]{1, 2, 3});
        microsoftAccount.setEncryptedRefreshToken(new byte[]{4, 5, 6});

        assertThatThrownBy(() -> gmailClientFactory.buildFor(microsoftAccount))
            .as("Non-google provider rejected with descriptive error")
            .isInstanceOf(GmailClientInitException.class)
            .hasMessageContaining("only handles GOOGLE accounts");

        UserAccount googleAccount = new UserAccount();
        googleAccount.setProvider("google");
        googleAccount.setEmail("test-google@example.com");
        googleAccount.setProviderAccountId("google-account-999");

        assertThatThrownBy(() -> gmailClientFactory.buildFor(googleAccount))
            .as("Google account without a matching persisted provider identity requires reauthorization")
            .isInstanceOf(CredentialAuthorizationException.class)
            .hasMessage("REAUTH_REQUIRED");
    }

    @Test
    @DisplayName("T4(e) — GmailMailService.extractDomain helper RFC5322 address parsing (6 cases)")
    void extractDomainHelperTests() {
        assertThat(GmailMailService.extractDomain("user@example.com"))
            .as("Plain user@domain → domain")
            .isEqualTo("example.com");

        assertThat(GmailMailService.extractDomain("Display Name <addr@sub.domain.co.uk>"))
            .as("Display Name <addr@sub.domain.co.uk> → sub.domain.co.uk")
            .isEqualTo("sub.domain.co.uk");

        assertThat(GmailMailService.extractDomain("no-at-sign"))
            .as("No @ sign → null")
            .isNull();

        assertThat(GmailMailService.extractDomain("Name <bogus>"))
            .as("No @ inside angle brackets → null")
            .isNull();

        assertThat(GmailMailService.extractDomain(""))
            .as("Empty string → null")
            .isNull();

        assertThat(GmailMailService.extractDomain("@nodomainprefix.com"))
            .as("@ prefix without local part → null")
            .isNull();

        assertThat(GmailMailService.extractDomain("Addr@"))
            .as("Trailing @ without domain → null")
            .isNull();
    }
}
