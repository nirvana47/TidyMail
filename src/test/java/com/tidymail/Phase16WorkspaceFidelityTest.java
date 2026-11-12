package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;

import com.tidymail.dto.AccountScopeOption;
import com.tidymail.dto.UnreadScopeCounts;
import com.tidymail.dto.WorkspacePageModel;
import com.tidymail.dto.WorkspaceRoute;
import com.tidymail.dto.WorkspaceState;
import java.util.List;
import org.junit.jupiter.api.Test;

class Phase16WorkspaceFidelityTest {
    @Test
    void pendingUnreadCountsAreUnavailable() {
        WorkspacePageModel unavailable = new WorkspacePageModel(
            WorkspaceRoute.REVIEW,
            "Owner",
            "owner@example.com",
            List.of(new AccountScopeOption("1", "owner@example.com", true)),
            List.of(1L),
            "1",
            "owner@example.com",
            UnreadScopeCounts.pending(),
            0,
            0,
            0,
            null,
            null,
            WorkspaceState.EMPTY,
            false,
            true,
            false);
        assertThat(unavailable.inboxUnreadCountAvailable()).isFalse();
    }
}
