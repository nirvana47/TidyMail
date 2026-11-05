package com.tidymail.service;

import static com.tidymail.service.MessageRecommendation.*;
import static com.tidymail.service.RecommendationSemanticContract.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;

/** Reuses paid responses without network, credentials or database access. Original experiment is immutable. */
public final class ReplayRecommendationDecisions {
    public static void main(String[] args) throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("Paid directory, collected samples, local observations and new output required");
        Path paid=Path.of(args[0]),samplePath=Path.of(args[1]),observed=Path.of(args[2]),output=Path.of(args[3]);
        if(Files.exists(output))throw new IllegalArgumentException("Replay output already exists");
        var json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var samples=json.readTree(samplePath.toFile());var facts=json.readTree(observed.toFile());var contract=new RecommendationSemanticContract(json);
        var qualifier=new RecommendationDecisionQualification(json);var engine=new MessageRecommendation();var rows=json.createArrayNode();
        var counts=new TreeMap<String,Map<String,Integer>>();
        var ledger=json.readTree(paid.resolve("ledger.json").toFile());
        if(!ledger.path("status").asText().equals("COMPLETE"))throw new IllegalStateException("Complete original run required");
        for(var attempt:ledger.path("attempts")) {
            JsonNode sample=null;for(var s:samples)if(s.path("review_id").asText().equals(attempt.path("review_id").asText()))sample=s;
            if(sample==null || sample.path("metadata").path("account_id").asLong()!=facts.path("account_id").asLong())throw new IllegalStateException("Owned replay source missing");
            var evidence=json.treeToValue(sample.path("evidence"),Evidence.class);var variant=Variant.valueOf(attempt.path("variant").asText());
            var judgment=contract.parse(json.readTree(paid.resolve("response-"+attempt.path("sequence").asInt()+".json").toFile()),List.of(evidence),variant).judgments().getFirst();
            var original=json.readTree(paid.resolve("result-"+attempt.path("sequence").asInt()+".json").toFile());
            if(!judgment.evidenceHash().equals(original.path("judgment").path("evidenceHash").asText()))throw new IllegalStateException("Paid evidence changed");
            var local=RecommendationSnippetPilot.local(sample);
            var replay=qualifier.diagnose(evidence,variant,judgment,local,Policy.initial(),Thresholds.diagnostic());
            long sent=facts.path("sent_to_exact_address").path(evidence.address()).asLong();
            Result withLocal=replay.recommendation();
            if(sent>0) {
                // This is an independent local retention decision. Do not relabel the model's evidence or purpose.
                String revision=CalendarEvidenceParser.hash(local.evidenceRevision()+CalendarEvidenceParser.hash(Files.readString(observed)));
                var unresolved=new Assessment(local.assessment().purpose(),local.assessment().importance(),local.assessment().facets(),Signal.UNKNOWN,Signal.UNKNOWN,Qualification.UNRESOLVED,true);
                withLocal=engine.evaluate(new Input(local.message(),revision,local.mailbox(),local.sizeEstimate(),unresolved,local.manualDecision(),
                    local.readState(),local.receivedAt(),local.evaluatedAt(),new RetentionEvidence(sent,false)),Policy.initial());
            }
            var row=rows.addObject();row.put("review_id",sample.path("review_id").asText()).put("variant",variant.name()).put("observed_sent",sent);
            row.set("original_diagnostic",original.path("diagnostic"));row.set("decision_specific",json.valueToTree(replay));row.set("with_local_observation",json.valueToTree(withLocal));
            counts.computeIfAbsent(variant.name()+"_same_paid_inputs",k->new TreeMap<>()).merge(replay.recommendation().action().name(),1,Integer::sum);
            counts.computeIfAbsent(variant.name()+"_plus_local_observations",k->new TreeMap<>()).merge(withLocal.action().name(),1,Integer::sum);
        }
        var summary=json.createObjectNode();summary.put("qualification_version",RecommendationDecisionQualification.VERSION).put("engine_version",MessageRecommendation.VERSION)
            .put("paid_run_unchanged",true).put("new_model_calls",0).put("new_gmail_calls",0).put("production_adoption",false)
            .put("local_observations_sha256",CalendarEvidenceParser.hash(Files.readString(observed)))
            .put("qualification_note","Same diagnostic cutoffs and paid answers; revised decision policy, not an independent accuracy test. Local observations are a separate input, not a snippet/model improvement.");
        summary.set("counts",json.valueToTree(counts));summary.set("cases",rows);
        Files.createDirectories(output.getParent());json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),summary);
        System.out.println(json.writeValueAsString(counts));
    }
}
