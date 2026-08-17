package com.tidymail.dto;

public record ActivityPageModel(
    WorkspacePageModel workspace,
    String providerName,
    ActivityStatusModel status
) {
}
