package com.dompetgaruda.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the Spring context against a real Postgres container (api profile),
 * confirms Flyway applied V1, and asserts key tables exist.
 */
class ScaffoldSmokeTest extends ApiIntegrationTestBase {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("admin.api-token", () -> "smoke-test-admin-token");
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void flywayAppliedV1AndKeyTablesExist() {
        Integer migrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true",
                Integer.class);
        assertThat(migrationCount).isGreaterThanOrEqualTo(2); // V1 + V2

        for (String table : new String[]{"users", "devices", "accounts",
                "ledger_transactions", "ledger_entries",
                "offline_certificates", "sync_inbox",
                "offline_transactions", "shedlock"}) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables " +
                    "WHERE table_schema = 'public' AND table_name = ?",
                    Integer.class, table);
            assertThat(count).as("table '%s' should exist after V1 migration", table)
                    .isEqualTo(1);
        }
    }
}
