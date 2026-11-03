package com.tidymail.service;

import java.util.Objects;
import java.util.regex.Pattern;

/** Admission for the experimental header-only lane, not a category or cleanup decision. */
public final class HeaderSemanticAdmission {
    public static final String VERSION = "header-context-v1";
    private static final Pattern CONVERSATION_PREFIX = Pattern.compile("(?iu)^\\s*(?:re|fw|fwd)\\s*:");
    public record Decision(boolean eligible, String reason) {}

    public static Decision inspect(SemanticQualificationContract.Evidence evidence) {
        if (evidence.reply() || CONVERSATION_PREFIX.matcher(Objects.toString(evidence.subject(), "")).find())
            return new Decision(false, "Reply or forwarded content needs correspondence context or review; headers alone cannot resolve the wrapper's purpose or access relevance.");
        return new Decision(true, "Eligible for bounded header-only semantic evaluation; confidence and reference qualification remain separate.");
    }
    private HeaderSemanticAdmission() {}
}
