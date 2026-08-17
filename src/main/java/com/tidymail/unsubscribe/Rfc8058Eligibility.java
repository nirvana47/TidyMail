package com.tidymail.unsubscribe;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class Rfc8058Eligibility {
    public static final byte[] CANONICAL_BODY =
        "List-Unsubscribe=One-Click".getBytes(StandardCharsets.US_ASCII);

    private final UnsubscribeUriPolicy uriPolicy;

    public Rfc8058Eligibility() {
        this(new UnsubscribeUriPolicy());
    }

    public Rfc8058Eligibility(UnsubscribeUriPolicy uriPolicy) {
        this.uriPolicy = uriPolicy;
    }

    public Result classify(
            Rfc2369Parser.ParseResult listUnsubscribe,
            List<String> listUnsubscribePostValues,
            boolean trustedDkimPass,
            boolean coversListUnsubscribe,
            boolean coversListUnsubscribePost) {
        if (listUnsubscribe == null || listUnsubscribe.malformed()) {
            return Result.ineligible(Reason.MALFORMED_LIST_UNSUBSCRIBE);
        }
        List<UnsubscribeUriPolicy.ValidatedTarget> https = new ArrayList<>();
        for (Rfc2369Parser.UriItem item : listUnsubscribe.items()) {
            UnsubscribeUriPolicy.Result target = uriPolicy.validate(item.value());
            if (target.valid()
                    && target.target().kind() == UnsubscribeUriPolicy.TargetKind.HTTPS) {
                https.add(target.target());
            }
        }
        if (https.size() != 1) {
            return Result.ineligible(Reason.REQUIRES_ONE_HTTPS_URI);
        }
        if (listUnsubscribePostValues == null || listUnsubscribePostValues.size() != 1) {
            return Result.ineligible(Reason.REQUIRES_ONE_POST_FIELD);
        }
        String postValue = unfold(listUnsubscribePostValues.getFirst());
        if (postValue == null
                || !stripOuterWhitespace(postValue)
                    .equalsIgnoreCase("List-Unsubscribe=One-Click")) {
            return Result.ineligible(Reason.INVALID_POST_ARGUMENT);
        }
        if (!trustedDkimPass) {
            return Result.ineligible(Reason.DKIM_NOT_TRUSTED);
        }
        if (!coversListUnsubscribe || !coversListUnsubscribePost) {
            return Result.ineligible(Reason.DKIM_COVERAGE_MISSING);
        }
        return new Result(true, Reason.ELIGIBLE, https.getFirst(), CANONICAL_BODY.clone());
    }

    private static String unfold(String raw) {
        if (raw == null || raw.length() > 1024) return null;
        StringBuilder result = new StringBuilder(raw.length());
        for (int index = 0; index < raw.length(); index++) {
            char character = raw.charAt(index);
            if (character > 0x7f) return null;
            if (character == '\r' || character == '\n') {
                int end = index;
                if (character == '\r' && end + 1 < raw.length()
                        && raw.charAt(end + 1) == '\n') {
                    end++;
                }
                if (end + 1 >= raw.length()
                        || raw.charAt(end + 1) != ' '
                            && raw.charAt(end + 1) != '\t') {
                    return null;
                }
                result.append(' ');
                index = end;
                while (index + 1 < raw.length()
                        && (raw.charAt(index + 1) == ' '
                            || raw.charAt(index + 1) == '\t')) {
                    index++;
                }
            } else if (Character.isISOControl(character)
                    && character != ' ' && character != '\t') {
                return null;
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }

    private static String stripOuterWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end
                && (value.charAt(start) == ' ' || value.charAt(start) == '\t')) {
            start++;
        }
        while (end > start
                && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\t')) {
            end--;
        }
        return value.substring(start, end);
    }

    public enum Reason {
        ELIGIBLE,
        MALFORMED_LIST_UNSUBSCRIBE,
        REQUIRES_ONE_HTTPS_URI,
        REQUIRES_ONE_POST_FIELD,
        INVALID_POST_ARGUMENT,
        DKIM_NOT_TRUSTED,
        DKIM_COVERAGE_MISSING
    }

    public record Result(
            boolean eligible,
            Reason reason,
            UnsubscribeUriPolicy.ValidatedTarget target,
            byte[] outboundBody) {
        public Result {
            outboundBody = outboundBody == null ? null : outboundBody.clone();
        }

        public static Result ineligible(Reason reason) {
            return new Result(false, reason, null, null);
        }

        @Override
        public byte[] outboundBody() {
            return outboundBody == null ? null : outboundBody.clone();
        }
    }
}
