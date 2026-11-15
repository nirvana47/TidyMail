package com.tidymail.mail;

import java.math.BigInteger;

/** Per-message provider acknowledgement; absent revisions are filled by normal sync. */
public record CleanupResult(BigInteger revision, RuntimeException failure) {
    public boolean accepted() { return failure == null; }
}
