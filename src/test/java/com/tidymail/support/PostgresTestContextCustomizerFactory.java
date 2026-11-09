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
 * Routes every Spring integration-test context through the dedicated native
 * PostgreSQL test database. Registered through test-only spring.factories.
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
            NativeTestPostgres.integrationDatabase().pinDataSources(context);
            Map<String, Object> properties = Map.of(
                "spring.datasource.hikari.maximum-pool-size", "2",
                "spring.datasource.hikari.minimum-idle", "0",
                "spring.flyway.enabled", "true",
                "spring.flyway.locations", "classpath:db/migration",
                "spring.jpa.hibernate.ddl-auto", "validate",
                "spring.task.scheduling.enabled", "false"
            );
            context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("nativeTestDefaults", properties));
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
