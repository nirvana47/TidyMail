package com.tidymail.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Test-only connection settings. No application URL or database credential is read here. */
public final class NativeTestPostgres {

    private static final String USER = "tidymail_test";
    private final String database;
    private final String url;
    private final String password;

    private NativeTestPostgres(String database) {
        this.database = database;
        String port = System.getenv().getOrDefault("TIDYMAIL_TEST_DB_PORT", "5433");
        if (!port.matches("[0-9]{1,5}") || Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65535) {
            throw new IllegalStateException("TIDYMAIL_TEST_DB_PORT must be a TCP port number.");
        }
        password = System.getenv("TIDYMAIL_TEST_DB_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Set TIDYMAIL_TEST_DB_PASSWORD; see TESTING.md.");
        }
        url = "jdbc:postgresql://127.0.0.1:" + port + "/" + database;
        try (Connection connection = DriverManager.getConnection(url, USER, password)) {
            verify(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot use the dedicated PostgreSQL 16 database "
                + database + "; see TESTING.md.", e);
        }
    }

    private static class Integration {
        static final NativeTestPostgres INSTANCE = new NativeTestPostgres("tidymail_test");
    }

    static NativeTestPostgres integrationDatabase() {
        return Integration.INSTANCE;
    }

    public static NativeTestPostgres cleanMigrationDatabase() {
        var database = new NativeTestPostgres("tidymail_test_migrations");
        // Historical upgrade tests need a fresh public schema without resetting Spring's
        // cached integration-test contexts. Surefire runs these classes serially.
        Flyway.configure()
            .dataSource(database.url, USER, database.password)
            .locations("classpath:db/migration")
            .schemas("public")
            .cleanDisabled(false)
            .load().clean();
        return database;
    }

    void verify(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT current_database() = ? AND current_user = ? AND session_user = ?
                AND current_setting('server_version_num')::int BETWEEN 160000 AND 169999
                AND EXISTS (
                    SELECT 1 FROM pg_database d JOIN pg_roles r ON d.datdba = r.oid
                    WHERE d.datname = current_database() AND r.rolname = current_user
                      AND NOT (r.rolsuper OR r.rolcreatedb OR r.rolcreaterole
                               OR r.rolreplication OR r.rolbypassrls)
                )
                AND NOT EXISTS (
                    SELECT 1 FROM pg_database d WHERE d.datname = 'tidymail'
                    AND (has_database_privilege(current_user, d.oid, 'CONNECT')
                         OR pg_has_role(d.datdba, 'MEMBER'))
                )
            """)) {
            statement.setString(1, database);
            statement.setString(2, USER);
            statement.setString(3, USER);
            try (var result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new IllegalStateException("Refusing to use a database outside the isolated TidyMail test setup.");
                }
            }
        }
    }

    public void pinDataSources(ConfigurableApplicationContext context) {
        // Pin Flyway as well as Hikari, even when the developer exports live Spring settings.
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
            "nativeTestPostgres", Map.of(
                "spring.datasource.url", url,
                "spring.datasource.username", USER,
                "spring.datasource.password", password,
                "spring.datasource.hikari.jdbc-url", url,
                "spring.datasource.hikari.username", USER,
                "spring.datasource.hikari.password", password,
                "spring.flyway.url", url,
                "spring.flyway.user", USER,
                "spring.flyway.password", password,
                "spring.flyway.clean-disabled", "true")));
    }

    public String getJdbcUrl() {
        return url;
    }

    public String getUsername() {
        return USER;
    }

    public String getPassword() {
        return password;
    }
}
