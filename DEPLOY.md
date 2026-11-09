# Setup and deployment

## Local setup

Use Java 21, Maven and native PostgreSQL 16. There is no Maven wrapper in this checkout. Confirm Java and Maven:

```sh
java -version
mvn -version
```

On a Homebrew Mac, if necessary:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
```

From the repository root, copy `.env.example` to `.env` only if you do not already have one. Supply the OAuth credentials for the providers you use and independent encryption values: run `openssl rand -hex 32` for `TOKEN_ENCRYPT_SECRET` and `openssl rand -hex 16` for `TOKEN_ENCRYPT_SALT`. Keep these values stable: changing them prevents decrypting existing tokens. `.env` is ignored by Git.

Enable Gmail API for the Google OAuth application and register `http://localhost:8080/login/oauth2/code/google` as its local redirect URI. For personal Outlook, register `http://localhost:8080/login/oauth2/code/microsoft` in the existing Microsoft app and use its configured tenant. `SecurityConfig` requests `openid`, `email`, `profile` and `gmail.modify`; offline refresh uses `access_type=offline`, not a Google `offline_access` scope. Configured providers require network access for OIDC discovery at startup. Without usable credentials, sign-in is unavailable.

For a fresh local cluster on macOS, install PostgreSQL and find its configuration:

```sh
brew install postgresql@16
export PG16_BIN="$(brew --prefix postgresql@16)/bin"
export PG16_DATA="$(brew --prefix)/var/postgresql@16"
```

In `$PG16_DATA/postgresql.conf`, set `listen_addresses = 'localhost'`, `port = 5433`, and
`password_encryption = 'scram-sha-256'`. On a fresh cluster dedicated to TidyMail,
replace Homebrew's initial trust rules in `$PG16_DATA/pg_hba.conf` with:

```text
local all all peer
host tidymail tidymail 127.0.0.1/32 scram-sha-256
host tidymail tidymail ::1/128 scram-sha-256
host tidymail_test,tidymail_test_migrations tidymail_test 127.0.0.1/32 scram-sha-256
host tidymail_test,tidymail_test_migrations tidymail_test ::1/128 scram-sha-256
```

Start the login service and provision the databases once. Use separate random
values for the app and test passwords:

```sh
brew services start postgresql@16
"$PG16_BIN/pg_isready" -h 127.0.0.1 -p 5433
export TIDYMAIL_DB_PASSWORD="$(openssl rand -hex 24)"
export TIDYMAIL_TEST_DB_PASSWORD="$(openssl rand -hex 24)"
"$PG16_BIN/psql" -X -p 5433 -d postgres -f tools/setup-local-postgres.sql
```

In `.env` set `DATABASE_URL=jdbc:postgresql://127.0.0.1:5433/tidymail`,
`DATABASE_USER=tidymail` and `DATABASE_PASSWORD` to the generated app password.
Keep existing OAuth and encryption settings. Use only one set of datasource keys.
Save the test password separately as described in [Testing](TESTING.md), then
unset the setup password variables when done.

```sh
mvn package -DskipTests
./tools/run-local.sh
```

Open [the local sign-in page](http://localhost:8080/login). Flyway applies the
packaged migrations and Hibernate validates the schema. A package build with
tests skipped is only a build; see [Testing](TESTING.md) for verification.

## Effective configuration

Defaults are in `src/main/resources/application.properties`. `.env.example` documents the local settings. Avoid conflicting `DATABASE_*` and `SPRING_DATASOURCE_*` values.

| Environment variable | Default / purpose |
|---|---|
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` | JDBC PostgreSQL URL, user and password; native local defaults for URL and role; supply the password |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | Google sign-in |
| `MICROSOFT_CLIENT_ID`, `MICROSOFT_CLIENT_SECRET`, `MICROSOFT_TENANT_ID` | Microsoft sign-in |
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
| `SYNC_STARTUP_DELAY_MS` | `5000`; resume active accounts shortly after startup |
| `SYNC_FRESHNESS_EXECUTOR_THREADS`, `SYNC_BACKFILL_EXECUTOR_THREADS` | `2` each; independent lanes |
| `SYNC_GMAIL_API_CONCURRENCY`, `SYNC_GMAIL_API_BATCH_DELAY_MS` | `5` concurrent requests, `100` ms batch delay |
| `SYNC_GMAIL_METADATA_BATCH_DELAY_MS` | `1000`; minimum spacing between metadata batches of up to ten messages, shared across lanes/accounts in this process |

Metadata batches are paced separately from the existing concurrency permits. Temporary sync read failures get up to five retries with increasing delays and `Retry-After` support; exhausted pages resume on the next sync cycle. Worker leases renew while reads or retries are in progress. This budget is per process, so multiple application instances still share Google project quotas.

Datasource properties can also be supplied directly through Spring configuration. `DATABASE_*` are the placeholders the application property file explicitly reads. `APP_BASE_URL` in the example does not construct OAuth redirects: registration uses Spring's request-derived `{baseUrl}`. The active `prod` profile alone is not a complete production configuration.

## Hosting and troubleshooting

Run the JAR on a persistent Java host with PostgreSQL. Use the local setup above for development and provide separate PostgreSQL credentials for deployment. Cloudflare Tunnel can proxy the host when configured separately. Register the HTTPS Google callback URI and verify forwarded host/protocol handling so Spring constructs that callback correctly. No public deployment was performed during this refresh.

Microsoft registration keys exist, but enabling that button does not provide Outlook cleanup.

| Symptom | Check |
|---|---|
| Database connection failure | `brew services list`, `pg_isready -h 127.0.0.1 -p 5433`, and the effective datasource values |
| Missing table/column | Flyway enabled, migration permissions, correct database |
| Encryption startup failure | Secret decodes to 32 bytes, salt to at least 8; generated 16-byte salt recommended |
| Missing Google button | Both credentials supplied and not placeholders; `OAuthProviderAvailability` |
| Wrong callback | Request-derived host/protocol and registered `/login/oauth2/code/google` URI |
| Reconnection required | `/activity`; failed OAuth preserves indexed data |

Ordinary logout retains data. Confirmed owner deletion is the application removal path. Back up PostgreSQL according to your retention needs.
