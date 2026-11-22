package com.tidymail;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tidymail.mail.gmail.GmailSyncConfigProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class Phase4ConfigValidationTest {

    @Test
    @DisplayName("GmailSyncConfigProperties.validate() — apiConcurrency=0 throws ISE")
    void gmailSyncConcurrencyZeroThrows() {
        GmailSyncConfigProperties props = new GmailSyncConfigProperties();
        props.setApiConcurrency(0);
        props.setBatchDelayMs(0);
        assertThatThrownBy(props::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SYNC_GMAIL_API_CONCURRENCY must be >= 1");
    }

    @Test
    @DisplayName("GmailSyncConfigProperties.validate() — apiConcurrency=21 throws ISE with max-is-20 message")
    void gmailSyncConcurrencyTwentyOneThrows() {
        GmailSyncConfigProperties props = new GmailSyncConfigProperties();
        props.setApiConcurrency(21);
        props.setBatchDelayMs(0);
        assertThatThrownBy(props::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("max is 20");
    }

}
