package com.tidymail.dto;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** One predicate for browsing, expansion, selection and preview. */
public record DashboardFilter(String scope, String q, String start, String end, String zone, int page) {
    public DashboardFilter {
        scope=scope==null ? "inbox" : scope;
        q=q==null ? "" : q.strip(); start=start==null ? "" : start; end=end==null ? "" : end;
        zone=zone==null || zone.isBlank() ? "UTC" : zone;
        try {
            if (!scope.equals("inbox") && !scope.equals("all") || q.length()>200 || page<0 || page>1_000_000)
                throw new IllegalArgumentException();
            ZoneId.of(zone);
            if (!start.isEmpty()) LocalDate.parse(start);
            if (!end.isEmpty()) LocalDate.parse(end);
            if (!start.isEmpty() && !end.isEmpty() && LocalDate.parse(start).isAfter(LocalDate.parse(end)))
                throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid dashboard filters"); }
    }
    public static DashboardFilter from(Map<String,String> values) {
        try { return new DashboardFilter(values.get("scope"),values.get("q"),values.get("start"),values.get("end"),values.get("zone"),Integer.parseInt(values.getOrDefault("page","0"))); }
        catch (NumberFormatException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid page"); }
    }
    public Instant startInstant() { return start.isEmpty() ? null : LocalDate.parse(start).atStartOfDay(ZoneId.of(zone)).toInstant(); }
    public Instant endInstant() { return end.isEmpty() ? null : LocalDate.parse(end).plusDays(1).atStartOfDay(ZoneId.of(zone)).toInstant(); }
    public DashboardFilter firstPage() { return new DashboardFilter(scope,q,start,end,zone,0); }
}
