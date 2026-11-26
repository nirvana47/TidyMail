package com.tidymail.dto;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** One predicate for browsing, expansion, selection and preview. */
public record DashboardFilter(String scope, String q, String start, String end, String zone, int page,
        String category, String sort, String suggestion, String recommendation, String importance, String label, String labels, String match, String composition, boolean excludeProtectedShopping, String groupBy, Integer pageSize) {
    public DashboardFilter(String scope, String q, String start, String end, String zone, int page,
            String category, String sort, String suggestion, String recommendation, String importance, String label,
            String labels, String match, String composition, boolean excludeProtectedShopping, String groupBy) {
        this(scope, q, start, end, zone, page, category, sort, suggestion, recommendation, importance, label,
                labels, match, composition, excludeProtectedShopping, groupBy, 10);
    }
    public DashboardFilter(String scope, String q, String start, String end, String zone, int page,
            String category, String sort, String suggestion, String recommendation, String importance, String label,
            String labels, String match, String composition, boolean excludeProtectedShopping) {
        this(scope, q, start, end, zone, page, category, sort, suggestion, recommendation, importance, label,
                labels, match, composition, excludeProtectedShopping, "current");
    }
    public DashboardFilter(String scope, String q, String start, String end, String zone, int page,
            String category, String sort, String suggestion, String recommendation, String importance, String label,
            String labels, String match, String composition) {
        this(scope, q, start, end, zone, page, category, sort, suggestion, recommendation, importance, label,
                labels, match, composition, false);
    }
    public DashboardFilter(String scope, String q, String start, String end, String zone, int page,
            String category, String sort, String suggestion, String recommendation, String importance, String label) {
        this(scope, q, start, end, zone, page, category, sort, suggestion, recommendation, importance, label, "", "all", "");
    }

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
        pageSize = pageSize == null ? 10 : pageSize;
        recommendation = recommendation == null ? "" : recommendation;
        importance = importance == null ? "" : importance;
        label = label == null ? "" : label;
        composition = composition == null ? "" : composition;
        groupBy = groupBy == null ? "current" : groupBy;
        labels = labels == null ? "" : labels;
        match = match == null || match.isBlank() ? "all" : match;
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
            if (!java.util.Set.of(10, 20, 50).contains(pageSize)) throw new IllegalArgumentException();
            if (!java.util.Set.of("","KEEP_IN_INBOX","KEEP_STORED","ARCHIVE","SUGGEST_TRASH","REVIEW","PENDING","STALE").contains(recommendation)) throw new IllegalArgumentException();
            if (!java.util.Set.of("","IMPORTANT","ROUTINE","UNKNOWN").contains(importance)) throw new IllegalArgumentException();
            if (!java.util.Set.of("current", "address").contains(groupBy)) throw new IllegalArgumentException();
            if (!composition.isEmpty() && (!scope.equals("inbox")
                    || (groupBy.equals("address") && !composition.startsWith("group:"))
                    || !composition.matches("(domain:[a-z0-9.-]{1,253}|group:[a-f0-9-]{36})")))
                throw new IllegalArgumentException();
            if (!label.isEmpty()) requireStoredLabel(label);
            if (!java.util.Set.of("all", "any").contains(match) || labels.length() > 800)
                throw new IllegalArgumentException();
            if (!labels.isEmpty()) {
                for (String value : labels.split(",", -1)) {
                    requireStoredLabel(value);
                    if (value.equals("SENT") && !suggestion.equals("large")) throw new IllegalArgumentException();
                }
            }
            if (label.equals("SENT") && !suggestion.equals("large")) throw new IllegalArgumentException();
            if (!java.util.Set.of("", "large").contains(suggestion)) throw new IllegalArgumentException();
            if (!java.util.Set.of("messages", "unread", "latest", "size").contains(sort))
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
            for(String retired:java.util.List.of("category","recommendation","importance"))
                if(!values.getOrDefault(retired, "").isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"This filter has been removed");
            var filter = new DashboardFilter(values.get("scope"), values.get("q"), values.get("start"), values.get("end"),
                    values.get("zone"), Integer.parseInt(values.getOrDefault("page", "0")), values.get("category"), values.get("sort"), values.get("suggestion"), values.get("recommendation"), values.get("importance"), values.get("label"), values.get("labels"), values.get("match"), values.get("composition"), Boolean.parseBoolean(values.get("excludeProtectedShopping")), values.get("groupBy"), Integer.valueOf(values.getOrDefault("pageSize", "10")));
            filter.requireAvailable();
            return filter;
        } catch (NumberFormatException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page");
        }
    }

    // Old confirmed jobs must still decode their frozen filters after feature removal.
    private static void requireStoredLabel(String value) {
        if (!value.matches("[A-Z][A-Z0-9_]{0,79}")) throw new IllegalArgumentException("Invalid saved label");
    }
    private static boolean availableLabel(String value) {
        return com.tidymail.service.LocalMailLabels.EXTRA_FILTERS.contains(value)
            || value.matches("GMAIL_LABEL_[0-9]{1,30}")
            || java.util.Set.of("CONTACT","CALENDAR_INVITE","LARGE_MESSAGE","HAS_ATTACHMENTS","SENT","SOCIAL","NOTIFICATION","ORDER","SHIPPING","SECURITY","BILL","STATEMENT","RECEIPT").contains(value);
    }
    public void requireAvailable() {
        if (!category.isEmpty() || !recommendation.isEmpty() || !importance.isEmpty()
            || (!label.isEmpty() && !availableLabel(label))
            || (!labels.isEmpty() && java.util.Arrays.stream(labels.split(",")).anyMatch(value -> !availableLabel(value))))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"This filter has been removed");
    }

    public boolean bySenderAddress() {
        return groupBy.equals("address");
    }

    public DashboardFilter inbox() {
        return new DashboardFilter("inbox", q, start, end, zone, 0, category, sort, suggestion, recommendation, importance, label, labels, match, "", excludeProtectedShopping, groupBy, pageSize);
    }

    public Instant startInstant() {
        return start.isEmpty() ? null : LocalDate.parse(start).atStartOfDay(ZoneId.of(zone)).toInstant();
    }

    public Instant endInstant() {
        return end.isEmpty() ? null : LocalDate.parse(end).plusDays(1).atStartOfDay(ZoneId.of(zone)).toInstant();
    }

    /** Browsing all indexed mail must not expand cleanup eligibility. */
    public DashboardFilter cleanupScope() {
        // Already-confirmed jobs keep their exact IDs; removed AI filters cannot strand them.
        return new DashboardFilter(scope.equals("indexed")?"all":scope,q,start,end,zone,page,"",sort,suggestion,"","",availableLabel(label)?label:"",labels.isEmpty()?"":java.util.Arrays.stream(labels.split(",")).filter(DashboardFilter::availableLabel).collect(java.util.stream.Collectors.joining(",")),match,"",excludeProtectedShopping,groupBy,pageSize);
    }

    /** A chart drilldown scopes selection, while confirmed IDs survive later grouping edits. */
    public DashboardFilter withoutComposition() {
        return composition.isEmpty() ? this : inbox();
    }

    public DashboardFilter firstPage() {
        return new DashboardFilter(scope, q, start, end, zone, 0, category, sort, suggestion,recommendation,importance,label,labels,match,composition,excludeProtectedShopping,groupBy,pageSize);
    }
}
