package com.tidymail.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validated query parameters for the Unread page, controlling which unread
 * messages are listed (account, search, type, age, waiting-on-me, thread shape,
 * recommendation, sender, provider, sort, and page).
 *
 * <p>Product functionality supported: the <em>Unread</em> flow's list, filtering,
 * sorting, and pagination.
 */
public record UnreadQuery(
    String account,
    String search,
    Type type,
    Age age,
    Waiting waiting,
    ThreadShape thread,
    Recommendation recommendation,
    String sender,
    Provider provider,
    Sort sort,
    int page
) {
    public static final int PAGE_SIZE = 25;
    private static final Set<String> PARAMETERS = Set.of(
        "account", "search", "type", "age", "waiting", "thread",
        "recommendation", "sender", "provider", "sort", "page");

    public UnreadQuery {
        account = QueryParsing.account(account);
        search = QueryParsing.text(search, "search", 200, false);
        type = required(type, "type");
        age = required(age, "age");
        waiting = required(waiting, "waiting");
        thread = required(thread, "thread");
        recommendation = required(recommendation, "recommendation");
        sender = QueryParsing.senderAddress(sender);
        provider = required(provider, "provider");
        sort = required(sort, "sort");
        if (page < 0) throw QueryParsing.bad("page");
    }

    /** Parses and validates raw HTTP parameters into an {@link UnreadQuery}. */
    public static UnreadQuery parse(Map<String, ? extends List<String>> parameters) {
        Map<String, String> values = QueryParsing.singleValues(parameters, PARAMETERS);
        return new UnreadQuery(
            values.getOrDefault("account", "current"),
            values.getOrDefault("search", ""),
            QueryParsing.enumeration(values.get("type"), Type.ALL, Type.class, "type"),
            QueryParsing.enumeration(values.get("age"), Age.ALL, Age.class, "age"),
            QueryParsing.enumeration(values.get("waiting"), Waiting.ALL, Waiting.class, "waiting"),
            QueryParsing.enumeration(
                values.get("thread"), ThreadShape.ALL, ThreadShape.class, "thread"),
            QueryParsing.enumeration(
                values.get("recommendation"), Recommendation.ALL,
                Recommendation.class, "recommendation"),
            values.getOrDefault("sender", ""),
            QueryParsing.enumeration(
                values.get("provider"), Provider.ALL, Provider.class, "provider"),
            QueryParsing.enumeration(
                values.get("sort"), Sort.OLDEST_FIRST, Sort.class, "sort"),
            QueryParsing.page(values.get("page")));
    }

    /** Returns the canonical parameter set for this query. */
    public Map<String, String> canonicalParameters() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("account", account);
        values.put("search", search);
        values.put("type", type.value);
        values.put("age", age.value);
        values.put("waiting", waiting.value);
        values.put("thread", thread.value);
        values.put("recommendation", recommendation.value);
        values.put("sender", sender);
        values.put("provider", provider.value);
        values.put("sort", sort.value);
        values.put("page", Integer.toString(page));
        return Collections.unmodifiableMap(values);
    }

    /** Returns a copy of this query with the given page number. */
    public UnreadQuery withPage(int newPage) {
        return new UnreadQuery(
            account, search, type, age, waiting, thread, recommendation,
            sender, provider, sort, newPage);
    }

    /** Returns a copy of this query reset to the first page. */
    public UnreadQuery resetPage() {
        return withPage(0);
    }

    /** Returns the row offset for the current page. */
    public long offset() {
        return Math.multiplyExact((long) page, PAGE_SIZE);
    }

    private static <T> T required(T value, String field) {
        if (value == null) throw QueryParsing.bad(field);
        return value;
    }

    /** Inferred message type filter. */
    public enum Type implements QueryValue {
        ALL("all"), ONE_TO_ONE("one_to_one"), LIST("list");
        private final String value;
        Type(String value) { this.value = value; }
        public String value() { return value; }
    }

    /** Message age filter. */
    public enum Age implements QueryValue {
        ALL("all"), TODAY("today"), D7("7d"), D30("30d"), OLDER_30D("older_30d");
        private final String value;
        Age(String value) { this.value = value; }
        public String value() { return value; }
    }

    /** Whether the thread is waiting on the user to reply. */
    public enum Waiting implements QueryValue {
        ALL("all"), WAITING_ON_ME("waiting_on_me"), NOT_WAITING("not_waiting");
        private final String value;
        Waiting(String value) { this.value = value; }
        public String value() { return value; }
    }

    /** Thread shape filter (single message vs. threaded). */
    public enum ThreadShape implements QueryValue {
        ALL("all"), SINGLE("single"), THREADED("threaded");
        private final String value;
        ThreadShape(String value) { this.value = value; }
        public String value() { return value; }
    }

    /** Recommended handling filter. */
    public enum Recommendation implements QueryValue {
        ALL("all"), REPLY_REQUIRED("reply_required"), REVIEW("review"), ARCHIVE("archive"),
        TRASH("trash"), MARK_READ("mark_read"), KEEP("keep");
        private final String value;
        Recommendation(String value) { this.value = value; }
        public String value() { return value; }
    }

    /** Mail provider filter. */
    public enum Provider implements QueryValue {
        ALL("all"), GOOGLE("google");
        private final String value;
        Provider(String value) { this.value = value; }
        public String value() { return value; }
    }

    /** Sort order for the unread listing. */
    public enum Sort implements QueryValue {
        OLDEST_FIRST("oldest_first"), NEWEST_FIRST("newest_first"),
        SENDER_ASC("sender_asc"), RECOMMENDATION("recommendation");
        private final String value;
        Sort(String value) { this.value = value; }
        public String value() { return value; }
    }
}
