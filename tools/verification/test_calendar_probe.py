"""Meaningful boundary checks for facts derived from downloaded calendar content."""
import unittest
from datetime import datetime, timezone
from parse_calendar_probe import facts

NOW=datetime(2026,11,1,tzinfo=timezone.utc)
def calendar(lines):
    return '\r\n'.join(['BEGIN:VCALENDAR','VERSION:2.0','METHOD:REQUEST','BEGIN:VEVENT','UID:fixture',*lines,'END:VEVENT','END:VCALENDAR',''])
class CalendarEvidenceTests(unittest.TestCase):
    def test_utc_event_end_is_past(self):
        event=facts(calendar(['DTSTART:20260101T100000Z','DTEND:20260101T110000Z']),NOW)['events'][0]
        self.assertTrue(event['past_end_in_this_message'])
        self.assertTrue(event['needs_series_or_update_reconciliation'])
    def test_future_event_not_past(self):
        event=facts(calendar(['DTSTART:20270101T100000Z','DTEND:20270101T110000Z']),NOW)['events'][0]
        self.assertFalse(event['past_end_in_this_message'])
    def test_recurring_series_not_expired_from_first_end(self):
        event=facts(calendar(['DTSTART:20250101T100000Z','DTEND:20250101T110000Z','RRULE:FREQ=YEARLY']),NOW)['events'][0]
        self.assertTrue(event['recurrence_or_exception'])
        self.assertIsNone(event['past_end_in_this_message'])
    def test_all_day_needs_timezone_for_boundary(self):
        event=facts(calendar(['DTSTART;VALUE=DATE:20261031']),NOW)['events'][0]
        self.assertTrue(event['all_day']);self.assertTrue(event['end_present'])
        self.assertIsNone(event['past_end_in_this_message'])
    def test_floating_time_not_assumed_utc(self):
        event=facts(calendar(['DTSTART:20250101T100000','DTEND:20250101T110000']),NOW)['events'][0]
        self.assertIsNone(event['past_end_in_this_message'])
    def test_invalid_content_does_not_become_event(self):
        self.assertFalse(facts('This is not an ICS payload',NOW)['parsed'])
if __name__=='__main__': unittest.main()
