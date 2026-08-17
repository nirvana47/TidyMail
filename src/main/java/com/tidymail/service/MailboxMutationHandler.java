package com.tidymail.service;

import com.tidymail.action.ActionType;
import com.tidymail.mail.BulkActionRequest;
import com.tidymail.mail.BulkActionType;
import com.tidymail.mail.MailProvider;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * Handles provider mailbox mutations (archive, trash, mark-read) by dispatching
 * the exact target IDs to the account's mail provider and classifying the result.
 *
 * <p>Product functionality supported: the action execution engine's provider
 * cleanup mutations.
 */
@Service
public class MailboxMutationHandler implements ActionHandler {
    private final Map<String, MailProvider> providers;

    public MailboxMutationHandler(Map<String, MailProvider> providers) {
        this.providers = providers;
    }

    /** Returns true for archive, trash, and mark-read actions. */
    @Override
    public boolean supports(ActionType actionType) {
        return actionType == ActionType.ARCHIVE
            || actionType == ActionType.TRASH
            || actionType == ActionType.MARK_READ;
    }

    /** Executes the provider mutation and classifies its result. */
    @Override
    public HandlerResult execute(ExecutionContext context) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Provider I/O cannot run in a transaction");
        }
        if (!supports(context.actionType())) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "unsupported_action");
        }
        var result = provider(context.account()).batchModify(
            context.account(),
            BulkActionRequest.of(
                bulkType(context.actionType()), context.exactTargetIds()));
        return HandlerResult.mailbox(
            ProviderResultClassifier.classify(context.targetOrdinals(), result));
    }

    /** Maps an action type to its provider bulk-action type. */
    private static BulkActionType bulkType(ActionType actionType) {
        return switch (actionType) {
            case ARCHIVE -> BulkActionType.ARCHIVE;
            case TRASH -> BulkActionType.TRASH;
            case MARK_READ -> BulkActionType.MARK_READ;
            default -> throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "unsupported_action");
        };
    }

    /** Resolves the mail provider bean for the account's provider. */
    private MailProvider provider(com.tidymail.entity.UserAccount account) {
        String beanName = switch (account.getProvider()) {
            case "google" -> "gmailMailService";
            case "microsoft" -> "outlookMailService";
            default -> throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "unknown_provider");
        };
        MailProvider provider = providers.get(beanName);
        if (provider == null) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "provider_unavailable");
        }
        return provider;
    }
}
