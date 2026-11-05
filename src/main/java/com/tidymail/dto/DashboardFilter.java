package com.tidymail.dto;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** One predicate for browsing, expansion, selection and preview. */
public record DashboardFilter(String scope, String q, String start, String end, String zone, int page,
        String category, String sort, String suggestion, String recommendation, String importance, String label) {
    public DashboardFilter(String scope,String q,String start,String end,String zone,int page,String category,String sort,String suggestion) {
        this(scope,q,start,end,zone,page,category,sort,suggestion,"","","");
    }
    public DashboardFilter(String scope, String q, String start, String end, String zone, int page, String category, String sort) {
        this(scope,q,start,end,zone,page,category,sort,"");
    }
    public DashboardFilter(String scope, String q, String start, String end, String zone, int page, String category) {
        this(scope, q, start, end, zone, page, category, "messages");
    }

    public DashboardFilter(String scope, String q, String start, String end, String zone, int page) {
        this(scope, q, start, end, zone, page, "", "messages");
    }

    public DashboardFilter {
        recommendation = recommendation == null ? "" : recommendation;
        importance = importance == null ? "" : importance;
        label = label == null ? "" : label;
        suggestion = suggestion == null ? "" : suggestion;
        sort = sort == null ? "messages" : sort;
        category = category == null ? "" : category;
        if (category.equals("UNCERTAIN")) category="OTHER";
        scope = scope == null ? "inbox" : scope;
        q = q == null ? "" : q.strip();
        start = start == null ? "" : start;
        end = end == null ? "" : end;
        zone = zone == null || zone.isBlank() ? "UTC" : zone;
        try {
            if (!java.util.Set.of("","KEEP_IN_INBOX","KEEP_STORED","ARCHIVE","SUGGEST_TRASH","REVIEW","PENDING","STALE").contains(recommendation)) throw new IllegalArgumentException();
            if (!java.util.Set.of("","IMPORTANT","ROUTINE","UNKNOWN").contains(importance)) throw new IllegalArgumentException();
            if (!label.isEmpty()) {
                try {com.tidymail.service.MessageRecommendation.Facet.valueOf(label);}
                catch(IllegalArgumentException notFacet){com.tidymail.service.MessageRecommendation.Purpose.valueOf(label);}
            }
            if (label.equals("SENT") && !suggestion.equals("large")) throw new IllegalArgumentException();
            if (!java.util.Set.of("", "large").contains(suggestion)) throw new IllegalArgumentException();
            if (!java.util.Set.of("messages", "unread").contains(sort))
                throw new IllegalArgumentException();
            if (!java.util.Set.of("inbox", "all", "indexed").contains(scope) || q.length() > 200 || page < 0 || page > 1_000_000)
                throw new IllegalArgumentException();
            if (!category.isEmpty() && !java.util.Set.of("PERSONAL", "MARKETING", "NEWSLETTER", "RECEIPT", "SECURITY",
                    "NOTIFICATION", "SOCIAL", "BILL", "STATEMENT", "CALENDAR_INVITE", "OTHER", "UNCERTAIN", "UNCLASSIFIED").contains(category))
                throw new IllegalArgumentException();
            ZoneId.of(zone);
            if (!start.isEmpty())
                LocalDate.parse(start);
            if (!end.isEmpty())
                LocalDate.parse(end);
            if (!start.isEmpty() && !end.isEmpty() && LocalDate.parse(start).isAfter(LocalDate.parse(end)))
                throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid dashboard filters");
        }
    }

    public static DashboardFilter from(Map<String, String> values) {
        try {
            return new DashboardFilter(values.get("scope"), values.get("q"), values.get("start"), values.get("end"),
                    values.get("zone"), Integer.parseInt(values.getOrDefault("page", "0")), values.get("category"), values.get("sort"), values.get("suggestion"), values.get("recommendation"), values.get("importance"), values.get("label"));
        } catch (NumberFormatException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page");
        }
    }

    public Instant startInstant() {
        return start.isEmpty() ? null : LocalDate.parse(start).atStartOfDay(ZoneId.of(zone)).toInstant();
    }

    public Instant endInstant() {
        return end.isEmpty() ? null : LocalDate.parse(end).plusDays(1).atStartOfDay(ZoneId.of(zone)).toInstant();
    }

    /** Browsing all indexed mail must not expand cleanup eligibility. */
    public DashboardFilter cleanupScope() {
        return scope.equals("indexed")
            ? new DashboardFilter("all", q, start, end, zone, page, category, sort, suggestion, recommendation, importance, label)
            : this;
    }

    public DashboardFilter firstPage() {
        return new DashboardFilter(scope, q, start, end, zone, 0, category, sort, suggestion,recommendation,importance,label);
    }
}
