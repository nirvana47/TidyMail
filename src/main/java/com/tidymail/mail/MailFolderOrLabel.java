package com.tidymail.mail;

public record MailFolderOrLabel(
    String id,
    String name,
    String type,
    long totalItems,
    long unreadItems
) {
    public static MailFolderOrLabel forLabel(String id, String name) {
        return new MailFolderOrLabel(id, name, "LABEL", 0, 0);
    }

    public static MailFolderOrLabel forFolder(String id, String name) {
        return new MailFolderOrLabel(id, name, "FOLDER", 0, 0);
    }
}
