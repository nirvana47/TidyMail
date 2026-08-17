package com.tidymail.service;

import com.tidymail.action.ActionReasonCode;
import com.tidymail.action.ActionType;
import com.tidymail.entity.MessageMetadata;
import com.tidymail.entity.ProtectedSender;
import com.tidymail.repository.MessageMetadataRepository;
import com.tidymail.repository.ProtectedSenderRepository;
import com.tidymail.repository.UserAccountRepository;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProtectionHandler implements ActionHandler {
    private final MessageMetadataRepository metadata;
    private final ProtectedSenderRepository protectedSenders;
    private final UserAccountRepository accounts;
    private final Clock clock;

    public ProtectionHandler(
            MessageMetadataRepository metadata,
            ProtectedSenderRepository protectedSenders,
            UserAccountRepository accounts) {
        this.metadata = metadata;
        this.protectedSenders = protectedSenders;
        this.accounts = accounts;
        this.clock = Clock.systemUTC();
    }

    @Override
    public boolean supports(ActionType actionType) {
        return actionType == ActionType.PROTECT
            || actionType == ActionType.REMOVE_PROTECTION;
    }

    @Override
    @Transactional
    public HandlerResult execute(ExecutionContext context) {
        if (accounts.findAllForUpdateByIdInOrderByIdAsc(
                List.of(context.account().getId())).size() != 1) {
            return HandlerResult.local(ProviderResultClassifier.failed(
                context.targetOrdinals(), ActionReasonCode.STALE_ACTION));
        }
        String intended = normalize(context.intent().getNormalizedSenderAddress());
        List<MessageMetadata> rows = metadata.findByAccountIdAndProviderMessageIdIn(
            context.account().getId(), context.exactTargetIds());
        Set<String> current = rows.stream()
            .map(MessageMetadata::getFromAddress)
            .map(ProtectionHandler::normalize)
            .filter(value -> value != null)
            .collect(Collectors.toSet());
        if (rows.size() != context.exactTargetIds().size()
                || intended == null || current.size() != 1
                || !current.contains(intended)) {
            return HandlerResult.local(ProviderResultClassifier.failed(
                context.targetOrdinals(), ActionReasonCode.STALE_ACTION));
        }
        var existing = protectedSenders.findByAccountIdAndSenderAddress(
            context.account().getId(), intended);
        if (context.actionType() == ActionType.REMOVE_PROTECTION) {
            if (existing.isEmpty()) {
                return HandlerResult.local(ProviderResultClassifier.failed(
                    context.targetOrdinals(), ActionReasonCode.STALE_ACTION));
            }
            protectedSenders.deleteByAccountIdAndSenderAddress(
                context.account().getId(), intended);
        } else if (existing.isEmpty()) {
            protectedSenders.saveAndFlush(ProtectedSender.create(
                context.account().getId(), intended, "USER", clock.instant()));
        }
        return HandlerResult.local(
            ProviderResultClassifier.allSucceeded(context.targetOrdinals()));
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        return value.strip().toLowerCase(Locale.ROOT);
    }
}
