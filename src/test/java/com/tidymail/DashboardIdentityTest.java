package com.tidymail;

import static org.assertj.core.api.Assertions.*;
import com.tidymail.service.DashboardIdentity;
import java.util.List;
import org.junit.jupiter.api.Test;

class DashboardIdentityTest {
    @Test void displayNameBracketsNeverBecomeMailboxDelimiters() {
        for (String from : List.of("\"Alerts > Updates\" <sender@example.test>",
                "\"Alerts < Updates\" <sender@example.test>",
                "\"Alerts <old@example.test>\" <sender@example.test>")) {
            assertThat(DashboardIdentity.mailbox(from)).isEqualTo("sender@example.test");
        }
        assertThat(DashboardIdentity.mailbox("\"Alerts > Updates\" <invalid>")).isNull();
        assertThat(DashboardIdentity.mailbox("Alerts > Updates <sender@example.test>")).isNull();
    }
    @Test void providerAndMapperPreserveMissingVersusObservedEmptyLabels() {
        var provider=new com.google.api.services.gmail.model.Message().setId("synthetic-label-evidence");
        var mapper=new com.tidymail.service.MessageMetadataMapper();
        var unknown=com.tidymail.mail.gmail.GmailMailService.toMailMessage("google",provider);
        assertThat(unknown.labelIds()).isNull();
        assertThat(mapper.apply(1L,unknown,null,java.time.Instant.now()).isLabelEvidenceKnown()).isFalse();
        provider.setLabelIds(List.of());
        var known=com.tidymail.mail.gmail.GmailMailService.toMailMessage("google",provider);
        assertThat(known.labelIds()).isEmpty();
        assertThat(mapper.apply(1L,known,null,java.time.Instant.now()).isLabelEvidenceKnown()).isTrue();
    }
    @Test void conservativeIdentityPreservesLocalPartAndRejectsAmbiguity() {
        assertThat(DashboardIdentity.mailbox("\"Doe, Jane\" <Jane+tag@EXAMPLE.COM>")).isEqualTo("Jane+tag@example.com");
        assertThat(DashboardIdentity.mailbox("Name <Jane@bücher.example>")).isEqualTo("Jane@xn--bcher-kva.example");
        for(String invalid:List.of("a@example.test,b@example.test","One <a@example.test>, Two <b@example.test>","a..b@example.test","a@bad..example","a@example.test\r\nBcc: b@example.test"))assertThat(DashboardIdentity.mailbox(invalid)).isNull();
        assertThat(DashboardIdentity.parse(List.of("a@example.test","b@example.test"),List.of(),"one").kind()).isEqualTo("MESSAGE");
        assertThat(DashboardIdentity.parse(List.of("a@example.test"),List.of("Bad list"),"two").kind()).isEqualTo("MESSAGE");
        assertThat(DashboardIdentity.parse(List.of("a@example.test"),List.of("One <a.example>","Two <b.example>"),"two").kind()).isEqualTo("MESSAGE");
        assertThat(DashboardIdentity.parse(List.of("a@example.test"),List.of("One <list.EXAMPLE>"),"three").key()).isEqualTo("list.example");
    }
}
