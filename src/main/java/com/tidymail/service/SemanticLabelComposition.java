package com.tidymail.service;

import static com.tidymail.service.MessageRecommendation.*;
import static com.tidymail.service.RecommendationSemanticContract.*;
import java.util.*;

/** Independent positive labels; this route has not qualified negative signals for disposal. */
public final class SemanticLabelComposition {
    private SemanticLabelComposition() {}
    public static Assessment merge(Assessment local, Judgment judgment, boolean correctedPurpose) {
        var facets=EnumSet.noneOf(Facet.class);facets.addAll(local.facets());
        Map<Property,Facet> mapping=Map.ofEntries(Map.entry(Property.SECURITY,Facet.SECURITY),
            Map.entry(Property.HUMAN_CORRESPONDENCE,Facet.HUMAN_CORRESPONDENCE),Map.entry(Property.HEALTHCARE,Facet.HEALTHCARE),
            Map.entry(Property.SCHOOL,Facet.SCHOOL),Map.entry(Property.ORDER,Facet.ORDER),Map.entry(Property.BILL,Facet.BILL),
            Map.entry(Property.STATEMENT,Facet.STATEMENT),Map.entry(Property.MARKETING,Facet.MARKETING),
            Map.entry(Property.SHIPPING,Facet.SHIPPING),Map.entry(Property.SOCIAL,Facet.SOCIAL));
        mapping.forEach((property,facet)->{if(judgment.properties().getOrDefault(property,0.)>=.9)facets.add(facet);});
        var choice=judgment.purpose();
        boolean resolved=choice.selected()!=Purpose.UNKNOWN && choice.confidence()>=.85
            && choice.probabilities().getOrDefault(choice.selected(),0.)>=.85;
        Property corresponding=switch(choice.selected()) {
            case PERSONAL->Property.HUMAN_CORRESPONDENCE;case RECEIPT->Property.ORDER;case SECURITY->Property.SECURITY;
            case BILL->Property.BILL;case STATEMENT->Property.STATEMENT;default->null;
        };
        if(corresponding!=null && judgment.properties().getOrDefault(corresponding,.5)<=.1)resolved=false;
        Purpose purpose=correctedPurpose || !resolved?local.purpose():choice.selected();
        // Preserve a supported human correction even when the model disagrees.
        if(correctedPurpose && local.purpose()==Purpose.PERSONAL) facets.add(Facet.HUMAN_CORRESPONDENCE);
        if(correctedPurpose && local.purpose()==Purpose.SECURITY) facets.add(Facet.SECURITY);
        boolean protect=Set.of(Purpose.PERSONAL,Purpose.RECEIPT,Purpose.SECURITY,Purpose.CALENDAR_INVITE).contains(purpose)
            || !Collections.disjoint(facets,Set.of(Facet.SCHOOL,Facet.HEALTHCARE,Facet.ORDER,Facet.SECURITY,Facet.HUMAN_CORRESPONDENCE));
        return new Assessment(purpose,local.importance(),facets,Signal.UNKNOWN,Signal.UNKNOWN,
            (protect || Set.of(Purpose.BILL,Purpose.STATEMENT).contains(purpose) || facets.contains(Facet.BILL) || facets.contains(Facet.STATEMENT))?Qualification.QUALIFIED:Qualification.UNRESOLVED,local.contextRequired());
    }
}
