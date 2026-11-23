package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;

import com.tidymail.entity.MessageMetadata;
import com.tidymail.mail.AttachmentState;
import com.tidymail.mail.MailMessage;
import com.tidymail.service.MessageMetadataMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Phase9MetadataIndexTest {

    @Test
    void mapperMapsSupportedFieldsAndDefensivelyCopiesHeaderCollections() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        ArrayList<String> to = new ArrayList<>(List.of("me@example.com"));
        headers.put("From", List.of("\"Example Sender\" <sender@Example.COM>"));
        headers.put("To", to);
        headers.put("Subject", List.of("Approved subject"));
        headers.put("List-Unsubscribe-Post", List.of("List-Unsubscribe=One-Click"));
        MailMessage message = new MailMessage(
            "m-1", "t-1", "google", Instant.parse("2026-08-09T12:00:00Z"),
            headers, Set.of("INBOX", "UNREAD"));

        MessageMetadata row = new MessageMetadataMapper().apply(
            7L, message, null, Instant.parse("2026-08-09T12:01:00Z"));
        to.add("changed@example.com");

        assertThat(row.getFromAddress()).isEqualTo("sender@example.com");
        assertThat(row.getFromDisplayName()).isEqualTo("Example Sender");
        assertThat(row.getFromDomain()).isEqualTo("example.com");
        assertThat(row.getUnread()).isTrue();
        assertThat(row.getProviderRevision()).isNull();
        assertThat(row.getAttachmentState()).isEqualTo(AttachmentState.UNKNOWN);
        assertThat(row.getToValues()).containsExactly("me@example.com");
        assertThat(row.getLabels()).containsExactlyInAnyOrder("INBOX", "UNREAD");
        assertThat(row.getListUnsubscribePostValues())
            .containsExactly("List-Unsubscribe=One-Click");
    }

}
