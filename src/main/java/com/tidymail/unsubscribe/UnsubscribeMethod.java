package com.tidymail.unsubscribe;

public enum UnsubscribeMethod {
    RFC8058_POST,
    LEGACY_HTTPS_GET,
    MAILTO,
    ACCOUNT_PAGE,
    MANUAL;

    public boolean automated() {
        return this == RFC8058_POST || this == LEGACY_HTTPS_GET;
    }

    public boolean manual() {
        return !automated();
    }
}
