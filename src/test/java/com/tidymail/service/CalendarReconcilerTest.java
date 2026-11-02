package com.tidymail.service;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class CalendarReconcilerTest {
    final CalendarEvidenceParser parser=new CalendarEvidenceParser();
    final CalendarReconciler reducer=new CalendarReconciler();
    final Instant now=Instant.parse("2026-11-02T00:00:00Z");
    CalendarReconciler.Observation event(String message,String method,String properties) {
        var facts=parser.parse("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nMETHOD:"+method+"\r\nBEGIN:VEVENT\r\nUID:event\r\nORGANIZER:mailto:person@example.test\r\n"+properties+"\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n");
        assertThat(facts.state()).isEqualTo("AVAILABLE");
        return new CalendarReconciler.Observation(message,"1",facts.events().getFirst());
    }
    CalendarReconciler.Result result(CalendarReconciler.Observation... events) {return reducer.reconcile(List.of(events),now,true).getFirst();}
    @Test void sequenceWinsOverReceiptOrderAndReschedulePreventsPastConclusion() {
        var old=event("old","REQUEST","DTSTART:20200101T090000Z\r\nDTEND:20200101T100000Z\r\nSEQUENCE:1");
        var future=event("new","REQUEST","DTSTART:20270101T090000Z\r\nDURATION:PT1H\r\nSEQUENCE:2");
        assertThat(result(future,old).state()).isEqualTo("OBSERVED_UPCOMING");
        assertThat(result(old).historyComplete()).isFalse();
        assertThat(result(old).reason()).contains("incomplete email history");
        assertThat(result(old).state()).isEqualTo("OBSERVED_ENDED");
    }
    @Test void finiteRecurrenceUsesDetachedExceptionAndCancellation() {
        var master=event("master","REQUEST","DTSTART:20261025T090000Z\r\nDURATION:PT1H\r\nRRULE:FREQ=WEEKLY;COUNT=2\r\nSEQUENCE:1");
        var moved=event("moved","REQUEST","RECURRENCE-ID:20261101T090000Z\r\nDTSTART:20270101T090000Z\r\nDURATION:PT1H\r\nSEQUENCE:2");
        assertThat(result(master,moved).latestObservedEnd()).isEqualTo(Instant.parse("2027-01-01T10:00:00Z"));
        var cancelled=event("cancelled","CANCEL","RECURRENCE-ID:20261101T090000Z\r\nSEQUENCE:3");
        assertThat(result(master,moved,cancelled).latestObservedEnd()).isEqualTo(Instant.parse("2026-10-25T10:00:00Z"));
        assertThat(result(moved).state()).isEqualTo("UNKNOWN");
        assertThat(result(event("cancel","CANCEL","SEQUENCE:2"),master).state()).isEqualTo("CANCELLED");
    }
    @Test void conflictingVersionsIncompleteDiscoveryAndUnsupportedRecurrenceRemainUnknown() {
        var old=event("a","REQUEST","DTSTART:20200101T090000Z\r\nDURATION:PT1H");
        var other=event("b","REQUEST","DTSTART:20200102T090000Z\r\nDURATION:PT1H");
        assertThat(result(old,other).state()).isEqualTo("UNKNOWN");
        assertThat(result(old,old).state()).isEqualTo("OBSERVED_ENDED");
        assertThat(reducer.reconcile(List.of(old),now,false).getFirst().state()).isEqualTo("UNKNOWN");
        for (String rule:List.of("FREQ=DAILY","FREQ=HOURLY;COUNT=2","FREQ=WEEKLY;COUNT=2;BYDAY=MO","FREQ=DAILY;COUNT=367"))
            assertThat(result(event("r","REQUEST","DTSTART:20200101T090000Z\r\nDURATION:PT1H\r\nRRULE:"+rule)).state()).isEqualTo("UNKNOWN");
    }
    @Test void timezoneDstAndUntilAreBoundedWhileFloatingAllDayAndAmbiguousTimesStayUnknown() {
        var dst=event("dst","REQUEST","DTSTART;TZID=America/Los_Angeles:20261031T090000\r\nDURATION:PT1H\r\nRRULE:FREQ=DAILY;COUNT=2");
        assertThat(result(dst).latestObservedEnd()).isEqualTo(Instant.parse("2026-11-01T18:00:00Z"));
        var until=event("until","REQUEST","DTSTART:20261030T090000Z\r\nDURATION:PT1H\r\nRRULE:FREQ=DAILY;UNTIL=20261101T090000Z");
        assertThat(result(until).latestObservedEnd()).isEqualTo(Instant.parse("2026-11-01T10:00:00Z"));
        for(String start:List.of("DTSTART:20200101T090000","DTSTART;VALUE=DATE:20200101","DTSTART;TZID=America/Los_Angeles:20261101T013000","DTSTART;TZID=America/Los_Angeles:20260308T023000"))
            assertThat(result(event("unsupported","REQUEST",start+"\r\nDURATION:PT1H")).state()).isEqualTo("UNKNOWN");
    }

    @Test void allDayEventsKeepExclusiveDateEndsWithoutInventingTimezoneOrPastClaims() {
        var single=result(event("day","REQUEST","DTSTART;VALUE=DATE:20261101"));
        assertThat(single.state()).isEqualTo("OBSERVED_DATE_RANGE");
        assertThat(single.latestObservedEndDate()).isEqualTo(java.time.LocalDate.of(2026,11,2));
        assertThat(single.latestObservedEnd()).isNull();
        assertThat(single.historyComplete()).isFalse();
        assertThat(single.reason()).contains("exclusive end","no timezone");
        var multi=result(event("days","REQUEST","DTSTART;VALUE=DATE:20261030\r\nDTEND;VALUE=DATE:20261102"));
        assertThat(multi.latestObservedEndDate()).isEqualTo(java.time.LocalDate.of(2026,11,2));
        var weekly=result(event("week","REQUEST","DTSTART;VALUE=DATE:20261030\r\nDURATION:P1W"));
        assertThat(weekly.latestObservedEndDate()).isEqualTo(java.time.LocalDate.of(2026,11,6));
        for(String suffix:List.of("DTEND:20261102T000000Z","DTEND;VALUE=DATE:20261029","DURATION:PT24H","DURATION:P-1D"))
            assertThat(result(event("bad","REQUEST","DTSTART;VALUE=DATE:20261030\r\n"+suffix)).state()).isEqualTo("UNKNOWN");
    }

    @Test void nominalDaysAndExactHoursDifferAcrossDstAndApplyPerInstance() {
        var day=result(event("day","REQUEST","DTSTART;TZID=America/Los_Angeles:20260307T120000\r\nDURATION:P1D"));
        var hours=result(event("hours","REQUEST","DTSTART;TZID=America/Los_Angeles:20260307T120000\r\nDURATION:PT24H"));
        assertThat(day.latestObservedEnd()).isEqualTo(Instant.parse("2026-03-08T19:00:00Z"));
        assertThat(hours.latestObservedEnd()).isEqualTo(Instant.parse("2026-03-08T20:00:00Z"));
        var combined=result(event("combined","REQUEST","DTSTART;TZID=America/Los_Angeles:20261031T120000\r\nDURATION:P1DT2H\r\nRRULE:FREQ=DAILY;COUNT=2"));
        assertThat(combined.latestObservedEnd()).isEqualTo(Instant.parse("2026-11-02T22:00:00Z"));
        var exactEnd=result(event("end","REQUEST","DTSTART;TZID=America/Los_Angeles:20261031T120000\r\nDTEND;TZID=America/Los_Angeles:20261101T120000\r\nRRULE:FREQ=DAILY;COUNT=2"));
        assertThat(exactEnd.latestObservedEnd()).isEqualTo(Instant.parse("2026-11-02T21:00:00Z"));
        assertThat(result(event("instant","REQUEST","DTSTART:20200101T090000Z")).latestObservedEnd()).isEqualTo(Instant.parse("2020-01-01T09:00:00Z"));
    }

    @Test void monthlyYearlyAndWeekdayRecurrencesAreBoundedByTheirCompleteSet() {
        var monthly=result(event("monthly","REQUEST","DTSTART:20260131T090000Z\r\nDURATION:PT1H\r\nRRULE:FREQ=MONTHLY;COUNT=3"));
        assertThat(monthly.latestObservedEnd()).isEqualTo(Instant.parse("2026-05-31T10:00:00Z"));
        var leap=result(event("leap","REQUEST","DTSTART;VALUE=DATE:20240229\r\nRRULE:FREQ=YEARLY;COUNT=3"));
        assertThat(leap.latestObservedEndDate()).isEqualTo(java.time.LocalDate.of(2032,3,1));
        var weekdays=result(event("weekday","REQUEST","DTSTART:20261026T090000Z\r\nDURATION:PT1H\r\nRRULE:FREQ=WEEKLY;BYDAY=MO,WE;COUNT=4"));
        assertThat(weekdays.latestObservedEnd()).isEqualTo(Instant.parse("2026-11-04T10:00:00Z"));
        var ordinal=result(event("ordinal","REQUEST","DTSTART;VALUE=DATE:20260925\r\nRRULE:FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1;COUNT=2"));
        assertThat(ordinal.state()).isEqualTo("UNKNOWN"); // Sep 25 does not match the last weekday.
        var lastWeekday=result(event("last","REQUEST","DTSTART;VALUE=DATE:20260930\r\nRRULE:FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1;COUNT=2"));
        assertThat(lastWeekday.latestObservedEndDate()).isEqualTo(java.time.LocalDate.of(2026,10,31));
        assertThat(result(event("horizon","REQUEST","DTSTART;VALUE=DATE:20200101\r\nRRULE:FREQ=YEARLY;COUNT=12")).state()).isEqualTo("UNKNOWN");
        assertThat(result(event("sparse","REQUEST","DTSTART;VALUE=DATE:20240229\r\nRRULE:FREQ=DAILY;BYMONTH=2;BYMONTHDAY=29;UNTIL=20320229")).state()).isEqualTo("UNKNOWN");
    }

    @Test void additionalAndExcludedDatesRetainTimezoneAndDateInstanceIdentity() {
        var master=event("master","REQUEST","DTSTART;TZID=America/Los_Angeles:20261030T090000\r\nDURATION:PT1H\r\nRRULE:FREQ=DAILY;COUNT=3\r\nEXDATE;TZID=America/Los_Angeles:20261031T090000\r\nEXDATE:20261101T170000Z\r\nRDATE:20261103T170000Z\r\nRDATE;TZID=America/Los_Angeles:20261103T090000");
        var result=result(master);
        assertThat(result.observedInstances()).isEqualTo(2); // RDATE alternatives deduplicate by instant.
        assertThat(result.latestObservedEnd()).isEqualTo(Instant.parse("2026-11-03T18:00:00Z"));
        var allDay=event("all-day","REQUEST","DTSTART;VALUE=DATE:20261030\r\nRRULE:FREQ=DAILY;COUNT=3\r\nEXDATE;VALUE=DATE:20261031\r\nRDATE;VALUE=DATE:20261104\r\nSEQUENCE:1");
        var moved=event("moved","REQUEST","RECURRENCE-ID;VALUE=DATE:20261104\r\nDTSTART;VALUE=DATE:20261107\r\nDTEND;VALUE=DATE:20261109\r\nSEQUENCE:2");
        assertThat(result(allDay,moved).latestObservedEndDate()).isEqualTo(java.time.LocalDate.of(2026,11,9));
        assertThat(result(allDay,moved).observedInstances()).isEqualTo(3);
        assertThat(result(allDay,moved).sources()).hasSize(2);
    }
    @Test void unsupportedTimezoneRangedAndPeriodFormsNeverProducePastConclusions() {
        var ranged=event("range","REQUEST","DTSTART:20200101T090000Z\r\nDURATION:PT1H\r\nRECURRENCE-ID;RANGE=THISANDFUTURE:20200101T090000Z");
        var period=event("period","REQUEST","DTSTART:20200101T090000Z\r\nDURATION:PT1H\r\nRDATE;VALUE=PERIOD:20200102T090000Z/20200102T100000Z");
        String raw="BEGIN:VCALENDAR\r\nVERSION:2.0\r\nMETHOD:REQUEST\r\nBEGIN:VTIMEZONE\r\nTZID:Custom/Local\r\nEND:VTIMEZONE\r\nBEGIN:VEVENT\r\nUID:event\r\nORGANIZER:mailto:person@example.test\r\nDTSTART;TZID=Custom/Local:20200101T090000\r\nDURATION:PT1H\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        var custom=parser.parse(raw);
        assertThat(custom.state()).isEqualTo("AVAILABLE");
        var observation=new CalendarReconciler.Observation("zone","1",custom.events().getFirst());
        for(var unsupported:List.of(ranged,period,observation)) {
            var result=result(unsupported);
            assertThat(result.state()).isEqualTo("UNKNOWN");
            assertThat(result.latestObservedEnd()).isNull();
            assertThat(result.latestObservedEndDate()).isNull();
            assertThat(result.historyComplete()).isFalse();
        }
    }
}
