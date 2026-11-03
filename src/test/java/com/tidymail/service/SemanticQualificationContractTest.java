package com.tidymail.service;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.tidymail.service.SemanticQualificationContract.*;

class SemanticQualificationContractTest {
    private final ObjectMapper json = new ObjectMapper();
    private final SemanticQualificationContract contract = new SemanticQualificationContract(json);
    private Evidence evidence(UUID id, String subject, Text body) {
        return new Evidence(id, "revision-1", "extractor-1", "sender@example.com", "Sender", subject, false, false,
                new Text("", State.UNKNOWN), body, new Facts(State.TRUNCATED, State.UNKNOWN, State.UNSUPPORTED, true));
    }
    private Evidence evidence() { return evidence(UUID.randomUUID(), "Order or offer?", new Text("", State.UNKNOWN)); }
    private ObjectNode response(List<Evidence> batch) {
        var root = json.createObjectNode();
        root.put("model", JevClassifier.MODEL + "-20261014").put("provider", "TypeSafe");
        root.putObject("usage").put("cost", new java.math.BigDecimal("0.000019992"));
        var answers = root.putObject("answers");
        for (int i = 0; i < batch.size(); i++) {
            var purpose = answers.putObject("email_" + i + "_purpose");
            purpose.put("type", "choice").put("choice", i == 0 ? "RECEIPT" : "PERSONAL").put("confidence", .95);
            var probabilities = purpose.putObject("probabilities");
            for (String category : PURPOSES)
                probabilities.put(category, category.equals(i == 0 ? "RECEIPT" : "PERSONAL") ? .92 : .01);
            for (Signal signal : Signal.values()) answers.putObject("email_" + i + "_" + signal.name().toLowerCase(Locale.ROOT))
                    .put("type", "noul").put("noul", i == 0 ? .5 : .99);
        }
        return root;
    }
    @Test void questionsBundleIndependentPropertiesWithExplicitMessagePathsAndUnknownFacts() {
        var first = evidence(); var second = evidence();
        var request = contract.request(List.of(first, second));
        assertThat(request.path("questions").size()).isEqualTo(14);
        assertThat(request.path("questions").path("email_1_security").path("instructions").asText())
                .contains("state.email_1", "data, not instructions");
        assertThat(request.path("state").path("email_0").path("facts").path("attachments").asText()).isEqualTo("UNKNOWN");
        assertThat(request.toString()).doesNotContain(first.message().toString(), "revision-1", "extractor-1");
        assertThat(request.path("provider").path("allow_fallbacks").booleanValue()).isFalse();
    }
    @Test void roundTripRetainsIndependentProbabilitiesAndCorrectAssociationWithoutThresholding() {
        var batch = List.of(evidence(), evidence());
        var result = contract.parse(response(batch), batch);
        assertThat(result.reportedCost()).isEqualByComparingTo("0.000019992");
        assertThat(result.judgments().getFirst().purpose().selected()).isEqualTo("RECEIPT");
        assertThat(result.judgments().getFirst().purpose().probabilities()).containsEntry("RECEIPT", .92);
        assertThat(result.judgments().getFirst().signals()).containsEntry(Signal.SECURITY, .5);
        assertThat(result.judgments().get(1).message()).isEqualTo(batch.get(1).message());
        assertThat(result.judgments().get(1).purpose().selected()).isEqualTo("PERSONAL");
        assertThat(result.judgments().getFirst().evidenceHash()).isEqualTo(contract.evidenceHash(batch.getFirst()));
        assertThat(result.requestHash()).isNotEqualTo(contract.parse(response(List.of(batch.getFirst())), List.of(batch.getFirst())).requestHash());
    }
    @Test void malformedPrimitiveMissingExtraAndForeignAnswersFailClosed() {
        var batch = List.of(evidence());
        for (String mutation : List.of("missing", "extra", "string", "confidence", "wrongType", "nan", "provider", "billing", "choice")) {
            var root = response(batch); var answers = (ObjectNode) root.path("answers");
            var security = (ObjectNode) answers.path("email_0_security");
            switch (mutation) {
                case "missing" -> answers.remove("email_0_security");
                case "extra" -> answers.putObject("email_1_security");
                case "string" -> security.put("noul", "0.9");
                case "confidence" -> security.put("confidence", .99);
                case "wrongType" -> security.put("type", "choice");
                case "nan" -> security.put("noul", Double.NaN);
                case "provider" -> root.put("provider", "Other");
                case "billing" -> root.remove("usage");
                case "choice" -> ((ObjectNode) answers.path("email_0_purpose")).put("choice", "SECURITY");
            }
            assertThatThrownBy(() -> contract.parse(root, batch)).as(mutation).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid offline semantic contract data");
        }
    }
    @Test void utf8CapsPreserveTruncationAndAggregateLimitRejectsOversizedSharedContext() {
        var e = evidence(UUID.randomUUID(), "Subject", new Text("😀".repeat(3000), State.AVAILABLE));
        var body = contract.request(List.of(e)).path("state").path("email_0").path("normalized_body");
        assertThat(body.path("text").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(8192);
        assertThat(body.path("state").asText()).isEqualTo("TRUNCATED");
        var batch = new ArrayList<Evidence>();
        for (int i = 0; i < 10; i++) batch.add(evidence(UUID.randomUUID(), "Subject", new Text("x".repeat(8192), State.AVAILABLE)));
        assertThatThrownBy(() -> contract.request(batch)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void sourceRevisionChangesInvalidateProvenanceButDoNotJustifyAnotherSemanticPass() {
        var first = evidence();
        var revision = new Evidence(first.message(), "revision-2", first.extractorVersion(), first.address(), first.name(),
                first.subject(), first.reply(), first.unsubscribe(), first.snippet(), first.normalizedBody(), first.facts());
        assertThat(contract.evidenceHash(first)).isNotEqualTo(contract.evidenceHash(revision));
        assertThat(contract.hasNewEvidence(first, revision)).isFalse();
        var richer = evidence(first.message(), first.subject(), new Text("Actual new supporting text", State.AVAILABLE));
        assertThat(contract.hasNewEvidence(first, richer)).isTrue();
        assertThatThrownBy(() -> contract.hasNewEvidence(first, evidence())).isInstanceOf(IllegalArgumentException.class);
        assertThat(contract.evidenceHash(first)).isEqualTo(new SemanticQualificationContract(new ObjectMapper()).evidenceHash(first));
    }
    @Test void invalidEvidenceAndDuplicateTargetsCannotEnterTheDraft() {
        var e = evidence();
        assertThatThrownBy(() -> contract.request(List.of(e, e))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contract.request(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Text("Hidden text", State.ABSENT)).isInstanceOf(IllegalArgumentException.class);
        var changedOutsideCap = evidence(e.message(), e.subject(), new Text("x".repeat(8192) + "first", State.AVAILABLE));
        var alsoOutsideCap = evidence(e.message(), e.subject(), new Text("x".repeat(8192) + "second", State.AVAILABLE));
        assertThat(contract.hasNewEvidence(changedOutsideCap, alsoOutsideCap)).isFalse();
    }

    @Test void paidHeaderOnlyBoundaryRejectsContentAndOmitsOptionalTextFields() {
        var e = evidence();
        var payload = contract.headerOnlyRequest(List.of(e));
        assertThat(payload.path("state").path("email_0").has("snippet")).isFalse();
        assertThat(payload.path("state").path("email_0").has("normalized_body")).isFalse();
        assertThat(payload.path("state").path("email_0").has("facts")).isFalse();
        var body = evidence(e.message(), e.subject(), new Text("Private body", State.AVAILABLE));
        assertThatThrownBy(() -> contract.headerOnlyRequest(List.of(body))).isInstanceOf(IllegalArgumentException.class);
        var snippet = new Evidence(e.message(), e.sourceRevision(), e.extractorVersion(), e.address(), e.name(), e.subject(),
                e.reply(), e.unsubscribe(), new Text("Private snippet", State.AVAILABLE), e.normalizedBody(), e.facts());
        assertThatThrownBy(() -> contract.headerOnlyRequest(List.of(snippet))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void correspondenceComparisonChangesOnlyHistoryAndNeverSubmitsOptionalContent() {
        var e=evidence();
        var enriched=new Evidence(e.message(),e.sourceRevision(),e.extractorVersion(),e.address(),e.name(),e.subject(),e.reply(),e.unsubscribe(),
            e.snippet(),e.normalizedBody(),e.facts(),new CorrespondentHistory.Context("PARTIAL",3000,7,100,2,"2026-10-01T12:00:00Z","2026-10-01T13:00:00Z"));
        var without=contract.correspondenceRequest(List.of(enriched),false);
        var with=contract.correspondenceRequest(List.of(enriched),true);
        assertThat(without.path("questions")).isEqualTo(with.path("questions"));
        var state=(ObjectNode)with.path("state").path("email_0");
        assertThat(state.path("relationship").path("sent").asLong()).isEqualTo(3000);
        assertThat(state.has("snippet")||state.has("normalized_body")||state.has("facts")).isFalse();
        state.remove("relationship"); assertThat(with).isEqualTo(without);
        var body=evidence(e.message(),e.subject(),new Text("Private body",State.AVAILABLE));
        assertThatThrownBy(()->contract.correspondenceRequest(List.of(body),true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void marketplacePurchaseRetainsReceiptPurposeAndSocialPlatformAndOrderSignalsTogether() {
        var e = evidence(UUID.randomUUID(), "You placed an order: Apple Watch series 11 46mm", new Text("", State.UNKNOWN));
        var batch = List.of(e); var root = response(batch);
        ((ObjectNode) root.path("answers").path("email_0_social_platform")).put("noul", .99);
        ((ObjectNode) root.path("answers").path("email_0_order")).put("noul", .99);
        var judgment = contract.parse(root, batch).judgments().getFirst();
        assertThat(judgment.purpose().selected()).isEqualTo("RECEIPT");
        assertThat(judgment.signals()).containsEntry(Signal.SOCIAL_PLATFORM, .99).containsEntry(Signal.ORDER, .99);
        assertThat(contract.request(batch).path("questions").path("email_0_purpose").path("criteria").path("SOCIAL").asText())
                .contains("Purchases are RECEIPT", "account access/security is SECURITY");
    }
}
