package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tidymail.entity.UserAccount;
import com.tidymail.mail.BulkActionRequest;
import com.tidymail.mail.BulkActionType;
import com.tidymail.mail.MailFolderOrLabel;
import com.tidymail.mail.MailProvider;
import com.tidymail.mail.gmail.GmailSyncConfigProperties;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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

    @Test
    @DisplayName("BulkActionRequest of 1001 IDs throws IllegalArgumentException with Gmail batchModify max 1000 message")
    void bulkActionRequest1001IdsThrows() {
        List<String> ids = IntStream.range(0, 1001)
            .mapToObj(i -> "msg-" + i)
            .collect(Collectors.toList());

        assertThatThrownBy(() -> new BulkActionRequest(BulkActionType.ARCHIVE, ids, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Gmail batchModify JSON API max is 1000 ids per call");
    }

    @Test
    @DisplayName("BulkActionRequest of 500 IDs succeeds without throw")
    void bulkActionRequest500IdsSucceeds() {
        List<String> ids = IntStream.range(0, 500)
            .mapToObj(i -> "msg-" + i)
            .collect(Collectors.toList());

        assertThatCode(() -> BulkActionRequest.of(BulkActionType.ARCHIVE, ids))
            .doesNotThrowAnyException();

        BulkActionRequest req = BulkActionRequest.of(BulkActionType.ARCHIVE, ids);
        assertThat(req.messageIds()).hasSize(500);
        assertThat(req.actionType()).isEqualTo(BulkActionType.ARCHIVE);
    }

    @Test
    @DisplayName("MailProvider interface includes required methods with stable listMessages signature")
    void mailProviderInterfaceSignatureVerification() throws NoSuchMethodException {
        Method[] methods = MailProvider.class.getDeclaredMethods();
        Set<String> methodNames = java.util.Arrays.stream(methods)
            .map(Method::getName)
            .collect(Collectors.toSet());

        assertThat(methodNames)
            .as("MailProvider includes the required method names")
            .contains(
                "listMessages",
                "listFoldersOrLabels",
                "batchModify",
                "applyLabel",
                "getThreadMetadata"
            );

        Method listMessages = MailProvider.class.getMethod(
            "listMessages",
            UserAccount.class,
            String.class,
            int.class
        );

        Parameter[] params = listMessages.getParameters();
        assertThat(params.length).as("listMessages has 3 parameters").isEqualTo(3);
        assertThat(params[0].getType()).as("listMessages p0 = UserAccount").isEqualTo(UserAccount.class);
        assertThat(params[1].getType()).as("listMessages p1 = String (pageToken)").isEqualTo(String.class);
        assertThat(params[2].getType()).as("listMessages p2 = int (maxResults)").isEqualTo(int.class);
    }

    @Test
    @DisplayName("MailFolderOrLabel.forLabel + forFolder create correct entries with distinct type fields")
    void mailFolderOrLabelFactoryMethods() {
        MailFolderOrLabel label = MailFolderOrLabel.forLabel("lbl-001", "Newsletters");
        assertThat(label.id()).isEqualTo("lbl-001");
        assertThat(label.name()).isEqualTo("Newsletters");
        assertThat(label.type()).as("forLabel sets type=LABEL").isEqualTo("LABEL");
        assertThat(label.totalItems()).as("factory defaults totalItems to 0").isZero();
        assertThat(label.unreadItems()).as("factory defaults unreadItems to 0").isZero();

        MailFolderOrLabel folder = MailFolderOrLabel.forFolder("fld-999", "Inbox");
        assertThat(folder.id()).isEqualTo("fld-999");
        assertThat(folder.name()).isEqualTo("Inbox");
        assertThat(folder.type()).as("forFolder sets type=FOLDER").isEqualTo("FOLDER");
        assertThat(folder.totalItems()).isZero();
        assertThat(folder.unreadItems()).isZero();

        assertThat(label.type()).as("Label and Folder types are distinct")
            .isNotEqualTo(folder.type());
    }
}
