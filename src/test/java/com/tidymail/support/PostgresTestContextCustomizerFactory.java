package com.tidymail.support;

import java.util.List;
import java.util.Map;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Routes every Spring integration-test context through the shared production-like
 * Postgres container. Registered through test-only spring.factories.
 */
public final class PostgresTestContextCustomizerFactory implements ContextCustomizerFactory {

    @Override
    public ContextCustomizer createContextCustomizer(
            Class<?> testClass,
            List<ContextConfigurationAttributes> configAttributes) {
        return new SharedPostgresContextCustomizer();
    }

    private static final class SharedPostgresContextCustomizer implements ContextCustomizer {

        @Override
        public void customizeContext(
                ConfigurableApplicationContext context,
                MergedContextConfiguration mergedConfig) {
            Map<String, Object> properties = Map.of(
                "spring.datasource.url", PostgresJourneyTest.POSTGRES.getJdbcUrl(),
                "spring.datasource.username", PostgresJourneyTest.POSTGRES.getUsername(),
                "spring.datasource.password", PostgresJourneyTest.POSTGRES.getPassword(),
                "spring.datasource.driver-class-name", "org.postgresql.Driver",
                "spring.datasource.hikari.maximum-pool-size", "2",
                "spring.datasource.hikari.minimum-idle", "0",
                "spring.flyway.enabled", "true",
                "spring.flyway.locations", "classpath:db/migration",
                "spring.jpa.hibernate.ddl-auto", "validate",
                "spring.task.scheduling.enabled", "false"
            );
            context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("sharedPostgresTestcontainer", properties));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof SharedPostgresContextCustomizer;
        }

        @Override
        public int hashCode() {
            return SharedPostgresContextCustomizer.class.hashCode();
        }
    }
}
