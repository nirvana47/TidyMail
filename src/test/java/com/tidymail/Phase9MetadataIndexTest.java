package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;

import com.tidymail.entity.MessageMetadata;
import com.tidymail.mail.AttachmentState;
import com.tidymail.mail.MailMessage;
import com.tidymail.mail.MailProvider;
import com.tidymail.service.MessageMetadataMapper;
import com.tidymail.service.MessageMetadataQueryService;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Phase9MetadataIndexTest {

    @Test
    void mapperCopiesOnlyApprovedMetadataAndDefensivelyCopiesCollections() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        ArrayList<String> to = new ArrayList<>(List.of("me@example.com"));
        headers.put("From", List.of("\"Example Sender\" <sender@Example.COM>"));
        headers.put("To", to);
        headers.put("Subject", List.of("Approved subject"));
        headers.put("List-Unsubscribe-Post", List.of("List-Unsubscribe=One-Click"));
        headers.put("X-Private-Unapproved", List.of("must-not-persist"));
        ArrayList<String> labels = new ArrayList<>(List.of("INBOX", "UNREAD"));
        MailMessage message = new MailMessage(
            "m-1", "t-1", "google", Instant.parse("2026-08-09T12:00:00Z"),
            headers, Set.copyOf(labels));

        MessageMetadata row = new MessageMetadataMapper().apply(
            7L, message, null, Instant.parse("2026-08-09T12:01:00Z"));
        to.add("changed@example.com");
        labels.add("CHANGED");

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

    @Test
    void queryServiceHasNoProviderDependencyAndMetadataHasNoForbiddenFields() {
        for (Field field : MessageMetadata.class.getDeclaredFields()) {
            String name = field.getName().toLowerCase();
            assertThat(name).doesNotContain("body", "payload", "mime", "token", "rawheaders");
        }
        assertThat(List.of(MessageMetadata.class.getDeclaredFields()))
            .extracting(Field::getName)
            .contains("providerRevision", "attachmentState", "hasAttachments", "sizeEstimate");
        for (Constructor<?> constructor : MessageMetadataQueryService.class.getConstructors()) {
            assertThat(constructor.getParameterTypes())
                .noneMatch(type -> MailProvider.class.isAssignableFrom(type));
        }
        assertThat(List.of(MessageMetadataQueryService.class.getDeclaredFields()))
            .noneMatch(field -> MailProvider.class.isAssignableFrom(field.getType()));
    }

}
