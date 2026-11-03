package com.tidymail.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

/** Independent read-only settlement check; no HTTP client, secrets or spending changes. */
public class Phase3PilotAudit {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[0].equals("--audit-ledger")) throw new IllegalArgumentException("Explicit audit-ledger mode required");
        Path path = Path.of(args[1]);
        var json = new ObjectMapper();
        var ledger = json.readTree(path.toFile());
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        try (var ctx = new SpringApplicationBuilder(Phase3JevPilot.Config.class).web(WebApplicationType.NONE)
                .run("--spring.main.banner-mode=off", "--logging.level.root=OFF", "--logging.level.com.tidymail=OFF",
                    "--spring.sql.init.mode=never", "--spring.datasource.hikari.read-only=true",
                    "--spring.datasource.hikari.maximum-pool-size=1", "--spring.datasource.hikari.minimum-idle=0")) {
            var jdbc = ctx.getBean(JdbcTemplate.class);
            for (var attempt : ledger.path("attempts")) {
                var row = jdbc.queryForMap("SELECT state,cost,benchmark_variant FROM classification_attempts WHERE id=?", UUID.fromString(attempt.path("attempt").asText()));
                if (!row.get("state").equals("REPORTED") || !row.get("benchmark_variant").equals("phase3-development-v1"))
                    throw new IllegalStateException("Attempt is not settled as expected");
                BigDecimal cost = (BigDecimal) row.get("cost");
                if (cost.compareTo(attempt.path("reported_usd").decimalValue()) != 0) throw new IllegalStateException("Charge mismatch");
                sum = sum.add(cost); count++;
            }
            if (sum.compareTo(ledger.path("reported_usd").decimalValue()) != 0) throw new IllegalStateException("Total mismatch");
            var result = json.createObjectNode();
            result.put("status", "ALL_ATTEMPTS_SETTLED_AND_MATCHED").put("attempt_count", count).put("reported_usd", sum)
                    .put("read_only", true).put("model_calls", 0);
            json.writerWithDefaultPrettyPrinter().writeValue(path.resolveSibling("settlement-audit.json").toFile(), result);
            System.out.println("Settlement audit passed: " + count + " attempts, exact reported total $" + sum.toPlainString());
        }
    }
}
