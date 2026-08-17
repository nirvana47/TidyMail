package com.tidymail.service;

import com.tidymail.entity.MessageMetadata;
import com.tidymail.mail.MailMessage;
import com.tidymail.repository.MessageMetadataRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Upserts a page of provider messages into message metadata in its own
 * transaction, deduplicating by provider message ID and reusing existing rows.
 *
 * <p>Product functionality supported: the metadata-only sync's page commits.
 */
@Service
public class MessageMetadataPageService {

    private final MessageMetadataRepository repository;
    private final MessageMetadataMapper mapper;

    public MessageMetadataPageService(
            MessageMetadataRepository repository, MessageMetadataMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /** Upserts a page of messages, returning the number stored. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int upsertPage(Long accountId, List<MailMessage> messages, Instant syncedAt) {
        Map<String, MailMessage> uniqueMessages = new LinkedHashMap<>();
        for (MailMessage message : messages) {
            if (message != null && message.id() != null && !message.id().isBlank()) {
                uniqueMessages.put(message.id(), message);
            }
        }
        if (uniqueMessages.isEmpty()) return 0;

        Map<String, MessageMetadata> existingByProviderId = new LinkedHashMap<>();
        repository.findByAccountIdAndProviderMessageIdIn(accountId, uniqueMessages.keySet())
            .forEach(row -> existingByProviderId.put(row.getProviderMessageId(), row));

        List<MessageMetadata> rows = new ArrayList<>(uniqueMessages.size());
        for (MailMessage message : uniqueMessages.values()) {
            rows.add(mapper.apply(
                accountId, message, existingByProviderId.get(message.id()), syncedAt));
        }
        repository.saveAll(rows);
        return rows.size();
    }
}
