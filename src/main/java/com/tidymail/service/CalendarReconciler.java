package com.tidymail.service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;
import net.fortuna.ical4j.model.Recur;
import org.springframework.stereotype.Component;

/** Account-local email evidence, never a claim about an external calendar. */
@Component
public class CalendarReconciler {
    public static final String VERSION = "calendar-reducer-v2";
    private static final int MAX_OCCURRENCES = 366;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss").withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern DURATION = Pattern.compile("\\+?P(?:(\\d+)W|(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?)");
    public record Observation(String message, String part, CalendarEvidenceParser.Event event) {}
    public record Source(String message, String part) {}
    public record Result(String uidHash, String organizerHash, String state, Instant latestObservedEnd,
                         LocalDate latestObservedEndDate, int observedInstances, boolean historyComplete,
                         List<String> messages, List<Source> sources, String reason) {}

    /** All-day values stay dates; no display/account timezone is invented for them. */
    private record Moment(LocalDateTime local, ZoneId zone, boolean allDay) {
        Instant instant() { return zoned().toInstant(); }
        ZonedDateTime zoned() {
            if (allDay || zone == null) throw new Unknown("Event time has no established timezone");
            if (zone.getRules().getValidOffsets(local).size() != 1)
                throw new Unknown("Event time is ambiguous or falls in a daylight-saving gap");
            return local.atZone(zone);
        }
        String key() { return allDay ? "DATE:"+local.toLocalDate() : instant().toString(); }
        Moment onDate(LocalDate date) { return new Moment(date.atTime(local.toLocalTime()),zone,allDay); }
    }
    /** Nominal days are applied before exact hours/minutes/seconds (RFC 5545). */
    private record Span(long days, Duration exact) {
        Moment end(Moment start) {
            var nominal = new Moment(start.local().plusDays(days),start.zone(),start.allDay());
            if (start.allDay()) {
                if (!exact.isZero()) throw new Unknown("An all-day duration must use whole days or weeks");
                return nominal;
            }
            var end = nominal.zoned().plus(exact);
            return new Moment(end.toLocalDateTime(),end.getZone(),false);
        }
    }

    public List<Result> reconcile(List<Observation> input, Instant now, boolean lookupComplete) {
        var groups = new LinkedHashMap<String,List<Observation>>();
        for (var observation : input) {
            var event = observation.event();
            String key = event.uidHash()+":"+event.organizerHash();
            if (event.uidHash()==null || event.organizerHash()==null)
                key += ":"+observation.message()+":"+observation.part();
            groups.computeIfAbsent(key,ignored->new ArrayList<>()).add(observation);
        }
        return groups.values().stream().map(observations->reduce(observations,now,lookupComplete)).toList();
    }

    private Result reduce(List<Observation> observations, Instant now, boolean lookupComplete) {
        var identity = observations.getFirst().event();
        try {
            if (!lookupComplete) throw new Unknown("Related calendar details are incomplete or the lookup reached its limit");
            if (identity.uidHash()==null || identity.organizerHash()==null) throw new Unknown("Event identity or organizer is missing");
            var revisions = new HashMap<String,CalendarEvidenceParser.Event>();
            for (var observation : observations.stream().sorted(Comparator.comparingInt((Observation o)->sequence(o.event())).reversed()).toList()) {
                var event = observation.event();
                if (!Set.of("REQUEST","PUBLISH","CANCEL").contains(Objects.toString(event.method(),"")))
                    throw new Unknown("Calendar method does not establish an authoritative update");
                if (event.customTimezone()) throw new Unknown("Embedded timezone definitions need further interpretation");
                if (event.rangedUpdate()) throw new Unknown("An update affects this and future instances; further interpretation is needed");
                if (event.invalidTemporalParameters()) throw new Unknown("Calendar date/time parameters are invalid or unsupported");
                if (event.additions()==null || event.exclusions()==null) throw new Unknown("Related calendar details use an older extractor; check those emails again");
                String key = event.instance()==null ? "master" : moment(event.instance(),event.instanceTimezone()).key();
                var previous = revisions.get(key);
                if (previous==null) revisions.put(key,event);
                else if (sequence(event)==sequence(previous) && !event.equals(previous))
                    throw new Unknown("Conflicting updates have the same sequence");
            }
            var master = revisions.remove("master");
            if (master==null) throw new Unknown("A detached instance has no observed series master");
            if (master.cancelled() && revisions.values().stream().anyMatch(e->sequence(e)>sequence(master)))
                throw new Unknown("A newer detached update conflicts with a series cancellation");
            if (master.cancelled()) return result(observations,"CANCELLED",null,0,"Observed cancellation; cancellation does not prove elapsed time");

            var start = moment(master.start(),master.timezone());
            var span = span(master,start);
            var occurrences = new TreeMap<String,Moment>();
            for (var occurrence : expand(master,start)) occurrences.put(occurrence.key(),occurrence);
            for (var date : master.additions()) {
                var addition = compatible(moment(date.value(),date.timezone()),start);
                occurrences.put(addition.key(),addition);
            }
            if (occurrences.size()>MAX_OCCURRENCES) throw new Unknown("Recurrence expansion limit reached");
            var excluded = new HashSet<String>();
            for (var date : master.exclusions()) excluded.add(compatible(moment(date.value(),date.timezone()),start).key());

            Moment latest = null;
            int instances = 0;
            for (var entry : occurrences.entrySet()) {
                var exception = revisions.remove(entry.getKey());
                if (exception!=null && sequence(exception)<sequence(master))
                    throw new Unknown("An exception predates the latest series revision");
                if (exception!=null && (exception.rule()!=null || !exception.additions().isEmpty() || !exception.exclusions().isEmpty()))
                    throw new Unknown("Exception changes recurrence structure");
                if (exception!=null && exception.cancelled()) continue;
                if (excluded.contains(entry.getKey())) {
                    if (exception!=null) throw new Unknown("An excluded instance also has an active update");
                    continue;
                }
                var actualStart = exception==null ? entry.getValue() : compatible(moment(exception.start(),exception.timezone()),start);
                var end = (exception==null ? span : span(exception,actualStart)).end(actualStart);
                if (latest==null || (start.allDay() ? end.local().isAfter(latest.local()) : end.instant().isAfter(latest.instant()))) latest=end;
                instances++;
            }
            if (!revisions.isEmpty()) throw new Unknown("An exception does not match an observed recurrence instance");
            if (latest==null) throw new Unknown("No supported active instance remains; elapsed time is unknown");
            if (start.allDay()) return result(observations,"OBSERVED_DATE_RANGE",latest,instances,
                "Observed all-day dates have an exclusive end; no timezone or finished-event conclusion is inferred from incomplete email history");
            boolean upcoming = latest.instant().isAfter(now);
            return result(observations,upcoming ? "OBSERVED_UPCOMING" : "OBSERVED_ENDED",latest,instances,upcoming
                ? "Latest observed schedule includes future time; unobserved updates may exist"
                : "Observed schedule has ended; incomplete email history cannot establish a finished event or series");
        } catch (RuntimeException error) {
            return result(observations,"UNKNOWN",null,0,error instanceof Unknown ? error.getMessage() : "Calendar timing is invalid or unsupported");
        }
    }

    private Result result(List<Observation> observations,String state,Moment end,int instances,String reason) {
        var identity = observations.getFirst().event();
        var messages = observations.stream().map(Observation::message).distinct().sorted().toList();
        var sources = observations.stream().map(o->new Source(o.message(),o.part())).distinct()
            .sorted(Comparator.comparing(Source::message).thenComparing(Source::part,Comparator.nullsFirst(Comparator.naturalOrder()))).toList();
        return new Result(identity.uidHash(),identity.organizerHash(),state,end==null || end.allDay() ? null : end.instant(),
            end!=null && end.allDay() ? end.local().toLocalDate() : null,instances,false,messages,sources,reason);
    }
    private int sequence(CalendarEvidenceParser.Event event) {
        int value=event.sequence()==null ? 0 : Integer.parseInt(event.sequence());
        if (value<0) throw new Unknown("Invalid calendar sequence");
        return value;
    }
    private Moment moment(String value,String timezone) {
        if (value==null) throw new Unknown("Event time is missing");
        if (value.matches("[0-9]{8}")) {
            if (timezone!=null) throw new Unknown("An all-day date cannot specify a timezone");
            return new Moment(LocalDate.parse(value,DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(),null,true);
        }
        boolean utc=value.endsWith("Z");
        if (utc && timezone!=null) throw new Unknown("UTC time cannot also specify a timezone");
        if (!utc && timezone==null) throw new Unknown("Floating event time has no established timezone");
        var result=new Moment(LocalDateTime.parse(utc ? value.substring(0,value.length()-1) : value,TIMESTAMP),utc ? ZoneOffset.UTC : ZoneId.of(timezone),false);
        result.zoned();
        return result;
    }
    private Moment compatible(Moment value,Moment start) {
        if (value.allDay()!=start.allDay()) throw new Unknown("Calendar dates and date-times cannot be mixed in one recurrence");
        if (value.allDay()) return value;
        var zoned=value.zoned().withZoneSameInstant(start.zone());
        return new Moment(zoned.toLocalDateTime(),start.zone(),false);
    }
    private Span span(CalendarEvidenceParser.Event event,Moment start) {
        if (event.end()!=null && event.duration()!=null) throw new Unknown("Both end and duration are present");
        if (event.end()!=null) {
            var end=compatible(moment(event.end(),event.endTimezone()),start);
            if (start.allDay()) {
                long days=ChronoUnit.DAYS.between(start.local().toLocalDate(),end.local().toLocalDate());
                if (days<=0) throw new Unknown("All-day end must follow its start");
                return new Span(days,Duration.ZERO);
            }
            var duration=Duration.between(start.instant(),end.instant());
            if (duration.isNegative()) throw new Unknown("Event end precedes its start");
            return new Span(0,duration);
        }
        if (event.duration()==null) return new Span(start.allDay() ? 1 : 0,Duration.ZERO);
        var match=DURATION.matcher(event.duration());
        if (!match.matches() || event.duration().endsWith("T")) throw new Unknown("Invalid calendar duration");
        boolean any=false;
        for (int i=1;i<=5;i++) any|=match.group(i)!=null;
        if (!any) throw new Unknown("Invalid calendar duration");
        long days=Math.addExact(Math.multiplyExact(number(match.group(1)),7),number(match.group(2)));
        Duration exact=Duration.ofHours(number(match.group(3))).plusMinutes(number(match.group(4))).plusSeconds(number(match.group(5)));
        if (start.allDay() && days==0) throw new Unknown("All-day duration must include at least one day");
        if (days>3660 || exact.compareTo(Duration.ofDays(3660))>0) throw new Unknown("Calendar duration exceeds the supported bound");
        if (start.allDay() && (!exact.isZero() || event.duration().contains("T"))) throw new Unknown("An all-day duration must use whole days or weeks");
        return new Span(days,exact);
    }
    private long number(String value) { return value==null ? 0 : Long.parseLong(value); }

    /** Calendar-date expansion uses the pinned parser library; timezones are resolved locally afterward. */
    private List<Moment> expand(CalendarEvidenceParser.Event event,Moment start) {
        if (event.rule()==null) return List.of(start);
        var rule=new LinkedHashMap<String,String>();
        for (String pair : event.rule().split(";",-1)) {
            String[] fields=pair.split("=",-1);
            if (fields.length!=2 || fields[1].isEmpty() || rule.put(fields[0],fields[1])!=null) throw new Unknown("Invalid recurrence rule");
        }
        if (!Set.of("FREQ","COUNT","INTERVAL","UNTIL","BYDAY","BYMONTHDAY","BYMONTH","BYSETPOS","WKST").containsAll(rule.keySet()))
            throw new Unknown("Recurrence rule requires unsupported expansion");
        if (!Set.of("DAILY","WEEKLY","MONTHLY","YEARLY").contains(rule.getOrDefault("FREQ",""))) throw new Unknown("Recurrence frequency is unsupported");
        if (rule.containsKey("COUNT") && rule.containsKey("UNTIL")) throw new Unknown("Conflicting recurrence bounds");
        int count=rule.containsKey("COUNT") ? Integer.parseInt(rule.get("COUNT")) : MAX_OCCURRENCES+1;
        int interval=Integer.parseInt(rule.getOrDefault("INTERVAL","1"));
        if (count<1 || count>MAX_OCCURRENCES && !rule.containsKey("UNTIL") || interval<1 || interval>366)
            throw new Unknown("Open-ended, invalid or oversized recurrence remains unresolved");
        var firstDate=start.local().toLocalDate();
        var horizon=firstDate.plusYears(10);
        Moment until=null;
        if (rule.containsKey("UNTIL")) {
            String raw=rule.get("UNTIL");
            if (!start.allDay() && !raw.endsWith("Z")) throw new Unknown("Timed recurrence requires a UTC end bound");
            until=compatible(moment(raw,null),start);
            if (until.local().toLocalDate().isAfter(horizon)) throw new Unknown("Recurrence exceeds the ten-year expansion horizon");
            if (until.local().isBefore(start.local())) throw new Unknown("Recurrence end precedes its start");
            // The pinned library stops after 1,000 consecutive empty increments. Never mistake that stop for a complete UNTIL set.
            if (rule.get("FREQ").equals("DAILY") && ChronoUnit.DAYS.between(firstDate,until.local().toLocalDate())/interval>=999)
                throw new Unknown("Daily recurrence end exceeds the supported expansion span");
            rule.put("UNTIL",until.local().toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE));
        }
        if (rule.get("FREQ").equals("WEEKLY") && (rule.containsKey("BYMONTHDAY") || rule.getOrDefault("BYDAY","").matches(".*[0-9].*")))
            throw new Unknown("Weekly recurrence has invalid day constraints");
        if (Set.of("DAILY","WEEKLY").contains(rule.get("FREQ")) && rule.getOrDefault("BYDAY","").matches(".*[0-9].*"))
            throw new Unknown("Ordinal weekdays require monthly or yearly recurrence");
        if (rule.containsKey("BYSETPOS") && !rule.containsKey("BYDAY") && !rule.containsKey("BYMONTHDAY") && !rule.containsKey("BYMONTH"))
            throw new Unknown("Recurrence positions require a date constraint");
        var encoded=String.join(";",rule.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).toList());
        var recurrence=new Recur<LocalDate>(encoded);
        // Non-date/time and experimental extensions must never silently alter this contract.
        if (!recurrence.getExperimentalValues().isEmpty()) throw new Unknown("Recurrence extensions are unsupported");
        var dates=recurrence.getDates(firstDate,firstDate,horizon.plusDays(1),MAX_OCCURRENCES+1);
        if (dates.size()>MAX_OCCURRENCES) throw new Unknown("Recurrence expansion limit reached");
        if (dates.isEmpty() || !dates.getFirst().equals(firstDate)) throw new Unknown("Event start does not match its recurrence rule");
        if (rule.containsKey("COUNT") && dates.size()!=count) throw new Unknown("Recurrence cannot be completed within the expansion horizon");
        var occurrences=new ArrayList<Moment>();
        for (var date : dates) {
            var occurrence=start.onDate(date);
            if (!start.allDay()) occurrence.zoned();
            if (until!=null && (start.allDay() ? occurrence.local().isAfter(until.local()) : occurrence.instant().isAfter(until.instant()))) continue;
            occurrences.add(occurrence);
        }
        return occurrences;
    }
    private static class Unknown extends RuntimeException { Unknown(String reason) { super(reason); } }
}
