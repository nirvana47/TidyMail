---
phase: 14-ui-fidelity-and-route-recovery
plan: "01"
status: complete
completed: 2026-08-11
---

# Plan 14-01 Summary

Restored Unread and Unsubscribe against PostgreSQL production data.

## Changes

- Replaced integer conversion of PostgreSQL booleans with JDBC boolean mapping.
- Replaced the invalid grouped protected-sender correlation with a join/aggregate.
- Preserved the generic private-safe 503 response while logging the complete exception.

## Evidence

- Real PostgreSQL service check: 34,072 unread rows and 462 unsubscribe candidates.
- Phase 10/11 focused repository suites passed.
- PostgreSQL 16 migration suites passed in the final 351-test run.

## Safety

No selection, action, execution, or provider-mutation path changed. Exact-ID
materialization and protected-sender predicates remain in force.
