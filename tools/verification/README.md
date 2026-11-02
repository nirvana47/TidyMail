# Read-only email evidence probe

Opt-in feasibility tooling, separate from the application. Reads existing app configuration and encrypted credentials through its crypto classes. Uses a read-only database connection and, if needed, refreshes the Google access token only in memory. Does not start application workers, run migrations, modify mailbox labels, persist bodies, or call any model. Normal app metadata/body protections are untouched.

Requires Java 21, the existing `target/classes` and packaged JAR, the local database, the configured connected account, and a temporary Python environment with `requirements.txt` installed. Configuration is loaded normally; never print or copy secrets into commands/reports.

```sh
python3 -m venv /tmp/tidymail-evidence-venv
/tmp/tidymail-evidence-venv/bin/pip install -r tools/verification/requirements.txt
python3 tools/verification/run_gmail_probe.py --compile-only
/tmp/tidymail-evidence-venv/bin/python -m unittest discover -s tools/verification -p 'test_calendar_probe.py' -v
```

Only when authorized to perform live reads:

```sh
python3 tools/verification/run_gmail_probe.py --live
python3 tools/verification/run_gmail_probe.py --live --cost-check
```

Each invocation caps Gmail GETs at 64, decoded JSON response bytes at roughly 8 MiB total and 1 MiB per response. Requests are sequential, with no automatic retries. Reads calendar parts only under 128 KiB and full-body variants only for selected small messages. MIME field masks inspect six nested levels; deeper structures need another explicitly scoped check. A response exceeding a cap is recorded as incomplete, not accepted as full evidence.

The capability run samples one retained message per named organization, up to four ICS-search candidates and two large-message candidates. The cost run compares identical field masks with/without snippet for the four organizations, plus at most one large message lacking Gmail's attachment search flag. Search results are small/nonrepresentative, not exhaustive.

Reports contain sizes/timings/types/presence and conservative calendar facts, no message IDs, subjects, addresses, filenames, body text, calendar titles or tokens. Original reports are not overwritten on subsequent runs. The parser deliberately leaves recurrence/update reconciliation, floating times and unknown all-day timezone boundaries unresolved; it is a feasibility helper, not a complete calendar cleanup engine. Production library/runtime selection remains a separate decision.
