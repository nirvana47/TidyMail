# Setup and deployment

Mapped from source configuration on 2026-10-14. These instructions do not establish that this checkout is running or deployed.

## Local setup

Use Java 21, Maven and a running Docker-compatible engine. There is no Maven wrapper in this checkout. Confirm the tools:

```sh
java -version
mvn -version
docker info
```

On a Homebrew Mac, if necessary:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
```

From the repository root, copy `.env.example` to `.env` only if you do not already have one. Supply Google credentials and independent encryption values: run `openssl rand -hex 32` for `TOKEN_ENCRYPT_SECRET` and `openssl rand -hex 16` for `TOKEN_ENCRYPT_SALT`. Keep these values stable: changing them prevents decrypting existing tokens. `.env` is ignored by Git.

Enable Gmail API for the Google OAuth application and register `http://localhost:8080/login/oauth2/code/google` as the local redirect URI. `SecurityConfig` requests `openid`, `email`, `profile` and `gmail.modify`; offline refresh uses `access_type=offline`, not a Google `offline_access` scope. Configured providers require network access for OIDC discovery at startup. Without usable credentials, sign-in is unavailable.

Start the database, wait for readiness, then build and run:

```sh
docker compose up -d db
docker compose exec db pg_isready -U tidymail -d tidymail
mvn package -DskipTests
java -Xms512m -Xmx1g -jar target/tidymail-0.1.0.jar
```

Open `http://localhost:8080/login`. Skipping tests builds the JAR without validating behavior; see [Testing](docs/TESTING.md) before accepting a release.

Compose supplies PostgreSQL 16 on port 5432, database/user `tidymail`, development password `tidymaildev`, and persistent volume `tidymail-db-data`. Stopping the database does not remove data. Flyway runs by default; Hibernate validates the schema. Do not replace migrations with Hibernate `update`.

## Effective configuration

Defaults are in `src/main/resources/application.properties`. `.env.example` has legacy comments and extra values that may not be consumed. Prefer the explicit keys below; avoid conflicting `DATABASE_*` and `SPRING_DATASOURCE_*` values.

| Environment variable | Default / purpose |
|---|---|
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` | JDBC PostgreSQL URL, user and password; local Compose defaults |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | Google sign-in |
| `TOKEN_ENCRYPT_SECRET`, `TOKEN_ENCRYPT_SALT` | OAuth token encryption material |
| `SERVER_PORT` | `8080` |
| `SPRING_FLYWAY_ENABLED` | `true` |
| `SESSION_COOKIE_SECURE` | `false` locally; set `true` for HTTPS |
| `SERVER_SERVLET_SESSION_TIMEOUT` | `86400s`; does not purge data |
| `THYMELEAF_CACHE` | `false`; enable for deployed templates |
| `SYNC_INITIAL_PAGE_SIZE` | `200`; valid range 100–250 |
| `SYNC_MAX_MESSAGES_PER_ACCOUNT` | `0`, unlimited; positive values bound unique indexed messages |
| `SYNC_GMAIL_BACKFILL_PAGE_SIZE` | `500` |
| `SYNC_FRESHNESS_CYCLE_MS` | `300000`, five minutes |
| `SYNC_FRESHNESS_EXECUTOR_THREADS`, `SYNC_BACKFILL_EXECUTOR_THREADS` | `2` each; independent lanes |
| `SYNC_GMAIL_API_CONCURRENCY`, `SYNC_GMAIL_API_BATCH_DELAY_MS` | `5` concurrent requests, `100` ms batch delay |

Datasource properties can also be supplied directly through Spring configuration. `DATABASE_*` are the placeholders the application property file explicitly reads. `APP_BASE_URL` in the example does not construct OAuth redirects: registration uses Spring's request-derived `{baseUrl}`. The active `prod` profile alone is not a complete production configuration.

## Hosting and troubleshooting

Run the JAR on a persistent Java host with PostgreSQL. This repository supplies local database Compose configuration, not an application container or deployment pipeline. Cloudflare Tunnel can proxy the host when configured separately. Register the HTTPS Google callback URI and verify forwarded host/protocol handling so Spring constructs that callback correctly. No public deployment was performed during this refresh.

Microsoft registration keys exist, but enabling that button does not provide Outlook cleanup.

| Symptom | Check |
|---|---|
| Database connection failure | Docker, readiness, port 5432 and effective datasource values |
| Missing table/column | Flyway enabled, migration permissions, correct database |
| Encryption startup failure | Secret decodes to 32 bytes, salt to at least 8; generated 16-byte salt recommended |
| Missing Google button | Both credentials supplied and not placeholders; `OAuthProviderAvailability` |
| Wrong callback | Request-derived host/protocol and registered `/login/oauth2/code/google` URI |
| Reconnection required | `/activity`; failed OAuth preserves indexed data |

Ordinary logout retains data. Confirmed owner deletion is the application removal path. Back up PostgreSQL according to your retention needs.
