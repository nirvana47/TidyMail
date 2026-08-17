package com.tidymail.support;

import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * Resets the shared Postgres schema before each Spring integration test.
 */
public final class PostgresDatabaseCleanupTestExecutionListener
        extends AbstractTestExecutionListener {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        var context = testContext.getApplicationContext();
        if (!context.containsBean("jdbcTemplate")) {
            return;
        }
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        Boolean schemaReady = jdbc.queryForObject(
            "SELECT to_regclass('public.user_accounts') IS NOT NULL",
            Boolean.class);
        if (Boolean.TRUE.equals(schemaReady)) {
            jdbc.execute("TRUNCATE TABLE user_accounts RESTART IDENTITY CASCADE");
        }
    }
}
