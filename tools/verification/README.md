# Verification tools

The `.cjs` scripts exercise desktop workflows with headless Playwright. The
selection script intercepts requests with fixtures; the other scripts use the
isolated `DashboardBrowserFixture`. Neither proves live Gmail/Outlook delivery or
speed. Start fresh fixture data for each script that changes a rule or selection.
See [test commands](../../TESTING.md).

`GmailEvidenceProbe.java`, `run_gmail_probe.py`, `parse_calendar_probe.py` and
`test_calendar_probe.py` support explicitly requested read-only calendar evidence
inspection. They are not normal suite or release steps. Never run a retained-mail
probe without task-specific authorization; keep outputs private.

Jev/model preparation, dispatch, replay and scoring runners are removed along with
the unused application feature. Old reports remain historical evidence only;
they do not authorize replay or describe current application functionality.
