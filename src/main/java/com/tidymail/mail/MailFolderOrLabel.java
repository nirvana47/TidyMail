package com.tidymail.mail;

/**
 * Represents a mail folder or label container used to organize messages.
 * <p>
 * Product functionality supported: listing folders/labels and reporting item
 * and unread counts for inbox cleanup decisions.
 */
public record MailFolderOrLabel(
    String id,
    String name,
    String type,
    long totalItems,
    long unreadItems
) {
    /**
     * Builds a label-style container with zero item counts.
     */
    public static MailFolderOrLabel forLabel(String id, String name) {
        return new MailFolderOrLabel(id, name, "LABEL", 0, 0);
    }

    /**
     * Builds a folder-style container with zero item counts.
     */
    public static MailFolderOrLabel forFolder(String id, String name) {
        return new MailFolderOrLabel(id, name, "FOLDER", 0, 0);
    }
}
