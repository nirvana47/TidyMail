package com.tidymail.service;

import static com.tidymail.service.MessageRecommendation.*;
import static com.tidymail.service.RecommendationSemanticContract.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Decision-specific offline replay. Keeps the original paid contract/questions/results unchanged. */
public final class RecommendationDecisionQualification {
    public static final String VERSION="decision-specific-retention-v2";
    private final RecommendationSemanticContract contract;
    public RecommendationDecisionQualification(ObjectMapper json) { contract=new RecommendationSemanticContract(json); }

    public Diagnostic diagnose(Evidence evidence,Variant variant,Judgment judgment,Input local,Policy policy,Thresholds t) {
        if(!evidence.message().equals(judgment.message()) || !evidence.message().equals(local.message())
                || !evidence.sourceRevision().equals(local.evidenceRevision())
                || !contract.evidenceHash(evidence,variant).equals(judgment.evidenceHash()))
            throw new IllegalArgumentException("Replay evidence does not match the paid judgment");
        var properties=new EnumMap<Property,Signal>(Property.class);
        for(var property:Property.values()) {
            Double p=judgment.properties().get(property);
            if(p==null || !Double.isFinite(p) || p<0 || p>1)throw new IllegalArgumentException("Invalid replay probability");
            properties.put(property,p>=t.yes()?Signal.PRESENT:p<=t.no()?Signal.ABSENT:Signal.UNKNOWN);
        }
        var choice=judgment.purpose();
        boolean resolved=choice.selected()!=Purpose.UNKNOWN && choice.confidence()>=t.purposeConfidence()
                && choice.probabilities().getOrDefault(choice.selected(),0.)>=t.purposeProbability();
        var facets=EnumSet.noneOf(Facet.class);facets.addAll(local.assessment().facets());
        var mapping=Map.ofEntries(Map.entry(Property.SECURITY,Facet.SECURITY),Map.entry(Property.HUMAN_CORRESPONDENCE,Facet.HUMAN_CORRESPONDENCE),
                Map.entry(Property.HEALTHCARE,Facet.HEALTHCARE),Map.entry(Property.SCHOOL,Facet.SCHOOL),Map.entry(Property.ORDER,Facet.ORDER),
                Map.entry(Property.BILL,Facet.BILL),Map.entry(Property.STATEMENT,Facet.STATEMENT),Map.entry(Property.MARKETING,Facet.MARKETING),
                Map.entry(Property.SHIPPING,Facet.SHIPPING),Map.entry(Property.SOCIAL,Facet.SOCIAL));
        mapping.forEach((p,f)->{if(properties.get(p)==Signal.PRESENT)facets.add(f);});
        var permanent=EnumSet.of(Property.SECURITY,Property.HUMAN_CORRESPONDENCE,Property.HEALTHCARE,Property.SCHOOL,Property.ORDER);
        boolean positive=permanent.stream().anyMatch(p->properties.get(p)==Signal.PRESENT)
                || (resolved && Set.of(Purpose.PERSONAL,Purpose.RECEIPT,Purpose.SECURITY,Purpose.CALENDAR_INVITE).contains(choice.selected()));
        Attention attention=MessageRecommendation.attention(local,policy);
        boolean financial=(resolved && Set.of(Purpose.BILL,Purpose.STATEMENT).contains(choice.selected()))
                || properties.get(Property.BILL)==Signal.PRESENT || properties.get(Property.STATEMENT)==Signal.PRESENT;
        boolean unreadHold=attention==Attention.RECENT_UNREAD && (financial);
        var needed=EnumSet.copyOf(permanent);
        if(attention==Attention.RECENT_UNREAD || attention==Attention.UNKNOWN)
            needed.addAll(Set.of(Property.BILL,Property.STATEMENT));
        if(!policy.trashReadOrOldBills())needed.add(Property.BILL);
        boolean complete=needed.stream().allMatch(p->properties.get(p)!=Signal.UNKNOWN);
        boolean financialConsistent=!resolved || switch(choice.selected()) {
            case BILL->properties.get(Property.BILL)==Signal.PRESENT;
            case STATEMENT->properties.get(Property.STATEMENT)==Signal.PRESENT;
            default->true;
        };
        Qualification qualification=positive || unreadHold || (resolved && complete && financialConsistent)
                ? Qualification.QUALIFIED : Qualification.UNRESOLVED;
        // Generic model self-assessment is not a gate for direct messages or positive retention evidence.
        // Ambiguous forwarded/quoted content still cannot earn Trash through an unresolved wrapper.
        boolean wrapper=!HeaderSemanticAdmission.inspect(evidence.subject(),evidence.reply()).eligible();
        boolean contextRequired=wrapper && (variant==Variant.HEADERS || evidence.snippet().text().isBlank()
                || properties.get(Property.CONTEXT_SUFFICIENT)!=Signal.PRESENT);
        var assessment=new Assessment(resolved?choice.selected():Purpose.UNKNOWN,local.assessment().importance(),facets,
                properties.get(Property.SECURITY),properties.get(Property.HUMAN_CORRESPONDENCE),qualification,contextRequired);
        var composed=new Input(local.message(),local.evidenceRevision(),local.mailbox(),local.sizeEstimate(),assessment,local.manualDecision(),
                local.readState(),local.receivedAt(),local.evaluatedAt(),local.retention());
        return new Diagnostic(true,resolved,properties,new MessageRecommendation().evaluate(composed,policy));
    }
}
