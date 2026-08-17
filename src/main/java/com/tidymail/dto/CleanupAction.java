package com.tidymail.dto;

import com.tidymail.dto.SelectionRequest.PreviewAction;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The cleanup actions available on the Senders/Unread pages (archive, delete,
 * mark-read), with their display labels and the preview action they map to.
 *
 * <p>Product functionality supported: the <em>Bulk Actions</em> feature's
 * action selection on the Senders and Unread pages.</p>
 */
public enum CleanupAction {
    ARCHIVE("Archive", "Bulk archive", PreviewAction.ARCHIVE),
    DELETE("Delete", "Bulk delete", PreviewAction.TRASH),
    MARK_READ("Mark as read", "Mark as read", PreviewAction.MARK_READ);

    private final String buttonLabel;
    private final String settingLabel;
    private final PreviewAction previewAction;

    CleanupAction(
            String buttonLabel, String settingLabel, PreviewAction previewAction) {
        this.buttonLabel = buttonLabel;
        this.settingLabel = settingLabel;
        this.previewAction = previewAction;
    }

    public String buttonLabel() {
        return buttonLabel;
    }

    public String settingLabel() {
        return settingLabel;
    }

    public PreviewAction previewAction() {
        return previewAction;
    }

    public String parameterValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses a cleanup action from its URL parameter value, defaulting when
     * absent and rejecting unknown values.
     */
    public static CleanupAction parse(String value, CleanupAction defaultValue) {
        if (value == null) return defaultValue;
        for (CleanupAction action : values()) {
            if (action.parameterValue().equals(value)) return action;
        }
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Invalid cleanupAction query parameter");
    }
}
