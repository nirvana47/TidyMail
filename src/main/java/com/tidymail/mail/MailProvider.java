package com.tidymail.mail;

import com.tidymail.entity.UserAccount;
import java.util.List;

/**
 * Provider abstraction for a mail backend (e.g. Gmail, Outlook) used by
 * TidyMail's provider layer.
 * <p>
 * Product functionality supported: metadata-only mailbox sync and bulk
 * mailbox mutations (archive/trash/mark-read). Implementers expose their
 * capabilities and translate requests into provider-specific calls.
 */
public interface MailProvider {
    /**
     * Describes what this provider can report reliably; unknown by default.
     */
    default ProviderCapabilities capabilities() {
        return ProviderCapabilities.unknown();
    }

    /**
     * Lists a page of message metadata for the account's mailbox.
     */
    PageResult<MailMessage> listMessages(UserAccount account, String pageToken, int maxResults);

    /**
     * Lists the folders or labels available for the account.
     */
    List<MailFolderOrLabel> listFoldersOrLabels(UserAccount account);

    /**
     * Applies a bulk mutation, returning exact per-message outcomes.
     */
    BulkActionResult batchModify(UserAccount account, BulkActionRequest request);

    /**
     * Adds or removes a label on the given messages in bulk.
     */
    BulkActionResult applyLabel(UserAccount account, List<String> messageIds, String labelId, boolean add);

    /**
     * Fetches metadata-only summary for a single thread.
     */
    MailThreadSummary getThreadMetadata(UserAccount account, String threadId);
}
