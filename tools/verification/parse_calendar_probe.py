"""Parse calendar payloads from stdin; emit facts only, never message/event text."""
import sys, json
from datetime import datetime, date, timedelta, timezone
from zoneinfo import ZoneInfo
from icalendar import Calendar

def facts(raw, now=None):
    now = now or datetime.now(timezone.utc)
    try:
        cal = Calendar.from_ical(raw)
        events=[]
        for event in cal.walk('VEVENT'):
            start=event.decoded('DTSTART', None)
            end=event.decoded('DTEND', None)
            duration=event.decoded('DURATION', None)
            recurring=any(k in event for k in ['RRULE','RDATE','EXDATE','RECURRENCE-ID'])
            if end is None and start is not None and duration is not None:
                end=start+duration
            all_day=isinstance(start,date) and not isinstance(start,datetime)
            if end is None and all_day: end=start+timedelta(days=1)
            if isinstance(end,datetime) and end.tzinfo is not None:
                past=end.astimezone(timezone.utc)<now
            elif isinstance(end,date) and not isinstance(end,datetime):
                # Day boundary needs an account/event timezone. Do not assume UTC.
                past=None
            else: past=None
            events.append(dict(uid_present=bool(event.get('UID')),start_present=start is not None,
                end_present=end is not None,all_day=all_day,recurrence_or_exception=recurring,
                sequence_present='SEQUENCE' in event,cancelled=str(event.get('STATUS',''))=='CANCELLED' or str(cal.get('METHOD',''))=='CANCEL',
                past_end_in_this_message=past if not recurring else None,
                needs_series_or_update_reconciliation=True,
                parser_errors=len(getattr(event,'errors',[]))))
        return dict(parsed=True,method=str(cal.get('METHOD','')),event_count=len(events),events=events)
    except Exception as exc:
        return dict(parsed=False,error_class=type(exc).__name__)

if __name__=='__main__':
    payloads=json.load(sys.stdin)
    print(json.dumps([facts(raw) for raw in payloads]))
