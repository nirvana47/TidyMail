package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;

import com.tidymail.entity.MessageMetadata;
import com.tidymail.mail.MailMessage;
import com.tidymail.service.MessageMetadataMapper;
import com.tidymail.service.ProviderDeepLinkService;
import com.tidymail.unsubscribe.Rfc2369Parser;
import com.tidymail.unsubscribe.Rfc8058Eligibility;
import com.tidymail.unsubscribe.UnsubscribeCandidateKey;
import com.tidymail.unsubscribe.UnsubscribeUriPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Phase11UnsubscribeParserTest {
    private final Rfc2369Parser parser = new Rfc2369Parser();
    private final UnsubscribeUriPolicy uriPolicy = new UnsubscribeUriPolicy();
    private final Rfc8058Eligibility eligibility = new Rfc8058Eligibility(uriPolicy);

    @Test
    void rfc2369PreservesPreferenceCommentsAndFoldingButStopsAfterMalformedItem() {
        Rfc2369Parser.ParseResult valid = parser.parse(List.of(
            "(preferred) <https://news.example/unsub?token=secret>,\r\n\t"
                + "<mailto:leave@example.com?subject=remove> (fallback)"));
        assertThat(valid.malformed()).isFalse();
        assertThat(valid.items()).extracting(Rfc2369Parser.UriItem::value)
            .containsExactly(
                "https://news.example/unsub?token=secret",
                "mailto:leave@example.com?subject=remove");

        Rfc2369Parser.ParseResult malformed = parser.parse(List.of(
            "<https://first.example/u>, bare.example/u, <https://never.example/u>"));
        assertThat(malformed.malformed()).isTrue();
        assertThat(malformed.reason()).isEqualTo(Rfc2369Parser.Reason.EXPECTED_ANGLE);
        assertThat(malformed.items()).extracting(Rfc2369Parser.UriItem::value)
            .containsExactly("https://first.example/u");

        assertThat(parser.parse(List.of("https://bare.example/u")).malformed()).isTrue();
        assertThat(parser.parse(List.of("<https://example.com/u>\r\nInjected")).malformed())
            .isTrue();
        assertThat(parser.parse(List.of("(unclosed <https://example.com/u>")).malformed())
            .isTrue();
        assertThat(parser.parse(List.of("<https://example.com/u")).malformed()).isTrue();
        assertThat(parser.parse(java.util.Collections.nCopies(
            Rfc2369Parser.MAX_FIELD_VALUES + 1, "")).reason())
            .isEqualTo(Rfc2369Parser.Reason.TOO_MANY_FIELDS);
    }

    @Test
    void uriPolicyCanonicalizesDnsAndExposesOnlyRedactedFacts() {
        UnsubscribeUriPolicy.Result valid =
            uriPolicy.validate("https://BÜCHER.example:443/private/token-value?secret=canary");
        assertThat(valid.valid()).isTrue();
        assertThat(valid.target().host()).isEqualTo("xn--bcher-kva.example");
        assertThat(valid.target().redactedPathShape()).isEqualTo("/... (2 segments)");
        assertThat(valid.target().queryPresent()).isTrue();
        assertThat(valid.target().fingerprint()).matches("[0-9a-f]{64}");
        assertThat(valid.target().originalUri().toString()).contains("secret=canary");
        assertThat(valid.target().toString())
            .doesNotContain("token-value", "secret=canary");
        assertThat(List.of(
            valid.target().host(),
            valid.target().redactedPathShape(),
            valid.target().fingerprint()).toString())
            .doesNotContain("token-value", "secret=canary");

        for (String rejected : List.of(
                "http://example.com/u",
                "ftp://example.com/u",
                "gopher://example.com/u",
                "data:text/plain,unsubscribe",
                "https://user@example.com/u",
                "https://example.com:444/u",
                "https://example.com./u",
                "https://example.com/u#fragment",
                "https://127.0.0.1/u",
                "https://127.1/u",
                "https://017700000001/u",
                "https://[::1]/u",
                "https://2130706433/u",
                "https://0x7f000001/u",
                "https://%31%32%37.0.0.1/u",
                "https://example.com\\@evil.example/u",
                "https://example.com/%zz",
                "https://example.com/u%0d%0aInjected",
                "https://example.com/u%5c..%5cadmin",
                "https://example.com/u%C0%AFadmin",
                "https://-invalid.example/u",
                "https://xn--a.example/u",
                "//example.com/u",
                "/relative/unsubscribe",
                ":// malformed",
                "file:///etc/passwd")) {
            assertThat(uriPolicy.validate(rejected).valid()).as(rejected).isFalse();
        }
        assertThat(uriPolicy.validate(
            "https://" + "a".repeat(64) + ".example/u").valid()).isFalse();
        assertThat(uriPolicy.validate(
            "https://example.com/" + "p".repeat(2049)).valid()).isFalse();
        assertThat(uriPolicy.validate(
            "https://example.com/u?" + "q".repeat(2049)).valid()).isFalse();

        assertThat(uriPolicy.validate(
            "mailto:leave@example.com?subject=Remove&body=Please%20remove").valid()).isTrue();
        assertThat(uriPolicy.validate(
            "mailto:leave@example.com?subject=Gr%C3%BC%C3%9Fe").valid()).isTrue();
        assertThat(uriPolicy.validate("mailto:a@example.com,b@example.com").valid()).isFalse();
        assertThat(uriPolicy.validate("mailto:a@example.com?cc=other@example.com").valid())
            .isFalse();
        for (String rejected : List.of(
                "mailto:user:password@example.com",
                "mailto:.leave@example.com",
                "mailto:leave..now@example.com",
                "mailto:leave@localhost",
                "mailto:" + "a".repeat(65) + "@example.com")) {
            assertThat(uriPolicy.validate(rejected).valid()).as(rejected).isFalse();
        }
    }

    @Test
    void gmailDeepLinksBoundAccountIdentityAndKeepOpaqueSourceIdsEncoded() {
        ProviderDeepLinkService deepLinks = new ProviderDeepLinkService();
        assertThat(deepLinks.gmail(
            "google", "owner+tag@example.com", "thread ?#%", "fallback")).hasValueSatisfying(
                link -> assertThat(link)
                    .startsWith("https://mail.google.com/mail/u/")
                    .contains("#all/thread%20%3F%23%25"));
        assertThat(deepLinks.gmail(
            "google", "a".repeat(65) + "@example.com", "thread", "fallback")).isEmpty();
        assertThat(deepLinks.gmail(
            "google", "a@" + "d".repeat(254), "thread", "fallback")).isEmpty();
    }

    @Test
    void rfc8058MatrixFailsClosedAndKeepsCanonicalOutboundBytes() {
        Rfc2369Parser.ParseResult https =
            parser.parse(List.of("<https://news.example/unsubscribe?id=private>"));
        List<String> exact = List.of(" \r\n\tList-Unsubscribe=One-Click");

        assertThat(eligibility.classify(https, exact, true, true, true).eligible()).isTrue();
        assertThat(eligibility.classify(https, exact, true, true, true).outboundBody())
            .isEqualTo("List-Unsubscribe=One-Click".getBytes(StandardCharsets.US_ASCII));
        assertThat(eligibility.classify(
            parser.parse(List.of("<http://news.example/u>")), exact, true, true, true).eligible())
            .isFalse();
        assertThat(eligibility.classify(https, List.of(), true, true, true).eligible()).isFalse();
        assertThat(eligibility.classify(
            https, List.of("List-Unsubscribe=One-Click", "List-Unsubscribe=One-Click"),
            true, true, true).eligible()).isFalse();
        assertThat(eligibility.classify(
            https, List.of("List-Unsubscribe=One-Click&extra=true"),
            true, true, true).eligible()).isFalse();
        assertThat(eligibility.classify(
            https, List.of(" \tList-Unsubscribe=One-Click\t "),
            true, true, true).eligible()).isTrue();
        assertThat(eligibility.classify(
            https, List.of("list-unsubscribe=One-Click"),
            true, true, true).eligible()).isTrue();
        assertThat(eligibility.classify(
            https, List.of("List-Unsubscribe=one-click"),
            true, true, true).eligible()).isTrue();
        assertThat(eligibility.classify(
            https, List.of("lIsT-uNsUbScRiBe=OnE-cLiCk"),
            true, true, true).outboundBody())
            .isEqualTo("List-Unsubscribe=One-Click".getBytes(StandardCharsets.US_ASCII));
        assertThat(eligibility.classify(https, exact, false, true, true).eligible()).isFalse();
        assertThat(eligibility.classify(https, exact, true, false, true).eligible()).isFalse();
        assertThat(eligibility.classify(https, exact, true, true, false).eligible()).isFalse();
        assertThat(eligibility.classify(
            https, List.of("List-Unsubscribe=One-Cl\u0131ck"), true, true, true).eligible())
            .isFalse();
    }

    @Test
    void mapperCorrelatesOneTrustedPassingSignatureAndDiscardsRawEvidence() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("From", List.of("News <news@example.com>"));
        headers.put("List-Unsubscribe", List.of("<https://news.example/u?token=AUTH_CANARY>"));
        headers.put("List-Unsubscribe-Post", List.of("List-Unsubscribe=One-Click"));
        headers.put("Authentication-Results", List.of(
            "mx.google.com; dkim=pass header.d=example.com header.s=selector1"));
        headers.put("DKIM-Signature", List.of(
            "v=1; d=example.com; s=selector1; "
                + "h=From:List-Unsubscribe:List-Unsubscribe-Post; b=SIGNATURE_CANARY"));
        MessageMetadata mapped = new MessageMetadataMapper().apply(
            7L,
            new MailMessage("id", "thread", "google", Instant.now(), headers, Set.of()),
            null,
            Instant.now());

        assertThat(mapped.getUnsubscribeDkimPass()).isTrue();
        assertThat(mapped.getUnsubscribeDkimCoversListUnsubscribe()).isTrue();
        assertThat(mapped.getUnsubscribeDkimCoversListUnsubscribePost()).isTrue();
        assertThat(mapped.getUnsubscribeAuthReason()).isEqualTo("TRUSTED_PASS");
        assertThat(MessageMetadata.class.getDeclaredFields())
            .noneMatch(field -> field.getName().toLowerCase().contains("signature")
                || field.getName().toLowerCase().contains("authenticationresults"));

        headers.put("Authentication-Results", List.of(
            "sender.example; dkim=pass header.d=example.com header.s=selector1"));
        mapped = new MessageMetadataMapper().apply(
            7L,
            new MailMessage("id", "thread", "google", Instant.now(), headers, Set.of()),
            null,
            Instant.now());
        assertThat(mapped.getUnsubscribeDkimPass()).isFalse();
        assertThat(mapped.getUnsubscribeAuthReason()).isEqualTo("NO_TRUSTED_RESULT");

        headers.put("Authentication-Results", List.of(
            "mx.google.com; dkim=pass header.d=example.com header.s=selector1",
            "mx.google.com; dkim=pass header.d=example.com header.s=selector2"));
        mapped = new MessageMetadataMapper().apply(
            7L,
            new MailMessage("id", "thread", "google", Instant.now(), headers, Set.of()),
            null,
            Instant.now());
        assertThat(mapped.getUnsubscribeDkimPass()).isFalse();
        assertThat(mapped.getUnsubscribeAuthReason()).isEqualTo("AMBIGUOUS_RESULT");

        headers.put("Authentication-Results", List.of(
            "mx.google.com; dkim=pass; spf=pass header.d=example.com header.s=selector1"));
        mapped = new MessageMetadataMapper().apply(
            7L,
            new MailMessage("id", "thread", "google", Instant.now(), headers, Set.of()),
            null,
            Instant.now());
        assertThat(mapped.getUnsubscribeDkimPass()).isFalse();
        assertThat(mapped.getUnsubscribeAuthReason()).isEqualTo("AMBIGUOUS_RESULT");

        headers.put("Authentication-Results", List.of(
            "mx.google.com; dkim=pass header.d=example.com header.s=selector1"));
        headers.put("DKIM-Signature", List.of(
            "v=1; d=example.com; d=attacker.example; s=selector1; "
                + "h=From:List-Unsubscribe:List-Unsubscribe-Post; b=SIGNATURE_CANARY"));
        mapped = new MessageMetadataMapper().apply(
            7L,
            new MailMessage("id", "thread", "google", Instant.now(), headers, Set.of()),
            null,
            Instant.now());
        assertThat(mapped.getUnsubscribeDkimPass()).isFalse();
        assertThat(mapped.getUnsubscribeAuthReason()).isEqualTo("AMBIGUOUS_RESULT");
    }

    @Test
    void candidateKeyIsVersionedCanonicalOpaqueAndRejectsAliases() {
        String key = UnsubscribeCandidateKey.encode(
            12, 34, "sender@example.com", "list.example");
        assertThat(key).startsWith("c1.").hasSize(46);
        assertThat(UnsubscribeCandidateKey.isCanonical(key)).isTrue();
        assertThat(Base64.getUrlDecoder().decode(key.substring(3))).hasSize(32);
        assertThat(key).doesNotContain("sender", "example", "12", "34");
        assertThat(UnsubscribeCandidateKey.encode(
            12, 34, "sender@example.com", "list.example")).isEqualTo(key);
        assertThat(UnsubscribeCandidateKey.encode(
            12, 35, "sender@example.com", "list.example")).isNotEqualTo(key);
        assertThat(UnsubscribeCandidateKey.isCanonical(key + "=")).isFalse();
        assertThat(UnsubscribeCandidateKey.isCanonical("c2." + key.substring(3))).isFalse();
    }
}
