package com.tidymail.service;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CalendarEvidenceParserTest {
    final CalendarEvidenceParser parser=new CalendarEvidenceParser();
    String calendar(String method,String properties) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nMETHOD:"+method+"\r\nBEGIN:VEVENT\r\nUID:private-event-id\r\n"+properties+"\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
    }
    @Test void localFactsRetainRecurrenceInstanceAndCancellationWithoutClaimingExpired() {
        var facts=parser.parse(calendar("CANCEL","DTSTART;TZID=America/Los_Angeles:20261101T090000\r\nDTEND;TZID=America/Los_Angeles:20261101T100000\r\nRECURRENCE-ID:20261025T160000Z\r\nSEQUENCE:3"));
        assertThat(facts.state()).isEqualTo("AVAILABLE");
        var event=facts.events().getFirst();
        assertThat(event.recurring()).isTrue(); assertThat(event.cancelled()).isTrue();
        assertThat(event.timezone()).isEqualTo("America/Los_Angeles"); assertThat(event.sequence()).isEqualTo("3");
        assertThat(event.uidHash()).hasSize(64).doesNotContain("private-event-id");
        assertThat(facts.conclusion()).contains("still need checking");
    }
    @Test void allDayFloatingAndOldDatesRemainUnreconciled() {
        for(String dates:new String[]{"DTSTART;VALUE=DATE:20200101\r\nDTEND;VALUE=DATE:20200102", "DTSTART:20200101T090000", "DTSTART:20200101T090000Z\r\nDURATION:PT1H"}) {
            var facts=parser.parse(calendar("REQUEST",dates));
            assertThat(facts.state()).isEqualTo("AVAILABLE");assertThat(facts.conclusion()).contains("still need checking");
        }
    }
    @Test void malformedOrOversizedContentCannotBecomeKnownEvent() {
        assertThat(parser.parse("not an ICS file").state()).isEqualTo("FAILED");
        assertThat(parser.parse("x".repeat(131073)).state()).isEqualTo("TRUNCATED");
        assertThat(parser.parse(calendar("REQUEST","DTSTART:20200101T090000Z\r\nDTSTART:20200102T090000Z")).state()).isEqualTo("FAILED");
    }
    @Test void repeatedRecurrencePropertiesKeepTheirOwnTimezonesAndInvalidTypesStayExplicit() {
        var event=parser.parse(calendar("REQUEST","DTSTART;TZID=America/Los_Angeles:20261101T090000\r\nRDATE;TZID=America/New_York:20261102T120000\r\nRDATE:20261103T170000Z\r\nEXDATE;TZID=America/Los_Angeles:20261104T090000,20261105T090000")).events().getFirst();
        assertThat(event.additions()).containsExactly(
            new CalendarEvidenceParser.OccurrenceDate("20261102T120000","America/New_York"),
            new CalendarEvidenceParser.OccurrenceDate("20261103T170000Z",null));
        assertThat(event.exclusions()).hasSize(2);
        assertThat(event.invalidTemporalParameters()).isFalse();
        assertThat(event.customTimezone()).isFalse();
        var invalid=parser.parse(calendar("REQUEST","DTSTART;VALUE=DATE;TZID=UTC:20261101")).events().getFirst();
        assertThat(invalid.invalidTemporalParameters()).isTrue();
        var range=parser.parse(calendar("REQUEST","DTSTART:20261101T090000Z\r\nRECURRENCE-ID;RANGE=THISANDFUTURE:20261101T090000Z")).events().getFirst();
        assertThat(range.rangedUpdate()).isTrue();
    }
}
