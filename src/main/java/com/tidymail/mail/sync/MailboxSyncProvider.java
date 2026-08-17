package com.tidymail.mail.sync;

import com.tidymail.entity.UserAccount;
import com.tidymail.mail.MailMessage;
import com.tidymail.mail.PageResult;
import java.util.List;

public interface MailboxSyncProvider {

    default SyncCapabilities capabilities() {
        return SyncCapabilities.none();
    }

    MailboxSnapshot profile(UserAccount account);

    List<MailMessage> listRecentBootstrap(UserAccount account, int maxResults);

    PageResult<MailMessage> listBackfill(UserAccount account, BackfillRequest request);

    ChangePage listChanges(UserAccount account, ChangeRequest request);

    List<MailMessage> getCurrentMetadata(UserAccount account, List<String> messageIds);

    AttachmentMembershipPage listAttachmentMembership(UserAccount account, String pageToken);
}
