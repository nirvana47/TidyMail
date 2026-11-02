package com.tidymail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tidymail.service.ReviewedPatternService;
import com.tidymail.support.PostgresJourneyTest;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Explicit-only, private local pilot. Production services run only against the test container. */
class Phase2ReviewedReferenceFixture extends PostgresJourneyTest {
    @Autowired ObjectMapper json;
    @Autowired ReviewedPatternService patterns;

    @Test void replayFrozenReviewedHeadersWithoutChangingRetainedMailbox() throws Exception {
        Path directory=Path.of(Objects.requireNonNull(System.getProperty("phase2.reference.directory"),"Set the private reference directory explicitly"));
        var reference=json.readTree(directory.resolve("phase2-private-pattern-review.json").toFile());
        assertThat(reference.path("status").asText()).isEqualTo("USER_REVIEWED");
        var labels=new HashMap<String,JsonNode>();
        for(var row:reference.path("rows")) {
            assertThat(row.path("reviewed_category").asText()).isNotBlank();
            labels.put(key(row),row);
        }
        var rows=new LinkedHashMap<String,JsonNode>();
        for(String line:Files.readAllLines(directory.resolve("phase2-private-pattern-neighbors.jsonl"))) {
            var row=json.readTree(line);rows.putIfAbsent(key(row),row);
        }
        assertThat(rows).hasSizeLessThanOrEqualTo(60);
        assertThat(rows.keySet()).containsAll(labels.keySet());
        var reviewedTargets=new HashMap<String,JsonNode>();
        Path exceptionFile=directory.resolve("../2026-11-02/phase2-private-exception-review.json");
        JsonNode exceptionReview=Files.exists(exceptionFile) ? json.readTree(exceptionFile.toFile()) : json.createObjectNode();
        var exceptionDecisions=new ArrayList<Object>();
        if(exceptionReview.path("status").asText().equals("USER_REVIEWED")) for(var row:exceptionReview.path("rows")) {
            assertThat(row.hasNonNull("correspondence_context")).isTrue();
            var decision=ReviewedPatternService.assess(new ReviewedPatternService.Mail(UUID.randomUUID(),nullable(row,"address"),
                row.path("dashboard_unit_kind").asText()+":"+row.path("dashboard_unit_key").asText(),nullable(row,"subject_header"),null,
                nullable(row,"classification_kind"),"frozen",false,nullable(row,"reviewed_category"),null,row.path("correspondence_context").asBoolean()));
            if(row.path("security_access").asBoolean() || row.path("reviewed_category").asText().equals("PERSONAL") || row.path("sender_relationship").asText().equals("SELLER_DM"))
                assertThat(decision.reusable()).as(row.path("review_id").asText()).isFalse();
            exceptionDecisions.add(Map.of("reviewId",row.path("review_id").asText(),"referenceCategory",row.path("reviewed_category"),
                "securityAccess",row.path("security_access"),"sampleScope",row.path("sample_role").asText(),"decision",decision));
        }
        var targetReview=json.readTree(directory.resolve("phase2-private-pattern-target-review.json").toFile());
        if(targetReview.path("status").asText().equals("USER_REVIEWED"))
            for(var row:targetReview.path("rows"))reviewedTargets.put(key(row),row);
        var accounts=new LinkedHashMap<Long,Long>();
        var messages=new HashMap<UUID,JsonNode>();
        for(var row:rows.values()) {
            long account=accounts.computeIfAbsent(row.path("account_id").asLong(),id->userAccounts.createAccount("pilot-"+id+"@example.test"));
            assertThat(row.hasNonNull("correspondence_context")).isTrue();
            var reviewed=labels.get(key(row));
            if(reviewed!=null) {
                assertThat(row.path("address").asText()).isEqualTo(reviewed.path("address").asText());
                assertThat(row.path("subject_header").asText()).isEqualTo(reviewed.path("subject_header").asText());
            }
            var id=jdbc.queryForObject("""
                INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,dashboard_from_address,
                from_display_name,subject_header,dashboard_unit_kind,dashboard_unit_key,labels,label_evidence_known,
                received_at,size_estimate,classification_override,classification_kind,in_reply_to_values)
                VALUES(?,'google',?,?,?,?,?,?,?,ARRAY(SELECT jsonb_array_elements_text(CAST(? AS jsonb))),true,?::timestamptz,?,?,?,CASE WHEN ? THEN ARRAY['<local-context@example.test>'] ELSE ARRAY[]::text[] END) RETURNING dashboard_message_id
                """,UUID.class,account,row.path("provider_message_id").asText(),row.path("address").asText(),row.path("address").asText(),
                nullable(row,"from_display_name"),nullable(row,"subject_header"),nullable(row,"dashboard_unit_kind"),nullable(row,"dashboard_unit_key"),json.writeValueAsString(row.path("labels")),
                row.path("received_at").asText(),row.path("size_estimate").isNumber() ? row.path("size_estimate").asLong() : null,
                reviewed==null ? null : reviewed.path("reviewed_category").asText(),nullable(row,"classification_kind"),row.path("correspondence_context").asBoolean());
            messages.put(id,row);
        }
        var runs=new ArrayList<Object>();
        for(var account:accounts.entrySet()) for(int months:List.of(24,12)) {
            var preview=patterns.preview(account.getValue(),months);
            var predictions=new ArrayList<Object>();
            for(var match:preview.matches()) {
                var target=messages.get(match.id());
                var source=messages.get(match.sourceId());
                assertThat(target.path("address").asText()).isEqualTo(source.path("address").asText());
                assertThat(target.path("dashboard_unit_key").asText()).isEqualTo(source.path("dashboard_unit_key").asText());
                assertThat(match.importance()).isEqualTo("NOT_INFERRED");
                var judged=reviewedTargets.get(key(target));
                var prediction=new LinkedHashMap<String,Object>();
                prediction.put("target",target);prediction.put("sourceReviewId",labels.get(key(source)).path("review_id").asText());
                prediction.put("proposedCategory",match.label());prediction.put("template",match.template());
                prediction.put("importanceInferred",false);prediction.put("requiresUserReview",judged==null);
                if(judged!=null) {
                    assertThat(match.label()).isEqualTo(judged.path("reviewed_category").asText());
                    prediction.put("categoryCorrect",true);prediction.put("reviewedImportance",judged.path("importance").asText());
                    prediction.put("importanceDisposition","SEPARATE_PER_MESSAGE_REVIEW_REQUIRED");
                    prediction.put("sourceImportanceNote",labels.get(key(source)).path("review_note").asText());
                }
                predictions.add(prediction);
            }
            runs.add(Map.of("sourceAccount",account.getKey(),"months",months,"limited",preview.limited(),"proposals",predictions));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dashboard_pattern_labels",Long.class)).isZero();
        verifyNoInteractions(gmailProvider,outlookProvider);
        var report=Map.of("createdAt",Instant.now().toString(),"status","NARROW_CATEGORY_CHECK_ONLY","version",ReviewedPatternService.VERSION,
            "reviewedReferences",labels.size(),"uniqueMessages",rows.size(),"runs",runs,"exceptionDecisions",exceptionDecisions,
            "limitations",List.of("Header-only local sample; MIME/snippet/body coverage not qualified","One independently reviewed target cannot establish broad accuracy or coverage",
                "User-confirmed importance differs across the same sender/subject template; never inherit cleanup suitability",
                "Costco/Nextdoor transfer remains disabled","No labels, mail, models or retained database writes"));
        json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("phase2-private-pattern-pilot-results.json").toFile(),report);
    }
    private String key(JsonNode row) {return row.path("account_id").asLong()+":"+row.path("provider_message_id").asText();}
    private String nullable(JsonNode row,String field) {return row.path(field).isTextual() ? row.path(field).asText() : null;}
}
