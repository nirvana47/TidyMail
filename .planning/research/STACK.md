# Stack Research: v1.1 Complete Cleanup Workspace

## Existing Stack To Keep

- Java 21, Spring Boot, Spring MVC, Spring Security OAuth2 Client
- Thymeleaf plus htmx for server-rendered pages and partial updates
- PostgreSQL 16, Spring Data JPA, Flyway
- Gmail Java SDK with metadata-only message retrieval
- JUnit 5, Spring Boot Test, MockMvc, H2 migration mirrors

## Required Additions

### Persisted metadata index

Add a `message_metadata` table keyed by account and provider message ID. Store only fields already allowed by the privacy contract: thread ID, sender address/domain/display name, subject header, received timestamp, labels, unsubscribe headers, precedence, references, in-reply-to, and sync timestamps. This replaces repeated 500-message live scans for filters, exact action previews, unread rows, and analytics.

### Action and outcome model

Expand `user_actions` or introduce action batches plus action items. Persist target type, exact message IDs, selected recommendation, execution policy, status, requested/succeeded/failed counts, failure reasons, approval and completion timestamps, inverse data, and unsubscribe outcome.

### HTTP unsubscribe client

Use Spring's JDK-backed `RestClient` with strict redirect, timeout, scheme, and private-network protections. Support RFC 8058 POST when `List-Unsubscribe-Post: List-Unsubscribe=One-Click` is present, legacy HTTPS GET as an explicit fallback, `mailto:` handoff, and manual account-page review.

### Analytics query layer

Use repository projections or `JdbcTemplate` for time-bucket and grouped aggregate queries. Keep chart rendering server-side with semantic HTML/CSS or small inline SVG; do not add a SPA or a client charting framework unless accessibility cannot be met otherwise.

### Export

Generate CSV with standard Java writers and explicit column schemas. Snapshot sharing should default to a downloadable sanitized report; public hosted links remain disabled unless a future secure storage model is approved.

## Do Not Add

- Message-body access, LLMs, embeddings, or classification services
- Redis, Kafka, RabbitMQ, or an external job queue
- React/Vue/Svelte or a separate frontend build
- A second analytics database
- Browser-side action scope as the source of truth
