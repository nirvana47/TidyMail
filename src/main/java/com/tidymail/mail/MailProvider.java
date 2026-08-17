package com.tidymail.mail;

import com.tidymail.entity.UserAccount;
import java.util.List;

public interface MailProvider {
    default ProviderCapabilities capabilities() {
        return ProviderCapabilities.unknown();
    }

    PageResult<MailMessage> listMessages(UserAccount account, String pageToken, int maxResults);

    List<MailFolderOrLabel> listFoldersOrLabels(UserAccount account);

    BulkActionResult batchModify(UserAccount account, BulkActionRequest request);

    BulkActionResult applyLabel(UserAccount account, List<String> messageIds, String labelId, boolean add);

    MailThreadSummary getThreadMetadata(UserAccount account, String threadId);
}
