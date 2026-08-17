package com.tidymail.unsubscribe;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public record ValidatedUnsubscribeRequest(
        URI uri,
        ResolvedDestination destination,
        Method method,
        byte[] body) {

    private static final byte[] ONE_CLICK_BODY =
        "List-Unsubscribe=One-Click".getBytes(StandardCharsets.US_ASCII);

    public ValidatedUnsubscribeRequest {
        UnsubscribeUriPolicy.Result validated = uri == null
            ? UnsubscribeUriPolicy.Result.invalid(UnsubscribeUriPolicy.Reason.EMPTY)
            : new UnsubscribeUriPolicy().validate(uri.toASCIIString());
        if (destination == null || method == null || !validated.valid()
                || validated.target().kind() != UnsubscribeUriPolicy.TargetKind.HTTPS
                || !validated.target().host().equals(destination.asciiHostname())) {
            throw new IllegalArgumentException("Request is not a validated HTTPS destination");
        }
        body = body == null ? new byte[0] : body.clone();
        if (method == Method.RFC8058_POST && !Arrays.equals(body, ONE_CLICK_BODY)) {
            throw new IllegalArgumentException("RFC 8058 request body is not canonical");
        }
        if (method == Method.LEGACY_HTTPS_GET && body.length != 0) {
            throw new IllegalArgumentException("Legacy GET cannot have a body");
        }
    }

    public static ValidatedUnsubscribeRequest oneClickPost(
            URI uri, ResolvedDestination destination) {
        return new ValidatedUnsubscribeRequest(
            uri, destination, Method.RFC8058_POST, ONE_CLICK_BODY);
    }

    public static ValidatedUnsubscribeRequest legacyGet(
            URI uri, ResolvedDestination destination) {
        return new ValidatedUnsubscribeRequest(
            uri, destination, Method.LEGACY_HTTPS_GET, new byte[0]);
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    @Override
    public String toString() {
        return "ValidatedUnsubscribeRequest[method=" + method
            + ", host=" + destination.asciiHostname() + ", uri=<redacted>]";
    }

    public enum Method {
        RFC8058_POST,
        LEGACY_HTTPS_GET
    }
}
