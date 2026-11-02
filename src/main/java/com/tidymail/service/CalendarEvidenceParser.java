package com.tidymail.service;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import net.fortuna.ical4j.data.CalendarParserImpl;
import net.fortuna.ical4j.data.ContentHandler;
import org.springframework.stereotype.Component;

/** Parse ICS locally without constructing a timezone registry or resolving URLs. */
@Component
public class CalendarEvidenceParser {
    public record OccurrenceDate(String value, String timezone) {}
    public record Event(String uidHash, String instance, String start, String end, String duration,
                        String timezone, String sequence, boolean recurring, boolean cancelled,
                        String organizerHash, String method, String rule, String recurrenceDates,
                        String excludedDates, String instanceTimezone, String endTimezone, boolean customTimezone,
                        List<OccurrenceDate> additions, List<OccurrenceDate> exclusions,
                        boolean rangedUpdate, boolean invalidTemporalParameters) {}
    public record Facts(String state, List<Event> events, String conclusion, String partId, String contentHash) {
        public Facts(String state, List<Event> events, String conclusion) { this(state,events,conclusion,null,null); }
        public Facts source(String part, String hash) { return new Facts(state,events,conclusion,part,hash); }
    }

    public Facts parse(String text) {
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length > 131072)
            return new Facts("TRUNCATED", List.of(), "Event timing is unknown");
        var handler = new Handler();
        try {
            new CalendarParserImpl().parse(new StringReader(text), handler);
            if (handler.events.isEmpty()) return new Facts("UNSUPPORTED", List.of(), "No calendar event found");
            return new Facts("AVAILABLE", handler.events.stream().map(e -> new Event(
                e.containsKey("UID") && !e.get("UID").isBlank() ? hash(e.get("UID")) : null, e.get("RECURRENCE-ID"), e.get("DTSTART"), e.get("DTEND"),
                e.get("DURATION"), e.get("DTSTART-TZID"), e.get("SEQUENCE"),
                e.keySet().stream().anyMatch(Set.of("RRULE", "RDATE", "EXDATE", "RECURRENCE-ID")::contains),
                "CANCEL".equals(handler.method) || "CANCELLED".equals(e.get("STATUS")),
                e.containsKey("ORGANIZER") && !e.get("ORGANIZER").isBlank() ? hash(e.get("ORGANIZER").strip().toLowerCase(Locale.ROOT)) : null,
                handler.method, e.get("RRULE"), e.get("RDATE"), e.get("EXDATE"),
                e.get("RECURRENCE-ID-TZID"), e.get("DTEND-TZID"), handler.customTimezone,
                List.copyOf(e.additions), List.copyOf(e.exclusions), e.rangedUpdate, e.invalidTemporalParameters)).toList(),
                "Timing found; related invitations and updates still need checking");
        } catch (Exception ignored) {
            return new Facts("FAILED", List.of(), "Calendar content could not be read");
        }
    }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static class EventData extends HashMap<String,String> {
        final List<OccurrenceDate> additions = new ArrayList<>(), exclusions = new ArrayList<>();
        boolean rangedUpdate, invalidTemporalParameters;
    }
    private static class Handler implements ContentHandler {
        private static final Set<String> TEMPORAL = Set.of("DTSTART","DTEND","RECURRENCE-ID","RDATE","EXDATE");
        final Deque<String> components = new ArrayDeque<>();
        final List<EventData> events = new ArrayList<>();
        EventData event;
        String property, timezone, valueType, method;
        boolean customTimezone, ranged;
        public void startCalendar() {}
        public void endCalendar() {}
        public void startComponent(String name) {
            components.push(name.toUpperCase(Locale.ROOT));
            if (name.equalsIgnoreCase("VTIMEZONE")) customTimezone = true;
            if (components.size() > 32) throw new IllegalArgumentException("Calendar too deeply nested");
            if (name.equalsIgnoreCase("VEVENT")) {
                if (event != null || events.size() >= 64) throw new IllegalArgumentException("Invalid event nesting or count");
                event = new EventData(); events.add(event);
            }
        }
        public void endComponent(String name) { components.pop(); if (name.equalsIgnoreCase("VEVENT")) event = null; }
        public void startProperty(String name) { property = name.toUpperCase(Locale.ROOT); timezone = null; valueType = null; ranged = false; }
        public void parameter(String name,String value) {
            if (name.equalsIgnoreCase("TZID")) timezone = value;
            if (name.equalsIgnoreCase("VALUE")) valueType = value;
            if (name.equalsIgnoreCase("RANGE")) ranged = true;
        }
        public void propertyValue(String value) {
            if (components.isEmpty() && property.equals("METHOD")) {
                if (method != null) throw new IllegalArgumentException("Duplicate calendar method");
                method = value;
            }
            if (event == null || !"VEVENT".equals(components.peek())) return;
            if (!Set.of("UID", "RECURRENCE-ID", "DTSTART", "DTEND", "DURATION", "SEQUENCE", "RRULE", "RDATE", "EXDATE", "STATUS", "ORGANIZER").contains(property)) return;
            if (TEMPORAL.contains(property)) {
                event.rangedUpdate |= ranged;
                for (String item : value.split(",",-1)) {
                    boolean date = item.matches("[0-9]{8}");
                    event.invalidTemporalParameters |= date != "DATE".equals(valueType)
                        || (valueType != null && !Set.of("DATE","DATE-TIME").contains(valueType))
                        || (timezone != null && (date || item.endsWith("Z")));
                }
            }
            if (property.equals("RDATE") || property.equals("EXDATE")) {
                var target = property.equals("RDATE") ? event.additions : event.exclusions;
                for (String item : value.split(",",-1)) {
                    if (target.size() >= 366) throw new IllegalArgumentException("Too many recurrence dates");
                    target.add(new OccurrenceDate(item,timezone));
                }
                event.merge(property,value,(old,next)->old+","+next);
            } else {
                if (event.containsKey(property)) throw new IllegalArgumentException("Ambiguous calendar property");
                event.put(property,value);
                if (timezone != null) event.put(property+"-TZID",timezone);
            }
        }
        public void endProperty(String name) {}
    }
}
