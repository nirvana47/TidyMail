package com.tidymail.dto;

/**
 * The lifecycle state of the workspace's mailbox indexing/sync: loading, empty,
 * partial, stale, error, or ready.
 *
 * <p>Product functionality supported: the workspace status display and the
 * availability of sync actions across all pages.
 */
public enum WorkspaceState {
    LOADING,
    EMPTY,
    PARTIAL,
    STALE,
    ERROR,
    READY
}
