# Project status

Source mapped on 2026-10-14. Implementation findings below are distinct from historical test evidence and live acceptance. No application code changed during this refresh.

## Current capabilities

| Area | Finding |
|---|---|
| Gmail cleanup | Provider, indexed sender/unread surfaces, exact staging, Review execution and outcome history implemented |
| Synchronization | Recent bootstrap, progressive backfill, independent History freshness, rescan, managed refresh and Activity UI implemented |
| Unsubscribe | Candidate inspection, method-specific consent, bounded transport and outcome ledger implemented |
| Analytics | Metadata/ledger queries, UTC trends, comparisons and source drilldown implemented |
| Corrections | Account-owned sender type overrides and organization categories implemented |
| Undo | Eligible successful Archive/Trash/Mark Read targets; ten-minute engine window |
| Outlook | OAuth and abstractions exist; production mailbox provider absent |
| Privacy | Metadata stored; bodies/snippets/content excluded. Logout retains data; confirmed owner deletion removes it |
| Export | Live export subsystem removed; removal journey test retained |

## Verification evidence

The archived Phase 19 validation, dated 2026-08-14, records **447 tests across 92 reports, zero failures/errors/skips**. Independent verification records a **53-test targeted pass** and a **10,000-row synthetic performance profile**. These are historical recorded results, not a fresh run of this checkout. Original evidence is recoverable from Git; see [History](HISTORY.md).

That same verification explicitly leaves controlled live Gmail acceptance open: freshness while backfill runs, token refresh persistence and real unread/size/attachment facts. No `19-LIVE-UAT.md` was found in the archived tree. Testcontainers/MockMvc evidence does not establish provider latency or real-browser rendering.

The repository contains 83 Java test-source files, including support classes; that is not a test-case count. No current Surefire reports were found in `target/`. This refresh checked documentation/source consistency and links; it did not rerun the suite or mutate a mailbox.

At mapping time, `src/main/resources/templates/login.html` contained a pre-existing uncommitted redesign. It is outside this documentation change and excluded from its commits.

## Remaining work

| Priority | Work | Evidence / completion criterion |
|---|---|---|
| First | Controlled live Gmail acceptance | Prove fresh mail during backfill, managed refresh and indexed metadata against a test mailbox; record redacted observations |
| First | Current release verification | Run affected/full PostgreSQL tests and authenticated browser checks after application changes |
| Next | Classification quality | Rules and corrections exist; old plans proposed a labeled accuracy benchmark and optional local ML, neither delivered in source |
| Next | Architectural maintenance | Large candidate/analytics services, duplicate validation/account helpers and in-JVM staging locks remain; preserve journeys while refactoring |
| Later | Gmail rules and bulk execution feedback | Filter creation and durable background bulk-action progress were planned; current cleanup is request-dispatched, unlike background mailbox sync |
| Scope decision | Outlook completion | Implement and verify a provider before claiming Outlook cleanup support |
| Scope decision | Automatic data expiry | Current jobs perform partial housekeeping, not full timed deletion of metadata/tokens |

These priorities consolidate unfinished historical work and mapping findings; they are not authorization to implement all of it. No production deployment, full-mailbox capacity guarantee or comprehensive security certification is established by this map.
