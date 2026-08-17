package com.tidymail.dto;

/**
 * View model for the Activity page: the workspace chrome, the mail provider
 * name, and the live sync/command status.
 *
 * <p>Product functionality supported: the <em>Activity</em> workspace tab.</p>
 */
public record ActivityPageModel(
    WorkspacePageModel workspace,
    String providerName,
    ActivityStatusModel status
) {
}
