# Project Research Summary

## Key Findings

The mock suite describes a complete cleanup workspace, not a dashboard restyle. Its five pages require message-level metadata persistence, immutable action scopes, standards-aware unsubscribe execution, historical outcome events, shared filters, and a unified responsive shell.

The current app performs provider scans inside page and action requests, persists sender aggregates but not queryable message metadata, supports only sender-scoped queued actions, and marks a queued batch applied without preserving provider result detail. Those constraints prevent accurate Unread rows, exact review previews, safe unsubscribe, drilldowns, and analytics.

The smallest coherent architecture keeps the current Spring Boot, Thymeleaf, htmx, Postgres, and Gmail SDK stack. It adds a bounded metadata index, richer action outcomes, deterministic recommendation services, safe unsubscribe HTTP handling, and query-driven analytics. No SPA, message-body access, AI, or external queue is needed.

## Feature Table Stakes

- Fully working Senders, Unread, Unsubscribe, Review, and Analytics routes
- Shared account scope, search, filters, sorting, pagination, selection, loading, empty, and error states
- Exact action previews, immutable message-ID scopes, staged review, partial outcomes, history, and durable undo
- Optional immediate execution using the same safety engine
- RFC 8058 one-click unsubscribe plus explicit legacy, mailto, and manual alternatives
- Historical analytics with comparisons, drilldowns, and CSV export

## Stack Additions

- `message_metadata` persistence with indexed account/time/sender/label columns
- Expanded action batch and outcome fields
- Spring `RestClient` unsubscribe client with SSRF controls
- Repository projections or `JdbcTemplate` analytics queries
- Shared server-rendered workspace components and inline accessible charts

## Watch Out For

- Do not derive historical analytics from present-day aggregate rows.
- Do not re-query action scope after selection.
- Do not treat provider acceptance as per-message confirmation.
- Do not let protected rows enter bulk operations through crafted requests.
- Do not allow immediate mode to bypass logging, preview, or undo.
- Do not automate untrusted unsubscribe URLs without network controls.
- Do not ship any decorative-only mock control.

## Implications for Roadmap

Persistence and action contracts must precede page implementation. Operational pages must precede analytics so analytics can consume real sync and action facts. Final verification needs localhost browser UAT across all five routes, including narrow viewport checks and real Gmail action/undo behavior.

## Sources

- `tidymail-ux-rehaul/pages/*.html`
- `src/main/java/com/tidymail/**`
- `src/main/resources/templates/**`
- `.planning/PROJECT.md`
- `.planning/STATE.md`
- Prior v1.0 planning research archived under `.planning/milestones/v1.0-research/`
